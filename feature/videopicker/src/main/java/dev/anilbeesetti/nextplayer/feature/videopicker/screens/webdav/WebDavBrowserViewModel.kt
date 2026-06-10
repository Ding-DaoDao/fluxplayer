package dev.anilbeesetti.nextplayer.feature.videopicker.screens.webdav

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
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
import dev.anilbeesetti.nextplayer.feature.videopicker.CommonStateUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WebDavBreadcrumb(val label: String, val path: String)

/**
 * WebDAV 浏览器 ViewModel
 *
 * 注意：WebDAV 不继承 BaseCloudBrowserViewModel，因为它使用普通 ViewModel（不需要 Application），
 * 且需要处理多服务器选择、Basic Auth 等特殊逻辑。
 * 但状态管理采用相同的 CommonStateSnapshot/CommonStateUpdate 模式。
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

    private val _stateFlow = MutableStateFlow(
        CommonStateSnapshot(
            breadcrumbs = listOf(WebDavBreadcrumb("根目录", "/")),
            orderBy = "name",
            orderDirection = "ASC"
        )
    )
    val stateFlow: StateFlow<CommonStateSnapshot<WebDavBreadcrumb>> = _stateFlow.asStateFlow()

    /** @deprecated 使用 [stateFlow] 代替 */
    val state: CommonStateSnapshot<WebDavBreadcrumb>
        get() = _stateFlow.value

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

    // endregion

    // region ==================== 初始化 ====================

    init {
        viewModelScope.launch {
            playbackHistoryRepository.getHistoryFlow().collect { history ->
                val historyUris = history.map { it.uriString }.toSet()
                _extraState.update { it.copy(playedUriStrings = historyUris) }
                preferencesRepository.updateApplicationPreferences { prefs ->
                    val cleaned = prefs.latestFootprintPerDir.filterValues { value ->
                        !value.startsWith("http") || value in historyUris
                    }
                    if (cleaned.size == prefs.latestFootprintPerDir.size) prefs
                    else prefs.copy(latestFootprintPerDir = cleaned)
                }
            }
        }
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect { prefs ->
                val dir = _stateFlow.value.currentFileId
                updateState(CommonStateUpdate(currentFootprint = prefs.latestFootprintPerDir[dir]))
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

    // region ==================== 状态管理 ====================

    private fun updateState(update: CommonStateUpdate<WebDavBreadcrumb>) {
        val current = _stateFlow.value
        _stateFlow.value = current.copy(
            items = update.items ?: current.items,
            currentFileId = update.currentFileId ?: current.currentFileId,
            breadcrumbs = update.breadcrumbs ?: current.breadcrumbs,
            isLoading = update.isLoading ?: current.isLoading,
            isLoadingMore = update.isLoadingMore ?: current.isLoadingMore,
            hasMore = update.hasMore ?: current.hasMore,
            currentPage = update.currentPage ?: current.currentPage,
            isLoggedIn = update.isLoggedIn ?: current.isLoggedIn,
            error = update.error ?: current.error,
            orderBy = update.orderBy ?: current.orderBy,
            orderDirection = update.orderDirection ?: current.orderDirection,
            currentFootprint = update.currentFootprint ?: current.currentFootprint,
            scrollTargetIndex = update.scrollTargetIndex ?: current.scrollTargetIndex,
            scrollTargetParentKey = update.scrollTargetParentKey ?: current.scrollTargetParentKey,
            pendingAction = update.pendingAction ?: current.pendingAction,
            moveFileId = update.moveFileId ?: current.moveFileId,
            copyFileId = update.copyFileId ?: current.copyFileId,
            pickerFolders = update.pickerFolders ?: current.pickerFolders,
            pickerIsLoading = update.pickerIsLoading ?: current.pickerIsLoading
        )
    }

    // endregion

    // region ==================== 目录加载 ====================

    fun loadDirectory(path: String) {
        val server = _extraState.value.selectedServer ?: return

        updateState(CommonStateUpdate(isLoading = true, error = null, currentFileId = path))
        val loadingPath = path

        viewModelScope.launch {
            val result = webDavRepository.listDirectory(
                baseUrl = server.normalizedUrl,
                path = loadingPath,
                authHeader = server.basicAuthHeader,
            )

            if (_stateFlow.value.currentFileId != loadingPath) return@launch

            result.fold(
                onSuccess = { resources ->
                    val filtered = resources.filter { res ->
                        !res.name.startsWith(".") && res.name.isNotBlank()
                    }
                    val freshPrefs = preferencesRepository.applicationPreferences.value
                    updateState(
                        CommonStateUpdate(
                            items = filtered,
                            currentFileId = loadingPath,
                            currentFootprint = freshPrefs.latestFootprintPerDir[loadingPath],
                            isLoading = false,
                            error = null
                        )
                    )
                },
                onFailure = { error ->
                    if (_stateFlow.value.currentFileId != loadingPath) return@launch
                    updateState(
                        CommonStateUpdate(
                            isLoading = false,
                            error = error.message ?: "加载失败"
                        )
                    )
                }
            )
        }
    }

    // endregion

    // region ==================== 导航 ====================

    fun navigateToDir(index: Int) {
        val s = _stateFlow.value
        val item = s.items.getOrNull(index) ?: return
        if (!item.isDirectory) return

        val path = item.path
        val label = item.name
        recordFootprint(path)

        updateState(
            CommonStateUpdate(
                currentFileId = path,
                breadcrumbs = s.breadcrumbs + WebDavBreadcrumb(label, path),
                items = emptyList()
            )
        )
        loadDirectory(path)
    }

    fun navigateUp() {
        val breadcrumbs = _stateFlow.value.breadcrumbs
        if (breadcrumbs.size <= 1) return

        val newBreadcrumbs = breadcrumbs.dropLast(1)
        val parentPath = newBreadcrumbs.last().path
        updateState(
            CommonStateUpdate(
                currentFileId = parentPath,
                breadcrumbs = newBreadcrumbs,
                items = emptyList()
            )
        )
        loadDirectory(parentPath)
    }

    fun navigateToBreadcrumb(index: Int) {
        val breadcrumbs = _stateFlow.value.breadcrumbs
        if (index < 0 || index >= breadcrumbs.size) return
        if (index == breadcrumbs.lastIndex) return

        val newBreadcrumbs = breadcrumbs.take(index + 1)
        val path = newBreadcrumbs.last().path
        updateState(
            CommonStateUpdate(
                currentFileId = path,
                breadcrumbs = newBreadcrumbs,
                items = emptyList()
            )
        )
        loadDirectory(path)
    }

    fun refresh() {
        loadDirectory(_stateFlow.value.currentFileId)
    }

    fun selectServer(id: String) {
        val server = _extraState.value.activeServers.firstOrNull { it.id == id } ?: return
        val prevSelected = _extraState.value.selectedServer
        if (server == prevSelected) return
        _extraState.update {
            it.copy(selectedServer = server)
        }
        updateState(
            CommonStateUpdate(
                currentFileId = "/",
                breadcrumbs = listOf(WebDavBreadcrumb("根目录", "/")),
                items = emptyList()
            )
        )
        loadDirectory("/")
    }

    // endregion

    // region ==================== 足迹 ====================

    fun recordFootprint(path: String) {
        val dir = _stateFlow.value.currentFileId
        updateState(CommonStateUpdate(currentFootprint = path))
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { prefs ->
                prefs.copy(latestFootprintPerDir = prefs.latestFootprintPerDir + (dir to path))
            }
        }
    }

    // endregion

    // region ==================== 下载 ====================

    fun downloadFile(index: Int) {
        val item = _stateFlow.value.items.getOrNull(index) ?: return
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

    // region ==================== 面包屑标签（供 UI 使用） ====================

    fun breadcrumbLabel(crumb: WebDavBreadcrumb): String = crumb.label

    // endregion
}
