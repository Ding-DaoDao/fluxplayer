package dev.anilbeesetti.nextplayer.feature.videopicker.yun139

import android.app.Application
import android.content.Context
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.anilbeesetti.nextplayer.core.common.CloudPlaylistCache
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.data.yun139.Yun139ApiClient
import dev.anilbeesetti.nextplayer.core.data.yun139.Yun139AuthProvider
import dev.anilbeesetti.nextplayer.core.data.yun139.Yun139FileItem
import dev.anilbeesetti.nextplayer.core.model.WebDavResource
import kotlinx.coroutines.delay
import dev.anilbeesetti.nextplayer.feature.videopicker.DirectoryStackEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class Yun139Breadcrumb(val label: String, val fileId: String)

@HiltViewModel
class Yun139BrowserViewModel @Inject constructor(
    application: Application,
    private val preferencesRepository: PreferencesRepository
) : androidx.lifecycle.AndroidViewModel(application) {

    companion object {
        private const val TAG = "Yun139BrowserVM"
        private const val PREF_NAME = "yun139"
    }

    // region ==================== API Client ====================

    val apiClient = Yun139ApiClient()

    // endregion

    // region ==================== 状态 ====================

    private val _uiState = MutableStateFlow(Yun139BrowserUiState())
    val uiState: StateFlow<Yun139BrowserUiState> = _uiState.asStateFlow()

    private val _navigationStack = MutableStateFlow(
        listOf(DirectoryStackEntry(fileId = "/", label = "根目录"))
    )
    val navigationStack: StateFlow<List<DirectoryStackEntry>> = _navigationStack.asStateFlow()

    private fun syncStackTop(transform: (DirectoryStackEntry) -> DirectoryStackEntry) {
        _navigationStack.update { stack ->
            if (stack.isEmpty()) return@update stack
            stack.toMutableList().apply { set(lastIndex, transform(get(lastIndex))) }
        }
    }

    private var loadSequence: Int = 0
    private var loadDirectoryJob: kotlinx.coroutines.Job? = null
    private var loadingMore: Boolean = false
    private val directoryCache = mutableMapOf<String, List<WebDavResource>>()

    // endregion

    // region ==================== 登录 — autoLogin ====================

    init {
        try {
            autoLogin()
        } catch (e: Exception) {
            Log.e(TAG, "autoLogin failed in init", e)
        }
    }

    private fun autoLogin() {
        val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val authorization = prefs.getString("authorization", "") ?: ""
        val phoneNumber = prefs.getString("phoneNumber", "") ?: ""
        val userDomainId = prefs.getString("userDomainId", "") ?: ""
        val lastRefresh = prefs.getLong("lastRefresh", 0L)

        if (authorization.isBlank()) return

        viewModelScope.launch {
            var valid = false
            for (attempt in 1..4) {
                try {
                    apiClient.setToken(authorization, phoneNumber, userDomainId)
                    val result = apiClient.listFiles("/", 1, 10)
                    if (result.isSuccess) { valid = true; break }
                } catch (_: Exception) {}
                if (attempt < 4) delay(1500)
            }

            if (valid) {
                updateUiState { it.copy(isLoggedIn = true) }
                loadDirectory("/")

                // 超过7天自动刷新token
                val sevenDays = 7L * 24 * 60 * 60 * 1000
                if (System.currentTimeMillis() - lastRefresh >= sevenDays) {
                    try {
                        val refreshResult = apiClient.refreshToken()
                        if (refreshResult.isSuccess) {
                            prefs.edit()
                                .putString("authorization", Yun139AuthProvider.authorization)
                                .putLong("lastRefresh", System.currentTimeMillis())
                                .apply()
                            Log.d(TAG, "token auto-refreshed")
                        }
                    } catch (_: Exception) {}
                }
            } else {
                Log.w(TAG, "autoLogin token verification failed, keeping credentials")
                updateUiState { it.copy(error = "登录已过期，请重新登录") }
            }
        }
    }

    // endregion

    // region ==================== 登录 — loginWithWeb ====================

    fun loginWithWeb(authorization: String, userDomainId: String) {
        try {
            // 解码 cookie 中的 authorization 并重新编码为 mobile:phone:authToken 格式
            val base64Part = authorization.removePrefix("Basic ").trim()
            val decoded = String(Base64.decode(base64Part, Base64.DEFAULT), Charsets.UTF_8)
            val phoneNumber = Regex("\\d{11}").find(decoded)?.value ?: ""
            val authToken = decoded.split(":").getOrNull(2) ?: decoded.split(":").last()
            val tokenValue = "Basic " + Base64.encodeToString(
                "mobile:$phoneNumber:$authToken".toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )

            apiClient.setToken(tokenValue, phoneNumber, userDomainId)

            val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putString("authorization", tokenValue)
                .putString("phoneNumber", phoneNumber)
                .putString("userDomainId", userDomainId)
                .putLong("lastRefresh", System.currentTimeMillis())
                .apply()

            updateUiState { it.copy(isLoggedIn = true) }
            loadDirectory("/")
        } catch (e: Exception) {
            Log.e(TAG, "loginWithWeb failed", e)
            updateUiState { it.copy(error = "登录信息解析失败: ${e.message}") }
        }
    }

    // endregion

    // region ==================== 登出 ====================

    fun logout() {
        apiClient.logout()
        directoryCache.clear()
        val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        // 清除 WebView 痕迹
        try {
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
            android.webkit.WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {}
        _uiState.value = Yun139BrowserUiState()
        _navigationStack.value = listOf(DirectoryStackEntry(fileId = "/", label = "根目录"))
    }

    // endregion

    // region ==================== 目录加载 ====================

    fun loadDirectory(parentFileId: String) {
        loadDirectoryJob?.cancel()
        loadSequence++
        val seq = loadSequence
        updateUiState { it.copy(isLoading = true, error = null, currentFolderId = parentFileId) }

        loadDirectoryJob = viewModelScope.launch {
            val result = apiClient.listFiles(parentFileId)
            if (seq != loadSequence) return@launch

            result.fold(
                onSuccess = { listResult ->
                    // 缓存文件元数据到 CloudPlaylistCache，供播放器显示标题
                    listResult.items.forEach { file ->
                        if (file.isVideo) {
                            CloudPlaylistCache.putFileMetadata(
                                "yun139", file.fileId,
                                CloudPlaylistCache.FileMetadata(fileName = file.fileName)
                            )
                        }
                    }
                    val resources = listResult.items.map { fileToResource(it) }
                    directoryCache[parentFileId] = resources
                    updateUiState {
                        it.copy(
                            items = resources,
                            isLoading = false, hasMore = listResult.items.size >= 100,
                            nextPageCursor = listResult.nextMarker.ifEmpty { null }
                        )
                    }
                    syncStackTop { it.copy(items = resources, isLoading = false, error = null) }
                },
                onFailure = { e ->
                    Log.e(TAG, "loadDirectory failed for $parentFileId", e)
                    val msg = e.message ?: "未知错误"
                    val friendly = when {
                        msg.contains("<!DOCTYPE", ignoreCase = true) ||
                        msg.contains("cannot be converted to JSONObject", ignoreCase = true) ->
                            "认证失败，请使用正确的 Authorization Token 登录"
                        msg.contains("require login", ignoreCase = true) ||
                        msg.contains("token", ignoreCase = true) ||
                        msg.contains("401") -> "登录已过期，请重新登录"
                        msg.contains("timeout", ignoreCase = true) ||
                        msg.contains("Timeout", ignoreCase = true) ||
                        msg.contains("timed out", ignoreCase = true) ->
                            "请求超时，请重试"
                        else -> "加载失败: ${msg.take(100)}"
                    }
                    updateUiState { it.copy(error = friendly, isLoading = false) }
                    syncStackTop { it.copy(error = friendly, isLoading = false) }
                }
            )
        }
    }

    // endregion

    // region ==================== 缓存加载 ====================

    private fun loadDirectoryCached(fileId: String) {
        val cached = directoryCache[fileId]
        if (cached != null) {
            updateUiState {
                it.copy(
                    items = cached,
                    currentFolderId = fileId,
                    isLoading = false,
                    error = null
                )
            }
            syncStackTop { it.copy(items = cached, isLoading = false, error = null) }
        } else {
            updateUiState { it.copy(items = emptyList(), currentFolderId = fileId) }
            syncStackTop { it.copy(items = emptyList(), isLoading = true, error = null) }
            loadDirectory(fileId)
        }
    }

    // endregion

    // region ==================== 加载更多 ====================

    fun loadMore() {
        val state = _uiState.value
        if (!state.hasMore || loadingMore || state.isLoading) return
        val nextPage = state.items.size / 100 + 2
        loadingMore = true
        updateUiState { it.copy(isLoadingMore = true) }

        viewModelScope.launch {
            val result = apiClient.listFiles(state.currentFolderId, nextPage, 100)
            result.fold(
                onSuccess = { listResult ->
                    val newItems = listResult.items.map { fileToResource(it) }
                    val existingPaths = state.items.map { it.path }.toSet()
                    val filtered = newItems.filter { it.path !in existingPaths }
                    updateUiState {
                        it.copy(
                            items = state.items + filtered, isLoadingMore = false,
                            hasMore = listResult.items.size >= 100,
                            nextPageCursor = listResult.nextMarker.ifEmpty { null }
                        )
                    }
                    loadingMore = false
                },
                onFailure = { e ->
                    updateUiState { it.copy(isLoadingMore = false, error = "加载更多失败: ${e.message}") }
                    loadingMore = false
                }
            )
        }
    }

    // endregion

    // region ==================== 导航 ====================

    fun navigateToDir(index: Int) {
        val state = _uiState.value
        val item = state.items.getOrNull(index) ?: return
        if (!item.isDirectory) return
        val parentKey = state.breadcrumbs.joinToString("/") { it.label }
        updateUiState {
            it.copy(
                breadcrumbs = state.breadcrumbs + Yun139Breadcrumb(item.name, item.path),
                scrollTargetIndex = index, scrollTargetParentKey = parentKey
            )
        }
        _navigationStack.update { it + DirectoryStackEntry(fileId = item.path, label = item.name) }
        loadDirectoryCached(item.path)
    }

    fun clearScrollTarget() { updateUiState { it.copy(scrollTargetIndex = -1) } }

    fun navigateUp() {
        val breadcrumbs = _uiState.value.breadcrumbs
        if (breadcrumbs.size <= 1) return
        val target = breadcrumbs[breadcrumbs.size - 2]
        updateUiState { it.copy(breadcrumbs = breadcrumbs.dropLast(1)) }
        _navigationStack.update { if (it.size > 1) it.dropLast(1) else it }
        loadDirectoryCached(target.fileId)
    }

    fun navigateToBreadcrumb(index: Int) {
        val breadcrumbs = _uiState.value.breadcrumbs
        if (index >= breadcrumbs.size) return
        val target = breadcrumbs[index]
        if (target.fileId.isEmpty()) return
        updateUiState { it.copy(breadcrumbs = breadcrumbs.subList(0, index + 1)) }
        _navigationStack.update { it.take(index + 1) }
        loadDirectoryCached(target.fileId)
    }

    // endregion

    // region ==================== 排序 ====================

    fun setSort(orderBy: String, orderDirection: String = "ASC") {
        updateUiState { it.copy(orderBy = orderBy, orderDirection = orderDirection) }
        loadDirectory(_uiState.value.currentFolderId)
    }

    // endregion

    // region ==================== 刷新 ====================

    fun refresh() { loadDirectory(_uiState.value.currentFolderId) }

    // endregion

    // region ==================== CRUD ====================

    fun createDirectory(name: String) {
        viewModelScope.launch {
            val state = _uiState.value
            updateUiState { it.copy(isLoading = true) }
            val result = apiClient.createFolder(state.currentFolderId, name)
            result.fold(
                onSuccess = {
                    Toast.makeText(getApplication(), "文件夹创建成功", Toast.LENGTH_SHORT).show()
                    refresh()
                },
                onFailure = { e ->
                    Toast.makeText(getApplication(), "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    updateUiState { it.copy(isLoading = false) }
                }
            )
        }
    }

    fun deleteItem(index: Int) {
        val item = _uiState.value.items.getOrNull(index) ?: return
        viewModelScope.launch {
            updateUiState { it.copy(isLoading = true) }
            val result = apiClient.deleteFiles(listOf(item.path))
            result.fold(
                onSuccess = {
                    Toast.makeText(getApplication(), "删除成功", Toast.LENGTH_SHORT).show()
                    refresh()
                },
                onFailure = { e ->
                    Toast.makeText(getApplication(), "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    updateUiState { it.copy(isLoading = false) }
                }
            )
        }
    }

    fun renameItem(index: Int, newName: String) {
        val item = _uiState.value.items.getOrNull(index) ?: return
        viewModelScope.launch {
            updateUiState { it.copy(isLoading = true) }
            val result = apiClient.renameFile(item.path, newName)
            result.fold(
                onSuccess = {
                    Toast.makeText(getApplication(), "重命名成功", Toast.LENGTH_SHORT).show()
                    refresh()
                },
                onFailure = { e ->
                    Toast.makeText(getApplication(), "重命名失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    updateUiState { it.copy(isLoading = false) }
                }
            )
        }
    }

    // endregion

    // region ==================== 移动 / 复制 ====================

    fun startMove(index: Int) {
        val item = _uiState.value.items.getOrNull(index) ?: return
        updateUiState { it.copy(pendingAction = "move", moveFileId = item.path) }
    }

    fun startCopy(index: Int) {
        val item = _uiState.value.items.getOrNull(index) ?: return
        updateUiState { it.copy(pendingAction = "copy", copyFileId = item.path) }
    }

    fun dismissPicker() {
        updateUiState {
            it.copy(pendingAction = null, moveFileId = null, copyFileId = null,
                pickerFolders = emptyList(), pickerIsLoading = false)
        }
    }

    fun loadFoldersForPicker(folderId: String) {
        viewModelScope.launch {
            val state = _uiState.value
            updateUiState { it.copy(pickerIsLoading = true) }
            val actualFolderId = folderId.ifEmpty { "/" }
            val result = apiClient.listFiles(actualFolderId)
            result.fold(
                onSuccess = { listResult ->
                    val folders = listResult.items
                        .filter { it.isDir && it.fileId != state.moveFileId }
                        .map { fileToResource(it) }
                    updateUiState { it.copy(pickerFolders = folders, pickerIsLoading = false) }
                },
                onFailure = { e ->
                    updateUiState { it.copy(pickerFolders = emptyList(), pickerIsLoading = false) }
                    Toast.makeText(getApplication(), "加载文件夹失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    fun createFolderInPicker(parentFolderId: String, name: String) {
        viewModelScope.launch {
            updateUiState { it.copy(pickerIsLoading = true) }
            val actualParentId = parentFolderId.ifEmpty { "/" }
            val result = apiClient.createFolder(actualParentId, name)
            result.fold(
                onSuccess = {
                    Toast.makeText(getApplication(), "文件夹创建成功", Toast.LENGTH_SHORT).show()
                    loadFoldersForPicker(parentFolderId)
                },
                onFailure = { e ->
                    Toast.makeText(getApplication(), "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    updateUiState { it.copy(pickerIsLoading = false) }
                }
            )
        }
    }

    fun moveTo(targetFolderId: String) {
        val fileId = _uiState.value.moveFileId ?: return
        viewModelScope.launch {
            updateUiState { it.copy(isLoading = true) }
            val result = apiClient.moveFiles(listOf(fileId), targetFolderId)
            result.fold(
                onSuccess = {
                    Toast.makeText(getApplication(), "移动成功", Toast.LENGTH_SHORT).show()
                    dismissPicker(); refresh()
                },
                onFailure = { e ->
                    Toast.makeText(getApplication(), "移动失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    updateUiState { it.copy(isLoading = false) }
                }
            )
        }
    }

    fun copyTo(targetFolderId: String) {
        viewModelScope.launch {
            updateUiState { it.copy(isLoading = true) }
            Toast.makeText(getApplication(), "移动云盘暂不支持复制", Toast.LENGTH_SHORT).show()
            updateUiState { it.copy(isLoading = false) }
        }
    }

    // endregion

    // region ==================== 视频播放 ====================

    suspend fun resolveVideoUri(item: WebDavResource): android.net.Uri? {
        val result = apiClient.getVideoPreviewUrl(item.path).getOrNull()
        if (!result.isNullOrBlank()) {
            val url = result + "#yun139Play=true#"
            CloudPlaylistCache.putResolvedUrl("yun139", item.path, url)
            return android.net.Uri.parse(url)
        }
        return null
    }

    // endregion

    // region ==================== 下载 ====================

    fun downloadFile(index: Int) {
        val res = _uiState.value.items.getOrNull(index) ?: return
        viewModelScope.launch {
            try {
                val urlResult = apiClient.getDownloadUrl(res.path, res.name)
                urlResult.fold(
                    onSuccess = { url ->
                        val dm = getApplication<Application>()
                            .getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                        val request = android.app.DownloadManager.Request(android.net.Uri.parse(url)).apply {
                            setTitle(res.name)
                            setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, res.name)
                        }
                        dm.enqueue(request)
                        Toast.makeText(getApplication(), "开始下载: ${res.name}", Toast.LENGTH_SHORT).show()
                    },
                    onFailure = { e ->
                        Toast.makeText(getApplication(), "获取下载链接失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(getApplication(), "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // endregion

    // region ==================== 工具方法 ====================

    private fun updateUiState(transform: (Yun139BrowserUiState) -> Yun139BrowserUiState) {
        while (true) {
            val current = _uiState.value
            val next = transform(current)
            if (_uiState.compareAndSet(current, next)) break
        }
    }

    private fun fileToResource(file: Yun139FileItem): WebDavResource {
        return WebDavResource(
            path = file.fileId, name = file.fileName,
            isDirectory = file.isDir, size = file.fileSize,
            lastModified = file.lastOpTime.ifEmpty { file.createDate }
        )
    }

    // endregion
}
