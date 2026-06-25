package com.fluxplayer.app.feature.videopicker.quark

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import com.fluxplayer.app.core.common.FluxNotificationDelegate
import com.fluxplayer.app.core.model.FluxMessageEvent
import kotlinx.coroutines.flow.SharedFlow
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.fluxplayer.app.core.common.CloudPlayHeaders
import com.fluxplayer.app.core.common.CloudPlaylistCache
import com.fluxplayer.app.core.common.CloudUriScheme
import com.fluxplayer.app.core.common.PickerUtils
import com.fluxplayer.app.core.data.GlobalCookieJar
import com.fluxplayer.app.core.data.cloud.CloudUriResolver
import com.fluxplayer.app.core.data.quark.QuarkApiClient
import com.fluxplayer.app.core.data.quark.QuarkAuthProvider
import com.fluxplayer.app.core.data.quark.QuarkFileItem
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

data class QuarkBreadcrumb(val label: String, val fileId: String)

@HiltViewModel
class QuarkBrowserViewModel @Inject constructor(
    application: Application,
    private val preferencesRepository: PreferencesRepository,
    private val cloudUriResolver: CloudUriResolver,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val cloudDownloadRepository: CloudDownloadRepository,
) : androidx.lifecycle.AndroidViewModel(application) {
    // region ==================== 统一通知 ====================

    /** 通知事件委托 */
    val notifier = FluxNotificationDelegate(viewModelScope)

    /** 供 UI 层收集的通知事件流 */
    val messageEvents: SharedFlow<FluxMessageEvent> = notifier.events

    // endregion


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
                    .values.flatMap { item ->
                        val uri = item.uriString
                        val parts = mutableListOf(uri)
                        if (uri.startsWith("cloud://")) {
                            val baseUri = uri.substringBefore("?")
                            if (baseUri != uri) parts.add(baseUri)
                        }
                        parts
                    }.toSet()
                _uiState.update { it.copy(playedUriSet = latestPerDir) }
            }
        }
    }

    // endregion

    // region ==================== 登录 — loginWithCookie（含 driveType 和 cookie 验证） ====================

    fun loginWithCookie(cookie: String, driveType: String = "quark") {
        Log.d(TAG, "loginWithCookie: driveType=$driveType, cookie长度=${cookie.length}")
        val prefName = if (driveType == "uc") UC_PREF_NAME else PREF_NAME
        val prefs = getApplication<Application>().getSharedPreferences(prefName, Context.MODE_PRIVATE)
        prefs.edit().putString("cookie", cookie).apply()

        apiClient.setCookie(cookie)
        setupCookiePersistence(prefName)

        // 注册缩略图 headers（浏览时 Coil 需要）
        QuarkAuthProvider.cookie = cookie
        QuarkAuthProvider.referer = if (driveType == "uc") "https://drive.uc.cn/" else "https://drive.quark.cn/"
        QuarkAuthProvider.isActive = true
        if (driveType == "uc") {
            CloudPlayHeaders.registerSuffix(".uc.cn") { QuarkAuthProvider.getPlayHeaders() }
        } else {
            CloudPlayHeaders.registerSuffix(".quark.cn") { QuarkAuthProvider.getPlayHeaders() }
        }

        updateUiState { it.copy(isLoggedIn = true, driveType = driveType) }
        Log.d(TAG, "loginWithCookie: 开始加载根目录")
        loadDirectory("0")
    }

    // endregion

    // region ==================== 登录 — setDriveType（UC/普通模式切换） ====================

    fun setDriveType(type: String) {
        // driveType 未变且已登录，跳过（避免 tab 切换时重载根目录覆盖导航栈）
        if (_uiState.value.driveType == type && _uiState.value.isLoggedIn) return
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
            setupCookiePersistence(prefName)
            // 注册缩略图 headers
            QuarkAuthProvider.cookie = cookie
            QuarkAuthProvider.referer = if (type == "uc") "https://drive.uc.cn/" else "https://drive.quark.cn/"
            QuarkAuthProvider.isActive = true
            if (type == "uc") {
                CloudPlayHeaders.registerSuffix(".uc.cn") { QuarkAuthProvider.getPlayHeaders() }
            } else {
                CloudPlayHeaders.registerSuffix(".quark.cn") { QuarkAuthProvider.getPlayHeaders() }
            }
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
        apiClient.clearCookie()

        // 清除 OkHttp GlobalCookieJar 中 Quark/UC 的 Cookie
        GlobalCookieJar.clearHost("drive.quark.cn")
        GlobalCookieJar.clearHost("pc-api.uc.cn")
        GlobalCookieJar.clearHost("drive.uc.cn")

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
        loadMoreJob?.cancel()
        loadingMore = false
        loadSequence++
        val seq = loadSequence
        updateUiState { it.copy(isLoading = true, error = null, currentFileId = parentFileId) }
        syncStackTop { it.copy(isLoading = true, error = null) }

        loadDirectoryJob = viewModelScope.launch {
            val result = apiClient.listFiles(parentFileId, orderBy = _uiState.value.orderBy)
            if (seq != loadSequence) return@launch

            result.fold(
                onSuccess = { items ->
                    Log.d(TAG, "loadDirectory SUCCESS: ${items.size}个文件")
                    items.forEach { itemCache[it.fid] = it }
                    // 缓存文件元数据到 CloudPlaylistCache，供播放器显示标题
                    val provider = if (_uiState.value.driveType == "uc") "uc" else "quark"
                    val currentFileId = _navigationStack.value.lastOrNull()?.fileId ?: "0"
                    val fullPathLabel = _navigationStack.value.joinToString("/") { "${it.label}|${it.fileId}" }
                    items.forEach { file ->
                        if (file.isVideo) {
                            CloudPlaylistCache.putFileMetadata(
                                provider, file.fid,
                                CloudPlaylistCache.FileMetadata(
                                    fileName = file.fileName,
                                    parentPath = "$currentFileId|$fullPathLabel",
                                )
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
            loadMoreJob?.cancel()
            loadingMore = false
            loadSequence++
            updateUiState {
                it.copy(
                    items = cached,
                    currentFileId = fileId,
                    isLoading = false,
                    isLoadingMore = false,
                    error = null,
                    currentPage = (cached.size + 99) / 100,
                    hasMore = cached.size >= 100
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

        loadMoreJob = viewModelScope.launch {
            try {
                val result = apiClient.listFiles(
                    pdirFid = state.currentFileId,
                    page = nextPage,
                    orderBy = state.orderBy
                )
                result.fold(
                    onSuccess = { items ->
                        items.forEach { itemCache[it.fid] = it }
                        val newItems = items.map { fileToResource(it) }
                        val existingPaths = _uiState.value.items.map { it.path }.toSet()
                        val filtered = newItems.filter { it.path !in existingPaths }
                        if (filtered.isEmpty()) {
                            updateUiState { it.copy(isLoadingMore = false, hasMore = false) }
                            return@fold
                        }
                        updateUiState {
                            it.copy(
                                items = _uiState.value.items + filtered,
                                isLoadingMore = false,
                                hasMore = items.size >= 100,
                                currentPage = nextPage
                            )
                        }
                        syncStackTop { it.copy(items = _uiState.value.items, isLoading = false, error = null) }
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
        val segments = label.split("/").filter { it.isNotBlank() }
        val pathCrumbs = segments.map { seg ->
            val parts = seg.split("|", limit = 2)
            QuarkBreadcrumb(parts[0], if (parts.size > 1) parts[1] else "")
        }
        if (pathCrumbs.isEmpty()) return
        updateUiState { it.copy(breadcrumbs = pathCrumbs) }
        _navigationStack.value = pathCrumbs.map { DirectoryStackEntry(fileId = it.fileId, label = it.label) }
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
                    notifier.success("文件夹创建成功")
                    refresh()
                },
                onFailure = { e ->
                    notifier.error("创建失败: ${e.message}")
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
                    notifier.success("删除成功")
                    refresh()
                },
                onFailure = { e ->
                    notifier.error("删除失败: ${e.message}")
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
                    notifier.success("重命名成功")
                    refresh()
                },
                onFailure = { e ->
                    notifier.error("重命名失败: ${e.message}")
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
                    notifier.error("加载文件夹失败: ${e.message}")
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
                    notifier.success("文件夹创建成功")
                    loadFoldersForPicker(parentFolderId)
                },
                onFailure = { e ->
                    notifier.error("创建失败: ${e.message}")
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
            val result = apiClient.moveFiles(listOf(fileId), actualTargetId)
            result.fold(
                onSuccess = {
                    notifier.success("移动成功")
                    dismissPicker()
                    refresh()
                },
                onFailure = { e ->
                    notifier.error("移动失败: ${e.message}")
                    updateUiState { it.copy(isLoading = false) }
                }
            )
        }
    }

    // endregion

    // region ==================== 视频播放 ====================

    suspend fun resolveVideoUri(item: WebDavResource): android.net.Uri? {
        val driveLabel = if (uiState.value.driveType == "uc") "uc" else "quark"
        return cloudUriResolver.resolve(CloudUriScheme.buildCloudUri(driveLabel, item.path))
    }

    suspend fun resolveImageUrl(item: WebDavResource): Pair<String, Map<String, String>>? {
        val url = apiClient.getDownloadUrl(item.path).getOrNull() ?: return null
        return url to QuarkAuthProvider.getPlayHeaders()
    }

    // endregion

    // region ==================== 下载 ====================

    fun downloadFile(index: Int) {
        val res = _uiState.value.items.getOrNull(index) ?: return
        val provider = if (_uiState.value.driveType == "uc") "uc" else "quark"

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            try {
                val urlResult = apiClient.getDownloadUrl(res.path)
                urlResult.fold(
                    onSuccess = { url ->
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
                                            notifier.success("下载完成: ${event.fileName}")
                                        }
                                    }
                                    is CloudDownloadRepository.DownloadEvent.Failed -> {
                                        if (event.fileName == res.name) {
                                            _downloadProgress.value = null
                                            if (event.error != "下载已取消") {
                                                notifier.error("下载失败: ${event.error}")
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        cloudDownloadRepository.download(
                            url = url,
                            fileName = res.name,
                            headers = QuarkAuthProvider.getPlayHeaders(),
                            provider = provider
                        )
                        eventJob.cancel()
                    },
                    onFailure = { e ->
                        notifier.error("获取下载链接失败: ${e.message}")
                    }
                )
            } catch (e: Exception) {
                notifier.error("下载失败: ${e.message}")
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
            notifier.info("文件不存在")
            return
        }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uri)
                type = PickerUtils.getMimeType(filePath)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "分享文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.e("QuarkVM", "分享文件失败", e)
            notifier.info("无法打开文件: ${e.message}")
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
            lastModified = if (file.updatedAt > 0) file.updatedAt.toString() else "",
            thumbnailUrl = file.thumbnail.ifBlank { null },
            fileCount = if (file.dir && file.includeItems > 0) file.includeItems else null,
            folderSize = if (file.dir) file.size else 0,
            category = file.objCategory,
            createdAt = if (file.createdAt > 0) file.createdAt.toString() else ""
        )
    }

    private fun buildBreadcrumbs(fileId: String, label: String?): List<QuarkBreadcrumb> {
        val crumbs = mutableListOf(QuarkBreadcrumb("根目录", "0"))
        if (fileId != "0" && label != null) {
            crumbs.add(QuarkBreadcrumb(label, fileId))
        }
        return crumbs
    }

    /**
     * 每次 API 响应可能携带 Set-Cookie 更新合并后的 cookie，
     * 通过此回调实时写回 SharedPreferences，确保 CloudUriResolver 后续能读到最新 cookie。
     */
    private fun setupCookiePersistence(prefName: String) {
        apiClient.onCookieUpdated = { mergedCookie ->
            val prefs = getApplication<Application>()
                .getSharedPreferences(prefName, Context.MODE_PRIVATE)
            prefs.edit().putString("cookie", mergedCookie).apply()
        }
    }

    // endregion
}
