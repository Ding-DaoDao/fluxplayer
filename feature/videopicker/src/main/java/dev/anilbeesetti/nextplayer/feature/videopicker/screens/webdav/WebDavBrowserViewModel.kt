package dev.anilbeesetti.nextplayer.feature.videopicker.screens.webdav

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.anilbeesetti.nextplayer.core.data.repository.PlaybackHistoryRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.data.repository.WebDavRepository
import dev.anilbeesetti.nextplayer.core.model.WebDavResource
import dev.anilbeesetti.nextplayer.core.model.WebDavServer
import dev.anilbeesetti.nextplayer.feature.videopicker.CommonStateSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WebDavBreadcrumb(val label: String, val path: String)

/**
 * 导航栈中每一层目录的状态
 */
data class WebDavDirectoryState(
    val path: String,
    val label: String,
    override val items: List<WebDavResource> = emptyList(),
    override val isLoading: Boolean = false,
    override val error: String? = null,
) : dev.anilbeesetti.nextplayer.feature.videopicker.DirectoryState {
    override val key: String get() = path
}

/**
 * WebDAV 浏览器 ViewModel
 *
 * 使用栈式叠加导航：每个目录层级有自己的 WebDavDirectoryState。
 * UI 通过 key(path) + rememberLazyListState() 为每层保持独立的滚动位置。
 */
@HiltViewModel
class WebDavBrowserViewModel @Inject constructor(
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val preferencesRepository: PreferencesRepository,
    private val webDavRepository: WebDavRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    companion object {
        private const val TAG = "WebDavBrowserVM"
        private const val PROVIDER_LABEL = "webdav"
    }

    // region ==================== 状态 ====================

    private val _navigationStack = MutableStateFlow(
        listOf(WebDavDirectoryState(path = "/", label = "根目录"))
    )
    val navigationStack: StateFlow<List<WebDavDirectoryState>> = _navigationStack.asStateFlow()

    /** 面包屑 —— 从 navigationStack 派生 */
    private val _stateFlow = MutableStateFlow(
        CommonStateSnapshot(
            breadcrumbs = listOf(WebDavBreadcrumb("根目录", "/")),
            orderBy = "name",
            orderDirection = "ASC"
        )
    )
    val stateFlow: StateFlow<CommonStateSnapshot<WebDavBreadcrumb>> = _stateFlow.asStateFlow()

    // WebDAV 特有状态
    data class WebDavExtraState(
        val servers: List<WebDavServer> = emptyList(),
        val activeServers: List<WebDavServer> = emptyList(),
        val selectedServer: WebDavServer? = null,
        val isConfigured: Boolean = false,
        val playedUriStrings: Set<String> = emptySet(),
    )

    private val _extraState = MutableStateFlow(WebDavExtraState())
    val extraState: StateFlow<WebDavExtraState> = _extraState.asStateFlow()

    /** 内存目录缓存 —— 已访问目录的列表，返回上级时直接恢复，不走网络 */
    private val directoryCache = mutableMapOf<String, List<WebDavResource>>()

    // endregion

    // region ==================== 初始化 ====================

    init {
        viewModelScope.launch {
            playbackHistoryRepository.getHistoryFlow().collect { history ->
                val historyUris = history.map { it.uriString }.toSet()
                _extraState.update { it.copy(playedUriStrings = historyUris) }
            }
        }
        viewModelScope.launch {
            webDavRepository.servers.collect { servers ->
                _extraState.update { it.copy(servers = servers) }
            }
        }
        viewModelScope.launch {
            webDavRepository.activeServers.collect { actives ->
                val prevSelected = _extraState.value.selectedServer
                _extraState.update {
                    it.copy(
                        activeServers = actives,
                        selectedServer = prevSelected?.takeIf { p -> actives.any { a -> a.id == p.id } }
                            ?: actives.firstOrNull(),
                        isConfigured = actives.isNotEmpty(),
                    )
                }
                val newSelected = _extraState.value.selectedServer
                if (newSelected != null && newSelected != prevSelected) {
                    loadDirectory("/")
                }
            }
        }
    }

    // endregion

    // region ==================== 目录加载 ====================

    fun loadDirectory(path: String) {
        val server = _extraState.value.selectedServer ?: return

        // 标记当前栈顶为 loading
        updateStackTop(path) { it.copy(isLoading = true, error = null) }

        val loadingPath = path

        viewModelScope.launch {
            val result = webDavRepository.listDirectory(
                baseUrl = server.normalizedUrl,
                path = loadingPath,
                authHeader = server.basicAuthHeader,
            )

            // 路径已变化（用户导航到其他目录），忽略结果
            val currentTop = _navigationStack.value.lastOrNull()
            if (currentTop == null || currentTop.path != loadingPath) return@launch

            result.fold(
                onSuccess = { resources ->
                    val filtered = resources.filter { res ->
                        !res.name.startsWith(".") && res.name.isNotBlank()
                    }
                    directoryCache[loadingPath] = filtered
                    Log.d(TAG, "loadDirectory: cached path=$loadingPath, items=${filtered.size}")
                    updateStackTop(loadingPath) {
                        it.copy(items = filtered, isLoading = false, error = null)
                    }
                },
                onFailure = { error ->
                    val top = _navigationStack.value.lastOrNull()
                    if (top != null && top.path == loadingPath) {
                        updateStackTop(loadingPath) {
                            it.copy(isLoading = false, error = error.message ?: "加载失败")
                        }
                    }
                }
            )
        }
    }

    /** 更新栈中指定路径的条目（通常为栈顶） */
    private fun updateStackTop(path: String, transform: (WebDavDirectoryState) -> WebDavDirectoryState) {
        _navigationStack.update { stack ->
            val index = stack.indexOfLast { it.path == path }
            if (index < 0) return@update stack
            stack.toMutableList().apply {
                set(index, transform(get(index)))
            }
        }
    }

    // endregion

    // region ==================== 导航 ====================

    fun navigateToDir(index: Int) {
        val stack = _navigationStack.value
        val current = stack.lastOrNull() ?: return
        val item = current.items.getOrNull(index) ?: return
        if (!item.isDirectory) return

        val path = item.path
        val label = item.name

        // 从缓存或空列表创建新栈条目
        val cached = directoryCache[path]
        val newEntry = WebDavDirectoryState(
            path = path,
            label = label,
            items = cached ?: emptyList(),
            isLoading = cached == null,
        )

        _navigationStack.update { it + newEntry }
        syncBreadcrumbs()

        if (cached == null) {
            loadDirectory(path)
        }
    }

    fun navigateUp() {
        val stack = _navigationStack.value
        if (stack.size <= 1) return

        _navigationStack.update { it.dropLast(1) }
        syncBreadcrumbs()
    }

    fun navigateToBreadcrumb(index: Int) {
        val stack = _navigationStack.value
        if (index < 0 || index >= stack.size) return
        if (index == stack.lastIndex) return

        _navigationStack.update { it.take(index + 1) }
        syncBreadcrumbs()
    }

    fun refresh() {
        val current = _navigationStack.value.lastOrNull() ?: return
        directoryCache.remove(current.path)
        loadDirectory(current.path)
    }

    fun selectServer(id: String) {
        val server = _extraState.value.activeServers.firstOrNull { it.id == id } ?: return
        val prevSelected = _extraState.value.selectedServer
        if (server == prevSelected) return
        _extraState.update { it.copy(selectedServer = server) }

        // 重置栈为根目录
        _navigationStack.value = listOf(WebDavDirectoryState(path = "/", label = "根目录"))
        syncBreadcrumbs()
        directoryCache.clear()
        loadDirectory("/")
    }

    /** 从 navigationStack 同步面包屑到 stateFlow */
    private fun syncBreadcrumbs() {
        val crumbs = _navigationStack.value.map {
            WebDavBreadcrumb(it.label, it.path)
        }
        _stateFlow.update { it.copy(breadcrumbs = crumbs) }
    }

    // endregion

    // region ==================== 文件操作 ====================

    fun createDirectory(name: String) {
        val server = _extraState.value.selectedServer ?: return
        val current = _navigationStack.value.lastOrNull() ?: return
        val parentPath = current.path.trimEnd('/')
        val newPath = "$parentPath/$name"

        viewModelScope.launch {
            val result = webDavRepository.createFolder(
                baseUrl = server.normalizedUrl,
                path = newPath,
                authHeader = server.basicAuthHeader,
            )
            result.fold(
                onSuccess = {
                    Toast.makeText(context, "文件夹创建成功", Toast.LENGTH_SHORT).show()
                    directoryCache.remove(current.path)
                    loadDirectory(current.path)
                },
                onFailure = { e ->
                    Toast.makeText(context, "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    fun renameItem(index: Int, newName: String) {
        val server = _extraState.value.selectedServer ?: return
        val current = _navigationStack.value.lastOrNull() ?: return
        val item = current.items.getOrNull(index) ?: return

        val parentPath = current.path.trimEnd('/')
        val destPath = "$parentPath/$newName"

        viewModelScope.launch {
            val result = webDavRepository.move(
                baseUrl = server.normalizedUrl,
                sourcePath = item.path,
                destinationPath = destPath,
                authHeader = server.basicAuthHeader,
            )
            result.fold(
                onSuccess = {
                    Toast.makeText(context, "重命名成功", Toast.LENGTH_SHORT).show()
                    directoryCache.remove(current.path)
                    loadDirectory(current.path)
                },
                onFailure = { e ->
                    Toast.makeText(context, "重命名失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    fun deleteItem(index: Int) {
        val server = _extraState.value.selectedServer ?: return
        val current = _navigationStack.value.lastOrNull() ?: return
        val item = current.items.getOrNull(index) ?: return

        viewModelScope.launch {
            val result = webDavRepository.delete(
                baseUrl = server.normalizedUrl,
                path = item.path,
                authHeader = server.basicAuthHeader,
            )
            result.fold(
                onSuccess = {
                    Toast.makeText(context, "删除成功", Toast.LENGTH_SHORT).show()
                    directoryCache.remove(current.path)
                    loadDirectory(current.path)
                },
                onFailure = { e ->
                    Toast.makeText(context, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    // endregion

    // region ==================== 下载 ====================

    fun downloadFile(index: Int) {
        val current = _navigationStack.value.lastOrNull() ?: return
        val item = current.items.getOrNull(index) ?: return
        if (item.isDirectory || item.isVideo) return

        val server = _extraState.value.selectedServer ?: return
        val baseUrl = server.normalizedUrl.trimEnd('/')
        val fullUrl = if (item.path.startsWith("/")) "$baseUrl${item.path}" else "$baseUrl/${item.path}"
        val originalUri = Uri.parse(fullUrl)
        val hostPort = originalUri.host +
            if (originalUri.port != -1) ":${originalUri.port}" else ""
        val authUri = originalUri.buildUpon()
            .encodedAuthority(
                Uri.encode(server.username) + ":" +
                    Uri.encode(server.password) + "@" + hostPort
            )
            .build()

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(authUri)
            .setTitle(item.name)
            .setDescription("正在下载...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, item.name)
        downloadManager.enqueue(request)
    }

    // endregion
}
