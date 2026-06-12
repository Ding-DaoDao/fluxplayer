package com.fluxplayer.app.feature.videopicker.screens.webdav

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
import com.fluxplayer.app.core.common.CloudPlaylistCache
import com.fluxplayer.app.core.data.repository.PlaybackHistoryRepository
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.data.repository.WebDavRepository
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.core.model.WebDavServer
import com.fluxplayer.app.feature.videopicker.CommonStateSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
) : com.fluxplayer.app.feature.videopicker.DirectoryState {
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
        // 观察播放历史 + 当前选中服务器，两者任一变化时重新计算 playedUriStrings
        viewModelScope.launch {
            combine(
                playbackHistoryRepository.getHistoryFlow(),
                _extraState.map { it.selectedServer }.distinctUntilChanged(),
            ) { history, server ->
                Pair(history, server)
            }.collect { (history, server) ->
                val basePath = server?.let {
                    Uri.parse(it.normalizedUrl).path?.trimEnd('/')
                }
                // 按 parentPath 分组，每组只保留最新一条
                val latestHistory = history
                    .filter { it.parentPath != null }
                    .groupBy { it.parentPath!! }
                    .mapValues { (_, list) -> list.maxByOrNull { it.lastPlayedTime }!! }
                    .values.toList()
                val historyUris = latestHistory.flatMap { item ->
                    val uri = item.uriString
                    val parts = mutableListOf(uri)
                    if (uri.startsWith("http://") || uri.startsWith("https://")) {
                        try {
                            val parsed = Uri.parse(uri)
                            val urlPath = parsed.path
                            if (!urlPath.isNullOrEmpty()) {
                                parts.add(urlPath)
                                if (basePath != null && !basePath.isNullOrEmpty() && urlPath.startsWith(basePath)) {
                                    val relative = urlPath.removePrefix(basePath)
                                    if (relative.isNotEmpty()) {
                                        parts.add(relative)
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    parts
                }.toSet()
                Log.d(TAG, "[Footprint] historySize=${history.size} latestPerDirSize=${latestHistory.size} playedUriSetSize=${historyUris.size}")
                val sample = historyUris.filter { it.startsWith("/") }.take(5).joinToString("|")
                Log.d(TAG, "[Footprint] sample paths: $sample")
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

        // 归一化 path：如果 path 包含 server baseUrl 的路径前缀（如 /webdav），则剥离，
        // 避免 WebDavClient 拼接时出现双重前缀（baseUrl + path = /webdav/webdav/...）
        val normalizedPath: String
        val basePath = Uri.parse(server.normalizedUrl).path?.trimEnd('/')
        normalizedPath = if (basePath.isNullOrEmpty()) {
            path
        } else if (path.startsWith("$basePath/")) {
            path.removePrefix(basePath).let { if (it.isEmpty()) "/" else it }
        } else if (path == basePath) {
            "/"
        } else {
            path
        }

        // 标记当前栈顶为 loading
        updateStackTop(path) { it.copy(isLoading = true, error = null) }

        val loadingPath = normalizedPath

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
                    val sorted = sortItems(filtered, _stateFlow.value.orderBy, _stateFlow.value.orderDirection)
                    directoryCache[loadingPath] = sorted

                    // 将视频文件元数据写入 CloudPlaylistCache（供播放历史跳转使用）
                    val breadcrumbPath = _navigationStack.value.joinToString("/") { "${it.label}|${it.path}" }
                    sorted.filter { it.isVideo }.forEach { file ->
                        CloudPlaylistCache.putFileMetadata(
                            PROVIDER_LABEL, file.path,
                            CloudPlaylistCache.FileMetadata(
                                fileName = file.name,
                                parentPath = "${loadingPath}|$breadcrumbPath",
                            )
                        )
                    }

                    Log.d(TAG, "loadDirectory: cached path=$loadingPath, items=${filtered.size}")
                    updateStackTop(loadingPath) {
                        it.copy(items = sorted, isLoading = false, error = null)
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
        ensureTopLoaded()
    }

    fun navigateToBreadcrumb(index: Int) {
        val stack = _navigationStack.value
        if (index < 0 || index >= stack.size) return
        if (index == stack.lastIndex) return

        _navigationStack.update { it.take(index + 1) }
        syncBreadcrumbs()
        ensureTopLoaded()
    }

    /** 确保当前栈顶有数据：有缓存则恢复，无则触发网络加载 */
    private fun ensureTopLoaded() {
        val top = _navigationStack.value.lastOrNull() ?: return
        if (top.items.isNotEmpty() || top.isLoading) return
        val cached = directoryCache[top.path]
        if (cached != null) {
            val sorted = sortItems(cached, _stateFlow.value.orderBy, _stateFlow.value.orderDirection)
            updateStackTop(top.path) { it.copy(items = sorted) }
        } else {
            loadDirectory(top.path)
        }
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

    /**
     * 从播放历史跳转到指定目录。
     * 直接从 dirPath 路径段重建面包屑层次，不再解析 label 字符串。
     * @param dirPath 目录路径（如 /Movies/2024，或含 basePath 的 /webdav/Movies/2024）
     * @param label   未使用（保留以兼容调用方）
     */
    fun jumpToFolder(dirPath: String, @Suppress("UNUSED_PARAMETER") label: String) {
        val server = _extraState.value.selectedServer

        // 归一化 dirPath：剥离 server baseUrl 的路径前缀
        val normalizedPath: String
        if (server != null) {
            val basePath = Uri.parse(server.normalizedUrl).path?.trimEnd('/')
            normalizedPath = if (basePath.isNullOrEmpty()) {
                dirPath
            } else if (dirPath.startsWith("$basePath/")) {
                dirPath.removePrefix(basePath).let { if (it.isEmpty()) "/" else it }
            } else if (dirPath == basePath) {
                "/"
            } else {
                dirPath
            }
        } else {
            normalizedPath = dirPath
        }

        val crumbs = mutableListOf<WebDavBreadcrumb>()
        crumbs.add(WebDavBreadcrumb("根目录", "/"))

        if (normalizedPath != "/") {
            val segments = normalizedPath.trimEnd('/').split("/").filter { it.isNotEmpty() }
            var accumulatedPath = ""
            for (seg in segments) {
                accumulatedPath += "/$seg"
                crumbs.add(WebDavBreadcrumb(seg, accumulatedPath))
            }
        }

        _navigationStack.value = crumbs.map { crumb ->
            val cachedItems = directoryCache[crumb.path]
            WebDavDirectoryState(
                path = crumb.path,
                label = crumb.label,
                items = cachedItems ?: emptyList(),
            )
        }
        syncBreadcrumbs()
        directoryCache.remove(normalizedPath)
        loadDirectory(normalizedPath)
    }

    // endregion

    // region ==================== 排序 ====================

    fun setSort(orderBy: String, orderDirection: String = "ASC") {
        _stateFlow.update { it.copy(orderBy = orderBy, orderDirection = orderDirection) }
        // 对当前栈顶数据重新排序
        val current = _navigationStack.value.lastOrNull() ?: return
        if (current.items.isNotEmpty()) {
            val sorted = sortItems(current.items, orderBy, orderDirection)
            updateStackTop(current.path) { it.copy(items = sorted) }
        }
    }

    private fun sortItems(items: List<WebDavResource>, orderBy: String, orderDirection: String): List<WebDavResource> {
        val comparator: Comparator<WebDavResource> = when (orderBy) {
            "name" -> compareBy { it.name.lowercase() }
            "size" -> compareBy { it.size }
            "modified" -> compareBy { it.lastModified }
            else -> compareBy { it.name.lowercase() }
        }
        val finalComparator = if (orderDirection.equals("DESC", ignoreCase = true)) comparator.reversed() else comparator
        return items.sortedWith(compareByDescending<WebDavResource> { it.isDirectory }.then(finalComparator))
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
