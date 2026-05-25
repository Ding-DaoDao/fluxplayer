package dev.anilbeesetti.nextplayer.feature.videopicker.openlist

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.data.openlist.OpenListApiClient
import dev.anilbeesetti.nextplayer.core.data.openlist.OpenListFileItem
import dev.anilbeesetti.nextplayer.core.data.openlist.OpenListManagerProvider
import dev.anilbeesetti.nextplayer.core.data.openlist.OpenListTokenProvider
import dev.anilbeesetti.nextplayer.core.data.openlist.OpenListServerState
import dev.anilbeesetti.nextplayer.core.data.repository.PlaybackHistoryRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.model.WebDavResource
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OpenListBrowserUiState(
    val items: List<WebDavResource> = emptyList(),
    val currentPath: String = "/",
    val breadcrumbs: List<Breadcrumb> = listOf(Breadcrumb("根目录", "/")),
    val isLoading: Boolean = false,
    val isConfigured: Boolean = false,
    val error: String? = null,
    val serverUrl: String = "http://127.0.0.1:5244",
    val playedUriStrings: Set<String> = emptySet(),
    val visitedDirPaths: Set<String> = emptySet(),
    val currentFootprint: String? = null,
)

data class Breadcrumb(val label: String, val path: String)

@dagger.hilt.android.lifecycle.HiltViewModel
class OpenListBrowserViewModel @Inject constructor(
    application: Application,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val preferencesRepository: PreferencesRepository,
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "OpenListBrowserVM"
    }

    private val manager = OpenListManagerProvider.get()
    private var apiClient: OpenListApiClient? = null
    private var currentAdminPassword: String? = null

    private val _uiState = MutableStateFlow(OpenListBrowserUiState())
    val uiState: StateFlow<OpenListBrowserUiState> = _uiState.asStateFlow()

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
            preferencesRepository.applicationPreferences.collect { prefs ->
                val dir = _uiState.value.currentPath
                _uiState.update {
                    it.copy(currentFootprint = prefs.latestFootprintPerDir[dir])
                }
            }
        }
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
            // 等待 OpenList 服务就绪
            if (manager == null) {
                _uiState.update { it.copy(error = "OpenListManager 未初始化") }
                return@launch
            }

            manager.state.collect { state ->
                if (state is OpenListServerState.Running) {
                    initialize()
                } else if (state is OpenListServerState.Error) {
                    _uiState.update { it.copy(error = "服务异常: ${state.message}") }
                }
            }
        }
    }

    private suspend fun initialize() {
        val pwd = manager?.adminSetPassword?.value ?: manager?.initialPassword?.value
        Log.d(TAG, "initialize() called, pwd exists=${pwd != null}")
        if (pwd == null) {
            _uiState.update { it.copy(error = "密码未就绪，请先在设置页配置 OpenList") }
            return
        }

        _uiState.update { it.copy(isConfigured = true, isLoading = true, error = null) }
        val client = OpenListApiClient()
        apiClient = client

        // 尝试登录获取 token（用于播放视频时的 Bearer 认证）
        val loginResult = client.adminLogin(pwd)
        if (loginResult.isSuccess) {
            Log.d(TAG, "adminLogin success")
            OpenListTokenProvider.bearerToken = client.getAdminToken()
        } else {
            Log.w(TAG, "adminLogin failed: ${loginResult.exceptionOrNull()?.message}")
        }
        // 保存密码供后续导航加载使用
        currentAdminPassword = pwd
        Log.d(TAG, "calling loadDirectory(/), currentAdminPassword=$currentAdminPassword")
        loadDirectory("/", pwd)
    }

    fun loadDirectory(path: String, adminPassword: String? = null) {
        val client = apiClient ?: run {
            Log.e(TAG, "loadDirectory: apiClient is null!")
            return
        }
        Log.d(TAG, "loadDirectory: path=$path, hasPassword=${adminPassword != null}")
        _uiState.update { it.copy(isLoading = true, error = null) }

        viewModelScope.launch {
            val result = client.listFiles(path, adminPassword)
            result.fold(
                onSuccess = { items ->
                    Log.d(TAG, "loadDirectory success: ${items.size} items for path=$path")
                    val freshPrefs = preferencesRepository.applicationPreferences.value
                    _uiState.update {
                        it.copy(
                            items = items.map { file -> file.toWebDavResource() },
                            currentPath = path,
                            isLoading = false,
                            currentFootprint = freshPrefs.latestFootprintPerDir[path],
                        )
                    }
                },
                onFailure = { e ->
                    Log.e(TAG, "loadDirectory failed for path=$path: ${e.message}")
                    val friendlyMsg = e.message?.let { msg ->
                        when {
                            msg.contains("storage not found") || msg.contains("add a storage") ->
                                "OpenList 中没有挂载任何存储，请在 OpenList 网页后台添加存储"
                            msg.contains("Failed to connect") ->
                                "无法连接到 OpenList 服务（127.0.0.1:5244），请确认服务已启动"
                            msg.contains("401") || msg.contains("token is invalidated") ->
                                "认证失败，请检查 OpenList 管理员密码"
                            else -> null
                        }
                    }
                    _uiState.update {
                        it.copy(isLoading = false, error = friendlyMsg ?: "加载失败: ${e.message}")
                    }
                },
            )
        }
    }

    fun navigateToDir(index: Int) {
        val item = _uiState.value.items.getOrNull(index)
        if (item == null) {
            Log.w(TAG, "navigateToDir($index): item not found (index out of range)")
            return
        }
        if (!item.isDirectory) {
            Log.d(TAG, "navigateToDir($index): not a directory (${item.name})")
            return
        }

        val label = item.name
        val path = item.path
        Log.d(TAG, "navigateToDir($index): name=$label, path=$path")

        recordFootprint(path)

        _uiState.update { state ->
            state.copy(
                breadcrumbs = state.breadcrumbs + Breadcrumb(label, path),
                items = emptyList(),
                visitedDirPaths = state.visitedDirPaths + path,
            )
        }
        loadDirectory(path, currentAdminPassword)
    }

    fun navigateUp() {
        val breadcrumbs = _uiState.value.breadcrumbs
        if (breadcrumbs.size <= 1) return

        val newBreadcrumbs = breadcrumbs.dropLast(1)
        val parentPath = newBreadcrumbs.last().path
        _uiState.update { it.copy(breadcrumbs = newBreadcrumbs, items = emptyList()) }
        loadDirectory(parentPath, currentAdminPassword)
    }

    fun navigateToBreadcrumb(index: Int) {
        val breadcrumbs = _uiState.value.breadcrumbs
        if (index < 0 || index >= breadcrumbs.size) return
        if (index == breadcrumbs.lastIndex) return

        val newBreadcrumbs = breadcrumbs.take(index + 1)
        val path = newBreadcrumbs.last().path
        _uiState.update { it.copy(breadcrumbs = newBreadcrumbs, items = emptyList()) }
        loadDirectory(path, currentAdminPassword)
    }

    fun refresh() {
        if (_uiState.value.error != null) {
            // 出错时重新初始化整个连接
            Log.d(TAG, "refresh triggered with error, re-initializing...")
            viewModelScope.launch {
                initialize()
            }
        } else {
            loadDirectory(_uiState.value.currentPath, currentAdminPassword)
        }
    }

    fun downloadFile(index: Int) {
        val item = _uiState.value.items.getOrNull(index) ?: return
        if (item.isDirectory || item.isVideo) return

        val url = getPlayUri(item).toString()
        val token = getBearerToken()

        Log.d(TAG, "downloadFile: ${item.name}, url=$url")

        val downloadManager = getApplication<Application>()
            .getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(item.name)
            .setDescription("正在下载...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, item.name)
        if (token != null) {
            request.addRequestHeader("Authorization", "Bearer $token")
        }
        downloadManager.enqueue(request)
    }

    /**
     * 获取可播放的视频 URI（包含 Bearer token 的完整路径）。
     */
    fun getPlayUri(resource: WebDavResource): Uri {
        val baseUrl = _uiState.value.serverUrl
        // 从 resource.path 提取相对路径
        val relativePath = if (resource.path.startsWith(baseUrl)) {
            resource.path.removePrefix(baseUrl)
        } else {
            resource.path
        }
        return Uri.parse("$baseUrl/d$relativePath")
    }

    /**
     * 获取当前的 Bearer token（供 Player auth 注入用）。
     */
    fun getBearerToken(): String? = apiClient?.getAdminToken()
}

private fun OpenListFileItem.toWebDavResource(): WebDavResource {
    return WebDavResource(
        name = name,
        path = path,
        isDirectory = isDirectory,
        size = size,
        lastModified = modified,
    )
}
