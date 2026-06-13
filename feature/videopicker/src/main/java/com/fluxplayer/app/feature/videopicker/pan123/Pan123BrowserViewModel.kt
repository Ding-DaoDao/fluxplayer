package com.fluxplayer.app.feature.videopicker.pan123

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.fluxplayer.app.core.common.CloudPlayHeaders
import com.fluxplayer.app.core.common.CloudPlaylistCache
import com.fluxplayer.app.core.common.CloudUriScheme
import com.fluxplayer.app.core.common.PickerUtils
import com.fluxplayer.app.core.data.GlobalCookieJar
import com.fluxplayer.app.core.data.cloud.CloudUriResolver
import com.fluxplayer.app.core.data.pan123.Pan123ApiClient
import com.fluxplayer.app.core.data.pan123.Pan123AuthProvider
import com.fluxplayer.app.core.data.pan123.Pan123FileItem
import com.fluxplayer.app.core.data.repository.CloudDownloadRepository
import com.fluxplayer.app.core.data.repository.PlaybackHistoryRepository
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.feature.videopicker.CloudDirectoryCache
import com.fluxplayer.app.feature.videopicker.DirectoryStackEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class Pan123Breadcrumb(val label: String, val fileId: String)

@HiltViewModel
class Pan123BrowserViewModel @Inject constructor(
    application: Application,
    private val preferencesRepository: PreferencesRepository,
    private val cloudUriResolver: CloudUriResolver,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val cloudDownloadRepository: CloudDownloadRepository,
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
    private var loadMoreJob: kotlinx.coroutines.Job? = null
    private var loadingMore: Boolean = false
    private val directoryCache = mutableMapOf<String, List<WebDavResource>>()
    private var downloadJob: Job? = null

    data class DownloadProgressData(
        val fileName: String = "",
        val progress: Float = 0f,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val completedFilePath: String? = null
    )

    private val _downloadProgress = MutableStateFlow<DownloadProgressData?>(null)
    val downloadProgress: StateFlow<DownloadProgressData?> = _downloadProgress.asStateFlow()

    // endregion

    // region ==================== 足迹 ====================

    init {
        viewModelScope.launch {
            playbackHistoryRepository.getHistoryFlow().collect { history ->
                val latestPerDir = history
                    .filter { it.parentPath != null }
                    .groupBy { it.parentPath!! }
                    .mapValues { (_, list) -> list.maxByOrNull { it.lastPlayedTime }!! }
                    .values.map { it.uriString }.toSet()
                _uiState.update { it.copy(playedUriStrings = latestPerDir) }
            }
        }
    }

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
        val expireTime = prefs.getLong("refresh_token_expire_time", 0)
        val now = System.currentTimeMillis() / 1000

        // 优先使用 token 恢复
        if (token.isNotBlank()) {
            // token 过期且有账号密码时自动重新登录
            if (now > expireTime && passport.isNotBlank() && password.isNotBlank()) {
                login(passport, password)
                return
            }
            apiClient.setToken("Bearer $token")
            Pan123AuthProvider.authorization = "Bearer $token"
            Pan123AuthProvider.isActive = true
            CloudPlayHeaders.registerSuffix(".123pan.cn") { Pan123AuthProvider.getPlayHeaders() }
            CloudPlayHeaders.registerSuffix("cjjd19.com") { Pan123AuthProvider.getPlayHeaders() }
            updateUiState { it.copy(isLoggedIn = true, isLoading = true, initializing = false) }
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
        } else {
            // 无任何保存的凭据，显示登录表单
            updateUiState { it.copy(initializing = false) }
        }
    }

    // endregion

    // region ==================== 登录 — login(passport, password) ====================

    fun login(passport: String, password: String) {
        updateUiState { it.copy(isLoading = true) }
        viewModelScope.launch {
            val result = apiClient.login(passport, password)
            result.fold(
                onSuccess = { loginResult ->
                    val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    prefs.edit()
                        .putString("passport", passport)
                        .putString("password", password)
                        .putString("token", loginResult.token)
                        .putLong("refresh_token_expire_time", loginResult.refreshTokenExpireTime)
                        .apply()
                    Pan123AuthProvider.authorization = "Bearer ${loginResult.token}"
                    Pan123AuthProvider.isActive = true
                    CloudPlayHeaders.registerSuffix(".123pan.cn") { Pan123AuthProvider.getPlayHeaders() }
                    CloudPlayHeaders.registerSuffix("cjjd19.com") { Pan123AuthProvider.getPlayHeaders() }
                    updateUiState { it.copy(isLoading = false, isLoggedIn = true) }
                    loadDirectory("0")
                },
                onFailure = { e ->
                    updateUiState {
                        it.copy(isLoading = false, initializing = false, error = "登录失败: ${e.message}")
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
        Pan123AuthProvider.authorization = trimmed
        Pan123AuthProvider.isActive = true
        CloudPlayHeaders.registerSuffix(".123pan.cn") { Pan123AuthProvider.getPlayHeaders() }
        CloudPlayHeaders.registerSuffix("cjjd19.com") { Pan123AuthProvider.getPlayHeaders() }
        updateUiState { it.copy(isLoggedIn = true) }
        viewModelScope.launch { loadDirectory("0") }
    }

    // endregion

    // region ==================== 登出 ====================

    fun logout() {
        Pan123AuthProvider.clear()

        // 清除 OkHttp GlobalCookieJar 中旧账号的 Cookie
        GlobalCookieJar.clearHost("api.123278.com")
        GlobalCookieJar.clearHost("apigate.123795.com")

        // 重置 ApiClient 字段
        apiClient.setToken("")

        val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        directoryCache.clear()
        CloudDirectoryCache.clear(getApplication(), "pan123")
        _uiState.value = Pan123BrowserUiState(initializing = false)
        _navigationStack.value = listOf(DirectoryStackEntry(fileId = "0", label = "根目录"))
    }

    // endregion

    // region ==================== 目录加载 ====================

    fun loadDirectory(parentFileId: String) {
        loadDirectoryJob?.cancel()
        loadMoreJob?.cancel()
        loadingMore = false
        loadSequence++
        val seq = loadSequence
        updateUiState { it.copy(isLoading = true, error = null, currentFileId = parentFileId) }
        syncStackTop { it.copy(isLoading = true, error = null) }

        loadDirectoryJob = viewModelScope.launch {
            val result = apiClient.listFiles(
                parentFileId,
                page = 1,
                orderBy = _uiState.value.orderBy,
                orderDirection = _uiState.value.orderDirection
            )
            if (seq != loadSequence) return@launch

            result.fold(
                onSuccess = { listResult ->
                    cachedFileItems = listResult.items
                    val currentFileId = _navigationStack.value.lastOrNull()?.fileId ?: "0"
                    val fullPathLabel = _navigationStack.value.joinToString("/") { "${it.label}|${it.fileId}" }
                    listResult.items.forEach { file ->
                        if (file.isVideo) {
                            CloudPlaylistCache.putFileMetadata(
                                "pan123", file.fileId,
                                CloudPlaylistCache.FileMetadata(
                                    fileName = file.fileName,
                                    etag = file.etag,
                                    size = file.size,
                                    s3keyFlag = file.s3keyFlag,
                                    downloadUrl = file.downloadUrl,
                                    parentPath = "$currentFileId|$fullPathLabel",
                                )
                            )
                        }
                    }
                    val resources = listResult.items.map { fileToResource(it) }
                    directoryCache[parentFileId] = resources
                    CloudDirectoryCache.put(getApplication(), "pan123", parentFileId, resources)
                    val hasMore = listResult.items.size >= 100
                    updateUiState {
                        it.copy(
                            items = resources,
                            isLoading = false, hasMore = hasMore,
                            currentPage = 1
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
            val restoredPage = (cached.size + 99) / 100
            val hasMore = cached.size % 100 == 0 && cached.size > 0
            updateUiState {
                it.copy(
                    items = cached,
                    currentFileId = fileId,
                    isLoading = false,
                    isLoadingMore = false,
                    error = null,
                    hasMore = hasMore,
                    currentPage = restoredPage
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
        val nextPage = state.currentPage + 1
        loadingMore = true
        updateUiState { it.copy(isLoadingMore = true) }

        loadMoreJob = viewModelScope.launch {
            try {
                val result = apiClient.listFiles(
                    parentFileId = state.currentFileId,
                    page = nextPage,
                    orderBy = state.orderBy,
                    orderDirection = state.orderDirection
                )
                result.fold(
                    onSuccess = { listResult ->
                        cachedFileItems = cachedFileItems + listResult.items
                        val currentFileId = _navigationStack.value.lastOrNull()?.fileId ?: "0"
                        val fullPathLabel = _navigationStack.value.joinToString("/") { "${it.label}|${it.fileId}" }
                        listResult.items.forEach { file ->
                            if (file.isVideo) {
                                CloudPlaylistCache.putFileMetadata(
                                    "pan123", file.fileId,
                                    CloudPlaylistCache.FileMetadata(
                                        fileName = file.fileName,
                                        etag = file.etag,
                                        size = file.size,
                                        s3keyFlag = file.s3keyFlag,
                                        downloadUrl = file.downloadUrl,
                                        parentPath = "$currentFileId|$fullPathLabel",
                                    )
                                )
                            }
                        }
                        val newItems = listResult.items.map { fileToResource(it) }
                        val existingPaths = _uiState.value.items.map { it.path }.toSet()
                        val filtered = newItems.filter { it.path !in existingPaths }
                        if (filtered.isEmpty()) {
                            updateUiState { it.copy(isLoadingMore = false, hasMore = false) }
                            return@fold
                        }
                        val allItems = _uiState.value.items + filtered
                        val hasMore = listResult.items.size >= 100
                        directoryCache[state.currentFileId] = allItems
                        updateUiState {
                            it.copy(
                                items = allItems, isLoadingMore = false,
                                hasMore = hasMore, currentPage = nextPage
                            )
                        }
                        syncStackTop { it.copy(items = allItems, isLoading = false, error = null) }
                    },
                    onFailure = { e ->
                        if (e !is kotlinx.coroutines.CancellationException) {
                            updateUiState { it.copy(isLoadingMore = false, error = "加载更多失败: ${e.message}") }
                        }
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

    fun jumpToFolder(fileId: String, label: String) {
        val segments = label.split("/").filter { it.isNotBlank() }
        val pathCrumbs = segments.map { seg ->
            val parts = seg.split("|", limit = 2)
            Pan123Breadcrumb(parts[0], if (parts.size > 1) parts[1] else "")
        }
        if (pathCrumbs.isEmpty()) return
        updateUiState { it.copy(breadcrumbs = pathCrumbs) }
        _navigationStack.value = pathCrumbs.map { DirectoryStackEntry(fileId = it.fileId, label = it.label) }
        loadDirectoryCached(fileId)
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
        val fileItem = cachedFileItems.find { it.fileId == item.path } ?: return
        viewModelScope.launch {
            updateUiState { it.copy(isLoading = true) }
            val result = apiClient.trashFile(listOf(fileItem))
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
            it.copy(pendingAction = null, moveFileId = null,
                pickerFolders = emptyList(), pickerIsLoading = false)
        }
    }

    fun loadFoldersForPicker(folderId: String) {
        viewModelScope.launch {
            val state = _uiState.value
            updateUiState { it.copy(pickerIsLoading = true) }
            val actualFolderId = folderId.ifEmpty { "0" }
            val result = apiClient.listFiles(parentFileId = actualFolderId)
            result.fold(
                onSuccess = { listResult ->
                    val folders = listResult.items
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
            val actualParentId = parentFolderId.ifEmpty { "0" }
            val result = apiClient.createFolder(name = name, parentFileId = actualParentId)
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
        val actualTargetId = targetFolderId.ifEmpty { "0" }
        viewModelScope.launch {
            updateUiState { it.copy(isLoading = true) }
            val result = apiClient.moveFile(fileId = fileId, parentFileId = actualTargetId)
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

    suspend fun resolveImageUrl(item: WebDavResource): Pair<String, Map<String, String>>? {
        val fileItem = cachedFileItems.find { it.fileId == item.path }
        val url = fileItem?.downloadUrl?.ifBlank { null }
            ?: fileItem?.let { apiClient.getFileDownloadInfo(it).getOrNull()?.url }
            ?: return null
        return url to Pan123AuthProvider.getPlayHeaders()
    }

    // endregion

    // region ==================== 下载 ====================

    fun downloadFile(index: Int) {
        val res = _uiState.value.items.getOrNull(index) ?: return

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            try {
                val fileItem = cachedFileItems.find { it.fileId == res.path } ?: return@launch

                // 链式取下载链接：getFileDownloadInfo（完整请求体）→ 列表自带 downloadUrl
                val downloadUrl: String
                val infoResult = apiClient.getFileDownloadInfo(fileItem)
                downloadUrl = when {
                    infoResult.isSuccess && infoResult.getOrNull()!!.url.isNotBlank() ->
                        infoResult.getOrNull()!!.url
                    fileItem.downloadUrl.isNotBlank() ->
                        fileItem.downloadUrl
                    else -> {
                        Toast.makeText(getApplication(), "获取下载链接失败: 下载地址为空", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }

                _downloadProgress.value = DownloadProgressData(fileName = res.name, progress = 0f)

                val eventJob = launch {
                    cloudDownloadRepository.downloadEvents.collect { event ->
                        when (event) {
                            is CloudDownloadRepository.DownloadEvent.Progress -> {
                                if (event.fileName == res.name) {
                                    _downloadProgress.value = DownloadProgressData(
                                        fileName = event.fileName, progress = event.progress,
                                        downloadedBytes = event.downloadedBytes, totalBytes = event.totalBytes
                                    )
                                }
                            }
                            is CloudDownloadRepository.DownloadEvent.Completed -> {
                                if (event.fileName == res.name) {
                                    _downloadProgress.value = DownloadProgressData(
                                        fileName = event.fileName,
                                        progress = 1f,
                                        completedFilePath = event.filePath
                                    )
                                    Toast.makeText(getApplication(), "下载完成: ${event.fileName}", Toast.LENGTH_SHORT).show()
                                }
                            }
                            is CloudDownloadRepository.DownloadEvent.Failed -> {
                                if (event.fileName == res.name) {
                                    _downloadProgress.value = null
                                    if (event.error != "下载已取消") {
                                        Toast.makeText(getApplication(), "下载失败: ${event.error}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                }

                // 解析最终CDN下载地址并构建正确的下载Headers
                // JS流程: HEAD跟随重定向 → 提取ref参数 → AES解密 → 得到Referer
                val finalUrl = apiClient.resolveFinalDownloadUrl(downloadUrl)
                val downloadHeaders = apiClient.buildDownloadHeaders(finalUrl)
                Log.d(TAG, "下载: finalUrl=$finalUrl, headers=$downloadHeaders")

                cloudDownloadRepository.download(
                    url = finalUrl,
                    fileName = res.name,
                    headers = downloadHeaders,
                    provider = "pan123"
                )
                eventJob.cancel()
            } catch (e: Exception) {
                Toast.makeText(getApplication(), "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                _downloadProgress.value = null
            }
        }
    }

    fun dismissDownloadProgress() {
        _downloadProgress.value = null
        cloudDownloadRepository.cancel()
    }

    fun openDownloadedFile(filePath: String) {
        val context = getApplication<Application>()
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = try {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            } catch (e: IllegalArgumentException) {
                Uri.fromFile(file)
            }
            val intent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uri)
                type = PickerUtils.getMimeType(filePath)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "分享文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.e("Pan123VM", "打开文件失败", e)
            Toast.makeText(context, "无法打开文件: ${e.message}", Toast.LENGTH_SHORT).show()
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
            lastModified = file.createAt,
            thumbnailUrl = file.thumbnailUrl,
            folderSize = if (file.isDirectory) file.size else 0,
            category = pan123CategoryToLabel(file.category),
            createdAt = file.createAt
        )
    }

    private fun pan123CategoryToLabel(category: Int): String = when (category) {
        4 -> "doc"
        10 -> "archive"
        else -> ""
    }

    private fun buildBreadcrumbs(fileId: String, label: String?): List<Pan123Breadcrumb> {
        val crumbs = mutableListOf(Pan123Breadcrumb("根目录", "0"))
        if (fileId != "0" && label != null) crumbs.add(Pan123Breadcrumb(label, fileId))
        return crumbs
    }

    // endregion
}
