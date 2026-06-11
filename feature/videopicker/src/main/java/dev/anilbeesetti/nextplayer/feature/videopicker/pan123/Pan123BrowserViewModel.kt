package dev.anilbeesetti.nextplayer.feature.videopicker.pan123

import android.app.Application
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.anilbeesetti.nextplayer.core.common.CloudPlaylistCache
import dev.anilbeesetti.nextplayer.core.common.CloudUriScheme
import dev.anilbeesetti.nextplayer.core.data.cloud.CloudUriResolver
import dev.anilbeesetti.nextplayer.core.data.pan123.Pan123ApiClient
import dev.anilbeesetti.nextplayer.core.data.pan123.Pan123FileItem
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

data class Pan123Breadcrumb(val label: String, val fileId: String)

@HiltViewModel
class Pan123BrowserViewModel @Inject constructor(
    application: Application,
    private val preferencesRepository: PreferencesRepository,
    private val cloudUriResolver: CloudUriResolver
) : androidx.lifecycle.AndroidViewModel(application) {

    companion object {
        private const val TAG = "Pan123BrowserVM"
        private const val PREF_NAME = "pan123"
    }

    // region ==================== API Client ====================

    val apiClient = Pan123ApiClient()
    private var cachedFileItems: List<Pan123FileItem> = emptyList()

    // endregion

    // region ==================== 状态 ====================

    private val _uiState = MutableStateFlow(Pan123BrowserUiState())
    val uiState: StateFlow<Pan123BrowserUiState> = _uiState.asStateFlow()

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

    // region ==================== 登录 — autoLogin ====================

    init {
        autoLogin()
    }

    /**
     * 自动恢复登录：先尝试 token，再尝试账号密码
     */
    private fun autoLogin() {
        val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val passport = prefs.getString("passport", "") ?: ""
        val password = prefs.getString("password", "") ?: ""
        val token = prefs.getString("token", "") ?: ""

        // 优先使用 token 恢复
        if (token.isNotBlank()) {
            apiClient.setToken("Bearer $token")
            updateUiState { it.copy(isLoggedIn = true, isLoading = true) }
            syncStackTop { it.copy(isLoading = true, error = null) }
            // 从磁盘缓存预填根目录，加速子目录导航
            CloudDirectoryCache.get(getApplication(), "pan123", "0")?.let { directoryCache["0"] = it }
            viewModelScope.launch {
                try { loadDirectory("0") } catch (_: Exception) {}
            }
            return
        }

        // 尝试账号密码登录
        if (passport.isNotBlank() && password.isNotBlank()) {
            login(passport, password)
        }
    }

    // endregion

    // region ==================== 登录 — login(passport, password) ====================

    fun login(passport: String, password: String) {
        updateUiState { it.copy(isLoading = true) }
        viewModelScope.launch {
            val result = apiClient.login(passport, password)
            result.fold(
                onSuccess = { token ->
                    val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("passport", passport)
                        .putString("password", password)
                        .putString("token", token)
                        .apply()
                    updateUiState { it.copy(isLoading = false, isLoggedIn = true) }
                    loadDirectory("0")
                },
                onFailure = { e ->
                    updateUiState {
                        it.copy(isLoading = false, error = "登录失败: ${e.message}")
                    }
                }
            )
        }
    }

    // endregion

    // region ==================== 登录 — loginWithToken ====================

    fun loginWithToken(token: String) {
        val trimmed = token.trim()
        if (!trimmed.startsWith("Bearer ")) {
            Toast.makeText(getApplication(), "token 格式错误，应以 Bearer 开头", Toast.LENGTH_SHORT).show()
            return
        }
        apiClient.setTokenDirectly(trimmed)
        val tokenValue = trimmed.removePrefix("Bearer ")
        val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove("passport")
            .remove("password")
            .putString("token", tokenValue)
            .apply()
        updateUiState { it.copy(isLoggedIn = true) }
        viewModelScope.launch { loadDirectory("0") }
    }

    // endregion

    // region ==================== 登出 ====================

    fun logout() {
        val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        directoryCache.clear()
        CloudDirectoryCache.clear(getApplication(), "pan123")
        _uiState.value = Pan123BrowserUiState()
        _navigationStack.value = listOf(DirectoryStackEntry(fileId = "0", label = "根目录"))
    }

    // endregion

    // region ==================== 目录加载 ====================

    fun loadDirectory(parentFileId: String) {
        loadDirectoryJob?.cancel()
        loadSequence++
        val seq = loadSequence
        updateUiState { it.copy(isLoading = true, error = null, currentFileId = parentFileId) }
        syncStackTop { it.copy(isLoading = true, error = null) }

        loadDirectoryJob = viewModelScope.launch {
            val result = apiClient.listFiles(
                parentFileId,
                orderBy = _uiState.value.orderBy,
                orderDirection = _uiState.value.orderDirection
            )
            if (seq != loadSequence) return@launch

            result.fold(
                onSuccess = { items ->
                    cachedFileItems = items
                    // 缓存文件元数据到 CloudPlaylistCache，供播放器显示标题和 CloudUriResolver 解析
                    items.forEach { file ->
                        if (file.isVideo) {
                            CloudPlaylistCache.putFileMetadata(
                                "pan123", file.fileId,
                                CloudPlaylistCache.FileMetadata(
                                    fileName = file.fileName,
                                    etag = file.etag,
                                    size = file.size,
                                    s3keyFlag = file.s3keyFlag,
                                    downloadUrl = file.downloadUrl,
                                )
                            )
                        }
                    }
                    val resources = items.map { fileToResource(it) }
                    directoryCache[parentFileId] = resources
                    CloudDirectoryCache.put(getApplication(), "pan123", parentFileId, resources)
                    updateUiState {
                        it.copy(
                            items = resources,
                            isLoading = false, hasMore = items.size >= 100, currentPage = 1
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
                parentFileId = state.currentFileId,
                page = nextPage,
                orderBy = state.orderBy,
                orderDirection = state.orderDirection
            )
            result.fold(
                onSuccess = { items ->
                    cachedFileItems = cachedFileItems + items
                    // 缓存文件元数据到 CloudPlaylistCache（loadMore 也需要）
                    items.forEach { file ->
                        if (file.isVideo) {
                            CloudPlaylistCache.putFileMetadata(
                                "pan123", file.fileId,
                                CloudPlaylistCache.FileMetadata(
                                    fileName = file.fileName,
                                    etag = file.etag,
                                    size = file.size,
                                    s3keyFlag = file.s3keyFlag,
                                    downloadUrl = file.downloadUrl,
                                )
                            )
                        }
                    }
                    val newItems = items.map { fileToResource(it) }
                    val existingPaths = state.items.map { it.path }.toSet()
                    val filtered = newItems.filter { it.path !in existingPaths }
                    updateUiState {
                        it.copy(
                            items = state.items + filtered, isLoadingMore = false,
                            hasMore = items.size >= 100, currentPage = nextPage
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
                breadcrumbs = state.breadcrumbs + Pan123Breadcrumb(item.name, item.path),
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

    fun setSort(orderBy: String, orderDirection: String = "asc") {
        updateUiState { it.copy(orderBy = orderBy, orderDirection = orderDirection) }
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
            val result = apiClient.createFolder(name = name, parentFileId = state.currentFileId)
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
            val result = apiClient.trashFile(listOf(item.path))
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

    fun startCopy(index: Int) {
        val item = _uiState.value.items.getOrNull(index) ?: return
        val fileItem = cachedFileItems.find { it.fileId == item.path }
        updateUiState { it.copy(pendingAction = "copy", copyFileItem = fileItem) }
    }

    fun dismissPicker() {
        updateUiState {
            it.copy(pendingAction = null, moveFileId = null, copyFileItem = null,
                pickerFolders = emptyList(), pickerIsLoading = false)
        }
    }

    fun loadFoldersForPicker(folderId: String) {
        viewModelScope.launch {
            val state = _uiState.value
            updateUiState { it.copy(pickerIsLoading = true) }
            val result = apiClient.listFiles(parentFileId = folderId)
            result.fold(
                onSuccess = { items ->
                    val folders = items
                        .filter { it.type == 1 && it.fileId != state.moveFileId }
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
            val result = apiClient.createFolder(name = name, parentFileId = parentFolderId)
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
            val result = apiClient.moveFile(fileId = fileId, parentFileId = targetFolderId)
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
        return cloudUriResolver.resolve(CloudUriScheme.buildCloudUri("pan123", item.path))
    }

    // endregion

    // region ==================== 下载 ====================

    fun downloadFile(index: Int) {
        val res = _uiState.value.items.getOrNull(index) ?: return
        viewModelScope.launch {
            try {
                val fileItem = cachedFileItems.find { it.fileId == res.path }
                    ?: return@launch
                val urlResult = apiClient.getFileDownloadUrl(fileItem)
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

    private fun updateUiState(transform: (Pan123BrowserUiState) -> Pan123BrowserUiState) {
        while (true) {
            val current = _uiState.value
            val next = transform(current)
            if (_uiState.compareAndSet(current, next)) break
        }
    }

    private fun fileToResource(file: Pan123FileItem): WebDavResource {
        return WebDavResource(
            path = file.fileId,
            name = file.fileName,
            isDirectory = file.type == 1,
            size = file.size,
            lastModified = file.createAt
        )
    }

    private fun buildBreadcrumbs(fileId: String, label: String?): List<Pan123Breadcrumb> {
        val crumbs = mutableListOf(Pan123Breadcrumb("根目录", "0"))
        if (fileId != "0" && label != null) crumbs.add(Pan123Breadcrumb(label, fileId))
        return crumbs
    }

    // endregion
}
