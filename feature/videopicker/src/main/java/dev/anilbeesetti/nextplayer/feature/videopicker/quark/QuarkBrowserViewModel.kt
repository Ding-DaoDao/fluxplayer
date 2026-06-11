package dev.anilbeesetti.nextplayer.feature.videopicker.quark

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.anilbeesetti.nextplayer.core.common.CloudPlayHeaders
import dev.anilbeesetti.nextplayer.core.common.CloudPlaylistCache
import dev.anilbeesetti.nextplayer.core.common.VideoQualityCache
import dev.anilbeesetti.nextplayer.core.data.quark.QuarkApiClient
import dev.anilbeesetti.nextplayer.core.data.quark.QuarkAuthProvider
import dev.anilbeesetti.nextplayer.core.data.quark.QuarkFileItem
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.model.WebDavResource
import dev.anilbeesetti.nextplayer.feature.videopicker.CloudDirectoryCache
import dev.anilbeesetti.nextplayer.feature.videopicker.DirectoryStackEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class QuarkBreadcrumb(val label: String, val fileId: String)

@HiltViewModel
class QuarkBrowserViewModel @Inject constructor(
    application: Application,
    private val preferencesRepository: PreferencesRepository,
    private val videoQualityCache: VideoQualityCache
) : androidx.lifecycle.AndroidViewModel(application) {

    companion object {
        private const val TAG = "QuarkBrowserVM"
        private const val PREF_NAME = "quark"
        private const val UC_PREF_NAME = "uc"
    }

    // region ==================== API Client ====================

    val apiClient = QuarkApiClient()
    private val itemCache = mutableMapOf<String, QuarkFileItem>()

    // endregion

    // region ==================== 状态 ====================

    private val _uiState = MutableStateFlow(QuarkBrowserUiState())
    val uiState: StateFlow<QuarkBrowserUiState> = _uiState.asStateFlow()

    private val _navigationStack = MutableStateFlow(
        listOf(DirectoryStackEntry(fileId = "0", label = "根目录"))
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

    // region ==================== 登录 — loginWithCookie（含 driveType 和 cookie 验证） ====================

    fun loginWithCookie(cookie: String, driveType: String = "quark") {
        Log.d(TAG, "loginWithCookie: driveType=$driveType, cookie长度=${cookie.length}")
        val prefName = if (driveType == "uc") UC_PREF_NAME else PREF_NAME
        val prefs = getApplication<Application>().getSharedPreferences(prefName, Context.MODE_PRIVATE)
        prefs.edit().putString("cookie", cookie).apply()

        apiClient.setCookie(cookie)

        updateUiState { it.copy(isLoggedIn = true, driveType = driveType) }
        Log.d(TAG, "loginWithCookie: 开始加载根目录")
        loadDirectory("0")
    }

    // endregion

    // region ==================== 登录 — setDriveType（UC/普通模式切换） ====================

    fun setDriveType(type: String) {
        val isSwitching = _uiState.value.driveType != type
        Log.d(TAG, "setDriveType: type=$type, isSwitching=$isSwitching")
        apiClient.setDriveType(type)
        val prefName = if (type == "uc") UC_PREF_NAME else PREF_NAME
        val prefs = getApplication<Application>().getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val cookie = prefs.getString("cookie", "") ?: ""
        // 切换 driveType 时重置状态，防止夸克/UC 之间数据泄漏
        if (isSwitching) {
            directoryCache.clear()
            _navigationStack.value = listOf(DirectoryStackEntry(fileId = "0", label = "根目录"))
        }
        if (cookie.isNotBlank()) {
            apiClient.setCookie(cookie)
            updateUiState { it.copy(isLoggedIn = true, driveType = type, items = if (isSwitching) emptyList() else it.items, error = null, isLoading = true) }
            syncStackTop { it.copy(isLoading = true, error = null) }
            // 从磁盘缓存预填根目录，加速子目录导航
            CloudDirectoryCache.get(getApplication(), type, "0")?.let { directoryCache["0"] = it }
            loadDirectory("0")
        } else {
            if (isSwitching) QuarkAuthProvider.clear()
            updateUiState { it.copy(isLoggedIn = false, driveType = type, items = if (isSwitching) emptyList() else it.items, error = null, isLoading = false) }
        }
    }

    // endregion

    // region ==================== 登出 ====================

    fun logout() {
        QuarkAuthProvider.clear()
        val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        val ucPrefs = getApplication<Application>().getSharedPreferences(UC_PREF_NAME, Context.MODE_PRIVATE)
        ucPrefs.edit().clear().apply()
        directoryCache.clear()
        CloudDirectoryCache.clear(getApplication(), "quark")
        CloudDirectoryCache.clear(getApplication(), "uc")
        // 清除 WebView 痕迹
        try {
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
            android.webkit.WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {}
        _uiState.value = QuarkBrowserUiState()
        _navigationStack.value = listOf(DirectoryStackEntry(fileId = "0", label = "根目录"))
    }

    // endregion

    // region ==================== 目录加载 ====================

    fun loadDirectory(parentFileId: String) {
        Log.d(TAG, "loadDirectory: parentFileId=$parentFileId, hasValidCookie=${apiClient.hasValidCookie()}")
        loadDirectoryJob?.cancel()
        loadSequence++
        val seq = loadSequence
        updateUiState { it.copy(isLoading = true, error = null, currentFileId = parentFileId) }
        syncStackTop { it.copy(isLoading = true, error = null) }

        loadDirectoryJob = viewModelScope.launch {
            val result = apiClient.listFiles(parentFileId)
            if (seq != loadSequence) return@launch

            result.fold(
                onSuccess = { items ->
                    Log.d(TAG, "loadDirectory SUCCESS: ${items.size}个文件")
                    items.forEach { itemCache[it.fid] = it }
                    // 缓存文件元数据到 CloudPlaylistCache，供播放器显示标题
                    val provider = if (_uiState.value.driveType == "uc") "uc" else "quark"
                    items.forEach { file ->
                        if (file.isVideo) {
                            CloudPlaylistCache.putFileMetadata(
                                provider, file.fid,
                                CloudPlaylistCache.FileMetadata(fileName = file.fileName)
                            )
                        }
                    }
                    val resources = items.map { fileToResource(it) }
                    directoryCache[parentFileId] = resources
                    CloudDirectoryCache.put(getApplication(), provider, parentFileId, resources)
                    updateUiState {
                        it.copy(
                            items = resources,
                            isLoading = false, hasMore = items.size >= 100, currentPage = 1
                        )
                    }
                    syncStackTop { it.copy(items = resources, isLoading = false, error = null) }
                },
                onFailure = { e ->
                    Log.e(TAG, "loadDirectory FAILED: ${e.message}", e)
                    val msg = e.message ?: "未知错误"
                    val friendly = when {
                        msg.contains("require login", ignoreCase = true) ||
                        msg.contains("token", ignoreCase = true) ||
                        msg.contains("invalid", ignoreCase = true) ||
                        msg.contains("401") -> "登录已过期，请重新登录"
                        else -> "加载失败: $msg"
                    }
                    updateUiState { it.copy(error = friendly, isLoading = false) }
                    syncStackTop { it.copy(error = friendly, isLoading = false) }
                }
            )
        }
    }

    private fun loadDirectoryCached(fileId: String) {
        val cached = directoryCache[fileId]
        if (cached != null) {
            loadDirectoryJob?.cancel()
            loadSequence++
            updateUiState {
                it.copy(
                    items = cached,
                    currentFileId = fileId,
                    isLoading = false,
                    error = null
                )
            }
            syncStackTop { it.copy(items = cached, isLoading = false, error = null) }
        } else {
            updateUiState { it.copy(items = emptyList(), currentFileId = fileId) }
            syncStackTop { it.copy(items = emptyList(), isLoading = true, error = null) }
            loadDirectory(fileId)
        }
    }

    // endregion

    // region ==================== 加载更多 ====================

    fun loadMore() {
        val state = _uiState.value
        if (!state.hasMore || loadingMore || state.isLoading) return
        loadingMore = true
        val nextPage = state.currentPage + 1
        updateUiState { it.copy(isLoadingMore = true) }

        viewModelScope.launch {
            val result = apiClient.listFiles(
                pdirFid = state.currentFileId,
                page = nextPage,
                orderBy = state.orderBy
            )
            result.fold(
                onSuccess = { items ->
                    items.forEach { itemCache[it.fid] = it }
                    val newItems = items.map { fileToResource(it) }
                    val existingPaths = state.items.map { it.path }.toSet()
                    val filtered = newItems.filter { it.path !in existingPaths }
                    updateUiState {
                        it.copy(
                            items = state.items + filtered,
                            isLoadingMore = false,
                            hasMore = items.size >= 100,
                            currentPage = nextPage
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
                breadcrumbs = state.breadcrumbs + QuarkBreadcrumb(item.name, item.path),
                scrollTargetIndex = index,
                scrollTargetParentKey = parentKey
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

    fun jumpToFolder(fileId: String, label: String) {
        val state = _uiState.value
        val segments = label.split("/")
        val root = state.breadcrumbs.firstOrNull() ?: QuarkBreadcrumb("根目录", "0")
        val pathCrumbs = segments.map { seg ->
            val parts = seg.split("|", limit = 2)
            QuarkBreadcrumb(parts[0], if (parts.size > 1) parts[1] else "")
        }
        updateUiState { it.copy(breadcrumbs = listOf(root) + pathCrumbs) }
        loadDirectoryCached(fileId)
    }

    // endregion

    // region ==================== 排序 ====================

    fun setSort(orderBy: String) {
        updateUiState { it.copy(orderBy = orderBy) }
        loadDirectory(_uiState.value.currentFileId)
    }

    // endregion

    // region ==================== 刷新 ====================

    fun refresh() { loadDirectory(_uiState.value.currentFileId) }

    // endregion

    // region ==================== CRUD ====================

    fun createDirectory(name: String) {
        viewModelScope.launch {
            val state = _uiState.value
            updateUiState { it.copy(isLoading = true) }
            val result = apiClient.createFolder(name, state.currentFileId)
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

    // region ==================== 移动 ====================

    fun startMove(index: Int) {
        val item = _uiState.value.items.getOrNull(index) ?: return
        updateUiState { it.copy(pendingAction = "move", moveFileId = item.path) }
    }

    fun dismissPicker() {
        updateUiState {
            it.copy(pendingAction = null, moveFileId = null, pickerFolders = emptyList(), pickerIsLoading = false)
        }
    }

    fun loadFoldersForPicker(folderId: String) {
        viewModelScope.launch {
            val state = _uiState.value
            updateUiState { it.copy(pickerIsLoading = true) }
            val actualFolderId = folderId.ifEmpty { "0" }
            val result = apiClient.listFiles(actualFolderId)
            result.fold(
                onSuccess = { items ->
                    val folders = items
                        .filter { it.dir && it.fid != state.moveFileId }
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
            val actualParentId = parentFolderId.ifEmpty { "0" }
            val result = apiClient.createFolder(name, actualParentId)
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
                    dismissPicker()
                    refresh()
                },
                onFailure = { e ->
                    Toast.makeText(getApplication(), "移动失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    updateUiState { it.copy(isLoading = false) }
                }
            )
        }
    }

    // endregion

    // region ==================== 视频播放 ====================

    suspend fun resolveVideoUri(item: WebDavResource): android.net.Uri? {
        val driveLabel = if (uiState.value.driveType == "uc") "uc" else "quark"
        val fragment = if (uiState.value.driveType == "uc") "ucPlay=true" else "quarkPlay=true"

        Log.d(TAG, "resolveVideoUri: path=${item.path}, name=${item.name}, driveLabel=$driveLabel")

        // 注册夸克播放头（lambda 实时读取，避免 stale cookie）
        CloudPlayHeaders.register("vod.quark.cn") { QuarkAuthProvider.getPlayHeaders() }
        CloudPlayHeaders.register("drive.quark.cn") { QuarkAuthProvider.getPlayHeaders() }

        // 先检查缓存
        val cached = CloudPlaylistCache.getResolvedUrl(driveLabel, item.path)
        if (cached != null) {
            Log.d(TAG, "resolveVideoUri: 命中缓存 -> $cached")
            return android.net.Uri.parse(cached)
        }

        // 优先尝试 getVideoPlayInfo
        val result = apiClient.getVideoPlayInfo(item.path).getOrNull()
        if (result != null && result.urls.isNotEmpty()) {
            val url = result.urls.first() + "#$fragment#"
            Log.d(TAG, "resolveVideoUri: getVideoPlayInfo成功 -> ${url.take(100)}")
            CloudPlaylistCache.putResolvedUrl(driveLabel, item.path, url)

            // 缓存清晰度选项，加速后续切换
            if (result.urls.size > 1) {
                val qualityOptions = result.urls.zip(result.names).map { (u, n) ->
                    VideoQualityCache.QualityOption(label = n, url = u)
                }
                videoQualityCache.cacheQualityOptions(
                    provider = driveLabel,
                    fileId = item.path,
                    videoName = item.name,
                    options = qualityOptions
                )
            }

            return android.net.Uri.parse(url)
        }

        // 回退：尝试 getDownloadUrl
        Log.d(TAG, "resolveVideoUri: getVideoPlayInfo失败或无源，尝试getDownloadUrl")
        val dlUrl = apiClient.getDownloadUrl(item.path).getOrNull()
        if (dlUrl != null) {
            val url = dlUrl + "#$fragment#"
            Log.d(TAG, "resolveVideoUri: getDownloadUrl成功 -> ${url.take(100)}")
            CloudPlaylistCache.putResolvedUrl(driveLabel, item.path, url)
            return android.net.Uri.parse(url)
        }

        Log.e(TAG, "resolveVideoUri: 所有方式都失败了")
        return null
    }

    // endregion

    // region ==================== 下载 ====================

    fun downloadFile(index: Int) {
        val res = _uiState.value.items.getOrNull(index) ?: return
        viewModelScope.launch {
            try {
                val urlResult = apiClient.getDownloadUrl(res.path)
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

    private fun updateUiState(transform: (QuarkBrowserUiState) -> QuarkBrowserUiState) {
        while (true) {
            val current = _uiState.value
            val next = transform(current)
            if (_uiState.compareAndSet(current, next)) break
        }
    }

    private fun fileToResource(file: QuarkFileItem): WebDavResource {
        return WebDavResource(
            path = file.fid,
            name = file.fileName,
            isDirectory = file.dir,
            size = file.size,
            lastModified = if (file.updatedAt > 0) file.updatedAt.toString() else ""
        )
    }

    private fun buildBreadcrumbs(fileId: String, label: String?): List<QuarkBreadcrumb> {
        val crumbs = mutableListOf(QuarkBreadcrumb("根目录", "0"))
        if (fileId != "0" && label != null) {
            crumbs.add(QuarkBreadcrumb(label, fileId))
        }
        return crumbs
    }

    // endregion
}
