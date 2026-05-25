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
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WebDavBrowserUiState(
    val items: List<WebDavResource> = emptyList(),
    val currentPath: String = "/",
    val breadcrumbs: List<Breadcrumb> = listOf(Breadcrumb("根目录", "/")),
    val isLoading: Boolean = false,
    val error: String? = null,
    val servers: List<WebDavServer> = emptyList(),
    val activeServers: List<WebDavServer> = emptyList(),
    val selectedServer: WebDavServer? = null,
    val isConfigured: Boolean = false,
    val playedUriStrings: Set<String> = emptySet(),
    val visitedDirPaths: Set<String> = emptySet(),
    val currentFootprint: String? = null,
)

data class Breadcrumb(
    val label: String,
    val path: String,
)

@HiltViewModel
class WebDavBrowserViewModel @Inject constructor(
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val preferencesRepository: PreferencesRepository,
    private val webDavRepository: WebDavRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WebDavBrowserUiState())
    val uiState: StateFlow<WebDavBrowserUiState> = _uiState.asStateFlow()

    /** 记录足迹 — 同目录只保留最新一个 */
    fun recordFootprint(itemPath: String) {
        val dir = _uiState.value.currentPath
        // 立即更新 UI 状态，不依赖异步 preferences 流
        _uiState.update { it.copy(currentFootprint = itemPath) }
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { prefs ->
                prefs.copy(latestFootprintPerDir = prefs.latestFootprintPerDir + (dir to itemPath))
            }
        }
    }

    init {
        viewModelScope.launch {
            playbackHistoryRepository.getHistoryFlow().collect { history ->
                val historyUris = history.map { it.uriString }.toSet()
                _uiState.update {
                    it.copy(playedUriStrings = historyUris)
                }
                // 清除已删除历史对应的足迹（仅清理视频类 URI）
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
                val dir = _uiState.value.currentPath
                _uiState.update {
                    it.copy(currentFootprint = prefs.latestFootprintPerDir[dir])
                }
            }
        }
        viewModelScope.launch {
            webDavRepository.servers.collect { servers ->
                _uiState.update { it.copy(servers = servers) }
            }
        }
        viewModelScope.launch {
            webDavRepository.activeServers.collect { actives ->
                val prevSelected = _uiState.value.selectedServer
                _uiState.update {
                    it.copy(
                        activeServers = actives,
                        selectedServer = prevSelected?.takeIf { p -> actives.any { a -> a.id == p.id } }
                            ?: actives.firstOrNull(),
                        isConfigured = actives.isNotEmpty(),
                    )
                }
                // 如果选中服务器变化，重新加载
                val newSelected = _uiState.value.selectedServer
                if (newSelected != null && newSelected != prevSelected) {
                    loadDirectory("/")
                }
            }
        }
    }

    fun loadDirectory(path: String) {
        val server = _uiState.value.selectedServer ?: return

        _uiState.update { it.copy(isLoading = true, error = null) }
        val loadingPath = path // 记录本次请求的目标路径

        viewModelScope.launch {
            val result = webDavRepository.listDirectory(
                baseUrl = server.normalizedUrl,
                path = loadingPath,
                authHeader = server.basicAuthHeader,
            )

            // 如果用户在此期间已跳转到其他目录，忽略过时结果
            if (_uiState.value.currentPath != loadingPath) return@launch

            result.fold(
                onSuccess = { resources ->
                    val filtered = resources.filter { res ->
                        !res.name.startsWith(".") && res.name.isNotBlank()
                    }
                    val freshPrefs = preferencesRepository.applicationPreferences.value
                    _uiState.update {
                        it.copy(
                            items = filtered,
                            currentPath = loadingPath,
                            currentFootprint = freshPrefs.latestFootprintPerDir[loadingPath],
                            isLoading = false,
                            error = null,
                        )
                    }
                },
                onFailure = { error ->
                    // 失败时同样检查路径是否已变
                    if (_uiState.value.currentPath != loadingPath) return@launch
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "加载失败",
                        )
                    }
                },
            )
        }
    }

    fun navigateToDir(index: Int) {
        val item = _uiState.value.items.getOrNull(index) ?: return
        if (!item.isDirectory) return

        val path = item.path
        val label = item.name

        recordFootprint(path)

        _uiState.update { state ->
            state.copy(
                currentPath = path,
                breadcrumbs = state.breadcrumbs + Breadcrumb(label, path),
                items = emptyList(),
                visitedDirPaths = state.visitedDirPaths + path,
            )
        }
        loadDirectory(path)
    }

    fun navigateUp() {
        val breadcrumbs = _uiState.value.breadcrumbs
        if (breadcrumbs.size <= 1) return

        val newBreadcrumbs = breadcrumbs.dropLast(1)
        val parentPath = newBreadcrumbs.last().path

        _uiState.update {
            it.copy(
                currentPath = parentPath,
                breadcrumbs = newBreadcrumbs,
                items = emptyList(),
            )
        }
        loadDirectory(parentPath)
    }

    fun navigateToBreadcrumb(index: Int) {
        val breadcrumbs = _uiState.value.breadcrumbs
        if (index < 0 || index >= breadcrumbs.size) return
        if (index == breadcrumbs.lastIndex) return

        val newBreadcrumbs = breadcrumbs.take(index + 1)
        val path = newBreadcrumbs.last().path

        _uiState.update { it.copy(breadcrumbs = newBreadcrumbs, items = emptyList()) }
        loadDirectory(path)
    }

    fun refresh() {
        loadDirectory(_uiState.value.currentPath)
    }

    fun selectServer(id: String) {
        val server = _uiState.value.activeServers.firstOrNull { it.id == id } ?: return
        val prevSelected = _uiState.value.selectedServer
        if (server == prevSelected) return
        _uiState.update {
            it.copy(
                selectedServer = server,
                currentPath = "/",
                breadcrumbs = listOf(Breadcrumb("根目录", "/")),
                items = emptyList(),
            )
        }
        loadDirectory("/")
    }

    fun downloadFile(index: Int) {
        val item = _uiState.value.items.getOrNull(index) ?: return
        if (item.isDirectory || item.isVideo) return

        val server = _uiState.value.selectedServer ?: return
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

    private var _dirCache = mutableMapOf<String, List<WebDavResource>>()
        get() = field
        set(value) { field = value }
}
