package com.fluxplayer.app.feature.videopicker.cloud189

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.fluxplayer.app.core.common.CloudPlaylistCache
import com.fluxplayer.app.core.common.CloudUriScheme
import com.fluxplayer.app.core.data.cloud.CloudUriResolver
import com.fluxplayer.app.core.data.cloud189.C189ApiClient
import com.fluxplayer.app.core.data.cloud189.C189AuthProvider
import com.fluxplayer.app.core.data.cloud189.C189FileItem
import com.fluxplayer.app.core.data.repository.PlaybackHistoryRepository
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.feature.videopicker.CloudDirectoryCache
import com.fluxplayer.app.feature.videopicker.DirectoryStackEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class C189Breadcrumb(val label: String, val fileId: String)

@HiltViewModel
class C189BrowserViewModel @Inject constructor(
    application: Application,
    private val preferencesRepository: PreferencesRepository,
    private val cloudUriResolver: CloudUriResolver,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
) : androidx.lifecycle.AndroidViewModel(application) {

    companion object {
        private const val TAG = "C189BrowserVM"
        private const val PREF_NAME = "cloud189"
    }

    // region ==================== API Client ====================

    val apiClient = C189ApiClient()

    // endregion

    // region ==================== 状态 ====================

    private val _uiState = MutableStateFlow(C189BrowserUiState())
    val uiState: StateFlow<C189BrowserUiState> = _uiState.asStateFlow()

    private var loadSequence: Int = 0
    private var loadDirectoryJob: kotlinx.coroutines.Job? = null
    private var loadMoreJob: kotlinx.coroutines.Job? = null
    private var loadingMore: Boolean = false
    private val directoryCache = mutableMapOf<String, List<WebDavResource>>()

    init {
        viewModelScope.launch {
            playbackHistoryRepository.getHistoryFlow().collect { history ->
                val latestPerDir = history
                    .filter { it.parentPath != null }
                    .groupBy { it.parentPath!! }
                    .mapValues { (_, list) -> list.maxByOrNull { it.lastPlayedTime }!! }
                    .values.map { it.uriString }.toSet()
                _uiState.update { it.copy(playedUriSet = latestPerDir) }
            }
        }
    }

    private val _navigationStack = MutableStateFlow(
        listOf(DirectoryStackEntry(fileId = "-11", label = "根目录"))
    )
    val navigationStack: StateFlow<List<DirectoryStackEntry>> = _navigationStack.asStateFlow()

    private fun syncStackTop(transform: (DirectoryStackEntry) -> DirectoryStackEntry) {
        _navigationStack.update { stack ->
            if (stack.isEmpty()) return@update stack
            stack.toMutableList().apply { set(lastIndex, transform(get(lastIndex))) }
        }
    }

    // endregion

    // region ==================== 登录 — tryRestoreSession ====================

    init {
        viewModelScope.launch { tryRestoreSession() }
    }

    fun tryRestoreSession() {
        val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val accessToken = prefs.getString("accessToken", "") ?: ""
        val sessionKey = prefs.getString("sessionKey", "") ?: ""
        val sessionSecret = prefs.getString("sessionSecret", "") ?: ""

        if (accessToken.isNotBlank() && sessionKey.isNotBlank()) {
            apiClient.accessToken = accessToken
            apiClient.sessionKey = sessionKey
            apiClient.sessionSecret = sessionSecret
            C189AuthProvider.accessToken = accessToken
            C189AuthProvider.sessionKey = sessionKey
            C189AuthProvider.sessionSecret = sessionSecret
            C189AuthProvider.refreshToken = prefs.getString("refreshToken", "") ?: ""
            C189AuthProvider.expiresIn = prefs.getLong("expiresIn", 0)
            C189AuthProvider.isActive = true

            // 恢复 Cookie（重启后丢失，从 SharedPreferences 注入到 GlobalCookieJar）
            val cookies = prefs.getString("cookies", "") ?: ""
            apiClient.restoreCookies(cookies)

            updateUiState { it.copy(isLoggedIn = true, isLoading = true) }
            syncStackTop { it.copy(isLoading = true, error = null) }
            // 从磁盘缓存预填根目录，加速子目录导航
            CloudDirectoryCache.get(getApplication(), "cloud189", "-11")?.let { directoryCache["-11"] = it }
            viewModelScope.launch {
                try {
                    loadDirectory("-11")
                } catch (_: Exception) {
                    try {
                        val ok = apiClient.refreshAccessToken()
                        if (ok) {
                            saveTokens()
                            loadDirectory("-11")
                        } else {
                            throw Exception("token 刷新失败")
                        }
                    } catch (_: Exception) {
                        android.util.Log.w(TAG, "tryRestoreSession: token 已过期，需要重新登录")
                        logout()
                    }
                }
            }
        }
    }

    // endregion

    // region ==================== 登录 — loginByPassword (手机号+密码) ====================

    /** 持久化 token 到 SharedPreferences */
    private fun saveTokens() {
        val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("accessToken", apiClient.accessToken)
            .putString("sessionKey", apiClient.sessionKey)
            .putString("sessionSecret", apiClient.sessionSecret)
            .putString("refreshToken", C189AuthProvider.refreshToken)
            .putLong("expiresIn", C189AuthProvider.expiresIn)
            .putString("cookies", apiClient.saveCookies())
            .apply()
    }

    /**
     * 手机号 + 密码登录 — 5 步 RSA 加密登录流程
     * 成功后自动加载根目录文件列表
     */
    fun loginByPassword(phone: String, password: String) {
        android.util.Log.d(TAG, "loginByPassword called phone=${phone.take(3)}**** pwdLen=${password.length}")
        updateUiState { it.copy(loginLoading = true, error = null) }
        viewModelScope.launch {
            val result = apiClient.loginByPassword(phone, password)
            result.fold(
                onSuccess = {
                    android.util.Log.d(TAG, "loginByPassword SUCCESS, saving tokens")
                    saveTokens()
                    updateUiState { it.copy(isLoggedIn = true, loginLoading = false) }
                    loadDirectory("-11")
                },
                onFailure = { e ->
                    val msg = e.message ?: "登录失败"
                    android.util.Log.e(TAG, "loginByPassword FAILED: $msg", e)
                    updateUiState {
                        it.copy(loginLoading = false, error = msg)
                    }
                }
            )
        }
    }

    // endregion

    // region ==================== 登录 — loginWithCookies (Cookie登录备用) ====================

    fun loginWithCookies(cookies: String) {
        val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        updateUiState { it.copy(loginLoading = true) }
        viewModelScope.launch {
            val ok = apiClient.loginByCookies(cookies)
            if (ok) {
                saveTokens()
                updateUiState { it.copy(isLoggedIn = true, loginLoading = false) }
                loadDirectory("-11")
            } else {
                updateUiState {
                    it.copy(loginLoading = false, error = "Cookie 登录失败")
                }
            }
        }
    }

    // endregion

    // region ==================== 登出 ====================

    fun logout() {
        C189AuthProvider.clear()
        val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        directoryCache.clear()
        CloudDirectoryCache.clear(getApplication(), "cloud189")
        _uiState.value = C189BrowserUiState()
        _navigationStack.value = listOf(DirectoryStackEntry(fileId = "-11", label = "根目录"))
    }

    // endregion

    // region ==================== 目录加载 ====================

    fun loadDirectory(parentFileId: String) {
        loadDirectoryJob?.cancel()
        loadMoreJob?.cancel()
        loadingMore = false
        loadSequence++
        val seq = loadSequence
        updateUiState { it.copy(isLoading = true, error = null, currentFolderId = parentFileId) }
        syncStackTop { it.copy(isLoading = true, error = null) }

        loadDirectoryJob = viewModelScope.launch {
            val state = _uiState.value
            // API 不支持 filesize 排序，用 lastOpTime 获取后客户端排序
            val apiOrderBy = if (state.orderBy == "filesize") "lastOpTime" else state.orderBy
            val result = apiClient.listFiles(
                parentFileId, state.currentPage, 100, apiOrderBy, state.descending
            )
            if (seq != loadSequence) return@launch

            result.fold(
                onSuccess = { listResult ->
                    // 缓存文件元数据到 CloudPlaylistCache，供播放器显示标题
                    val currentFileId = _navigationStack.value.lastOrNull()?.fileId ?: "-11"
                    val fullPathLabel = _navigationStack.value.joinToString("/") { "${it.label}|${it.fileId}" }
                    listResult.items.forEach { file ->
                        if (file.isVideo) {
                            CloudPlaylistCache.putFileMetadata(
                                "cloud189", file.id,
                                CloudPlaylistCache.FileMetadata(
                                    fileName = file.name,
                                    parentPath = "$currentFileId|$fullPathLabel",
                                )
                            )
                        }
                    }
                    val resources = sortResources(listResult.items.map { fileToResource(it) })
                    directoryCache[parentFileId] = resources
                    CloudDirectoryCache.put(getApplication(), "cloud189", parentFileId, resources)
                    updateUiState {
                        it.copy(
                            items = resources,
                            isLoading = false, hasMore = listResult.items.size >= 100, currentPage = 1
                        )
                    }
                    syncStackTop { it.copy(items = resources, isLoading = false, error = null) }
                },
                onFailure = { e ->
                    val msg = e.message ?: "未知错误"
                    val friendly = when {
                        msg.contains("require login", ignoreCase = true) ||
                        msg.contains("token", ignoreCase = true) ||
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
            loadMoreJob?.cancel()
            loadingMore = false
            val sorted = sortResources(cached)
            updateUiState {
                it.copy(
                    items = sorted,
                    currentFolderId = fileId,
                    isLoading = false,
                    isLoadingMore = false,
                    error = null,
                    currentPage = (cached.size + 99) / 100,
                    hasMore = cached.size >= 100
                )
            }
            syncStackTop { it.copy(items = sorted, isLoading = false, error = null) }
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
        loadingMore = true
        val nextPage = state.currentPage + 1
        updateUiState { it.copy(isLoadingMore = true) }

        loadMoreJob = viewModelScope.launch {
            try {
                val apiOrderBy = if (state.orderBy == "filesize") "lastOpTime" else state.orderBy
                val result = apiClient.listFiles(
                    state.currentFolderId, nextPage, 100, apiOrderBy, state.descending
                )
                result.fold(
                    onSuccess = { listResult ->
                        val newItems = sortResources(listResult.items.map { fileToResource(it) })
                        val existingPaths = _uiState.value.items.map { it.path }.toSet()
                        val filtered = newItems.filter { it.path !in existingPaths }
                        if (filtered.isEmpty()) {
                            updateUiState { it.copy(isLoadingMore = false, hasMore = false) }
                            return@fold
                        }
                        val combined = sortResources(_uiState.value.items + filtered)
                        updateUiState {
                            it.copy(
                                items = combined, isLoadingMore = false,
                                hasMore = listResult.items.size >= 100, currentPage = nextPage
                            )
                        }
                        syncStackTop { it.copy(items = combined, isLoading = false, error = null) }
                    },
                    onFailure = { e ->
                        updateUiState { it.copy(isLoadingMore = false, error = "加载更多失败: ${e.message}") }
                    }
                )
            } finally {
                loadingMore = false
                updateUiState { it.copy(isLoadingMore = false) }
            }
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
                breadcrumbs = state.breadcrumbs + C189Breadcrumb(item.name, item.path),
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

    fun jumpToFolder(fileId: String, label: String) {
        val segments = label.split("/").filter { it.isNotBlank() }
        val pathCrumbs = segments.map { seg ->
            val parts = seg.split("|", limit = 2)
            C189Breadcrumb(parts[0], if (parts.size > 1) parts[1] else "")
        }
        if (pathCrumbs.isEmpty()) return
        updateUiState { it.copy(breadcrumbs = pathCrumbs) }
        _navigationStack.value = pathCrumbs.map { DirectoryStackEntry(fileId = it.fileId, label = it.label) }
        loadDirectoryCached(fileId)
    }

    // endregion

    // region ==================== 排序 ====================

    fun setSort(orderBy: String, descending: Boolean = false) {
        updateUiState { it.copy(orderBy = orderBy, descending = descending) }
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
            val actualFolderId = folderId.ifEmpty { "-11" }
            val result = apiClient.listFiles(actualFolderId)
            result.fold(
                onSuccess = { listResult ->
                    val folders = listResult.items
                        .filter { it.isDir && it.id != state.moveFileId }
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
            val actualParentId = parentFolderId.ifEmpty { "-11" }
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
        val copyId = _uiState.value.copyFileId ?: return
        viewModelScope.launch {
            updateUiState { it.copy(isLoading = true) }
            Toast.makeText(getApplication(), "天翼云暂不支持复制", Toast.LENGTH_SHORT).show()
            updateUiState { it.copy(isLoading = false) }
        }
    }

    // endregion

    // region ==================== 视频播放 ====================

    suspend fun resolveVideoUri(item: WebDavResource): android.net.Uri? {
        return cloudUriResolver.resolve(CloudUriScheme.buildCloudUri("cloud189", item.path))
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

    private fun updateUiState(transform: (C189BrowserUiState) -> C189BrowserUiState) {
        while (true) {
            val current = _uiState.value
            val next = transform(current)
            if (_uiState.compareAndSet(current, next)) break
        }
    }

    private fun sortResources(items: List<WebDavResource>): List<WebDavResource> {
        val state = _uiState.value
        if (state.orderBy != "filesize") return items
        val comparator = compareBy<WebDavResource> { it.size }
        return if (state.descending) items.sortedWith(comparator.reversed()) else items.sortedWith(comparator)
    }

    private fun fileToResource(file: C189FileItem): WebDavResource {
        return WebDavResource(
            path = file.id, name = file.name,
            isDirectory = file.isDir, size = file.size, lastModified = file.lastOpTime,
            thumbnailUrl = file.thumbnailUrl,
            fileCount = if (file.isDir && file.fileCount > 0) file.fileCount else null,
            folderSize = if (file.isDir) file.folderSize else 0,
            category = mediaTypeToCategory(file.mediaType),
            createdAt = file.createDate
        )
    }

    private fun mediaTypeToCategory(mediaType: Int): String = when (mediaType) {
        1 -> "image"
        2 -> "audio"
        3 -> "video"
        else -> ""
    }

    private fun buildBreadcrumbs(fileId: String, label: String?): List<C189Breadcrumb> {
        val crumbs = mutableListOf(C189Breadcrumb("根目录", "-11"))
        if (fileId != "-11" && label != null) crumbs.add(C189Breadcrumb(label, fileId))
        return crumbs
    }

    // endregion
}
