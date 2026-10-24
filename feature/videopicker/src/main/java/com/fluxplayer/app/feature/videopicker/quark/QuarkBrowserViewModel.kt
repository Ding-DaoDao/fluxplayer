package com.fluxplayer.app.feature.videopicker.quark

import kotlinx.coroutines.asContextElement
import com.fluxplayer.app.core.data.cloud.cloudPreferences
import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import com.fluxplayer.app.core.common.CloudPlayHeaders
import com.fluxplayer.app.core.common.CloudPlaylistCache
import com.fluxplayer.app.core.common.CloudUriScheme
import com.fluxplayer.app.core.common.FluxNotificationDelegate
import com.fluxplayer.app.core.common.PickerUtils
import com.fluxplayer.app.core.data.GlobalCookieJar
import com.fluxplayer.app.core.data.cloud.CloudUriResolver
import com.fluxplayer.app.core.data.quark.QuarkApiClient
import com.fluxplayer.app.core.data.quark.quarkPlaybackAuth
import com.fluxplayer.app.core.data.quark.QuarkFileItem
import com.fluxplayer.app.core.data.repository.CloudDownloadRepository
import com.fluxplayer.app.core.data.repository.PlaybackHistoryRepository
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.data.security.getSecret
import com.fluxplayer.app.core.data.security.putSecret
import com.fluxplayer.app.core.model.FluxMessageEvent
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.feature.videopicker.CloudDirectoryCache
import com.fluxplayer.app.feature.videopicker.DirectoryStackEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    /**
     * 夸克与 UC 现在是两个独立实例（各自固定来源），不再用 setDriveType 在同一个实例上切换。
     * 本 VM 每侧一个实例（BrowseTabs 用不同 key），setDriveType 只在来源变化时重建客户端。
     */
    var apiClient = QuarkApiClient(provider = "quark").apply { configureSession(application) }
        private set

    private fun ensureClient(provider: String) {
        if (apiClient.provider == provider) return
        apiClient = QuarkApiClient(provider = provider).apply { configureSession(getApplication()) }
    }
    private val itemCache = mutableMapOf<String, QuarkFileItem>()

    // endregion

    // region ==================== 状态 ====================

    private val _uiState = MutableStateFlow(QuarkBrowserUiState())
    val uiState: StateFlow<QuarkBrowserUiState> = _uiState.asStateFlow()

    private val _navigationStack = MutableStateFlow(
        listOf(DirectoryStackEntry(fileId = "0", label = "根目录")),
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
        val completedFilePath: String? = null,
    )

    private val _downloadProgress = MutableStateFlow<DownloadProgressData?>(null)
    val downloadProgress: StateFlow<DownloadProgressData?> = _downloadProgress.asStateFlow()

    // endregion

    // region ==================== 足迹 ====================

    private var accountRevisions = com.fluxplayer.app.core.data.cloud.CloudAccountStore.revisions.value

    init { observeHistory() }

    private fun observeHistory() {
        viewModelScope.launch {
            playbackHistoryRepository.getAllHistoryFlow().collect { history ->
                val latestPerDir = history
                    .filter { it.parentPath != null && com.fluxplayer.app.core.common.CloudAccountIdentity.isCurrentCloudUri(it.uriString) }
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
        val prefs = getApplication<Application>().cloudPreferences(driveType, Context.MODE_PRIVATE)
        val revision = com.fluxplayer.app.core.data.security.CloudSessionFence.advance(driveType)
        val candidate = QuarkApiClient(provider = driveType).apply { configureSession(getApplication()) }
        val tv = if (driveType == "uc") com.fluxplayer.app.core.data.quark.UcTvService(getApplication()) else null
        updateUiState { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val result = candidate.validateCookie(cookie)
            if (com.fluxplayer.app.core.data.security.CloudSessionFence.current(driveType) != revision) return@launch
            result.fold(
                onSuccess = { verified ->
                    runCatching {
                        check(com.fluxplayer.app.core.data.security.CloudSessionFence.commit(driveType, revision) {
                            tv?.acceptWebCookie(verified)
                            check(prefs.edit().putSecret(driveType, "cookie", verified).putLong("cookie_refreshed_at", System.currentTimeMillis()).commit()) { "保存登录信息失败" }
                            candidate.setCookie(verified)
                            apiClient = candidate
                            val headers = candidate.featureHeaders()
                            CloudPlayHeaders.registerSuffixFor(driveType, ".quark.cn", headers)
                            if (driveType == "uc") CloudPlayHeaders.registerSuffixFor("uc", ".uc.cn", headers)
                        }) { "网盘账号已变化，请重新登录" }
                    }.onSuccess {
                        updateUiState { it.copy(isLoggedIn = true, driveType = driveType) }
                        loadDirectory("0")
                    }.onFailure { error -> updateUiState { it.copy(isLoading = false, error = error.message) } }
                },
                onFailure = { error -> updateUiState { it.copy(isLoading = false, error = error.message) } },
            )
        }
    }

    // endregion

    // region ==================== 登录 — setDriveType（UC/普通模式切换） ====================

    fun setDriveType(type: String) {
        // driveType 未变且已登录，跳过（避免 tab 切换时重载根目录覆盖导航栈）
        if (_uiState.value.driveType == type && _uiState.value.isLoggedIn) return
        val isSwitching = _uiState.value.driveType != type
        Log.d(TAG, "setDriveType: type=$type, isSwitching=$isSwitching")
        ensureClient(type)
        val prefName = if (type == "uc") UC_PREF_NAME else PREF_NAME
        val prefs = getApplication<Application>().cloudPreferences(prefName, Context.MODE_PRIVATE)
        val cookie = prefs.getSecret(prefName, "cookie").orEmpty()
        // 切换 driveType 时重置状态，防止夸克/UC 之间数据泄漏
        if (isSwitching) {
            directoryCache.clear()
            _navigationStack.value = listOf(DirectoryStackEntry(fileId = "0", label = "根目录"))
        }
        if (cookie.isNotBlank()) {
            apiClient.setCookie(cookie)
            // 注册缩略图 headers：只写本来源状态，用本次账号的头快照
            val auth = quarkPlaybackAuth(type)
            auth.cookie = cookie
            auth.referer = if (type == "uc") "https://drive.uc.cn/" else "https://drive.quark.cn/"
            auth.isActive = true
            val headersSnapshot = apiClient.featureHeaders()
            CloudPlayHeaders.registerSuffixFor(type, ".quark.cn", headersSnapshot)
            if (type == "uc") {
                CloudPlayHeaders.registerSuffixFor("uc", ".uc.cn", headersSnapshot)
            }
            updateUiState { it.copy(isLoggedIn = true, driveType = type, items = if (isSwitching) emptyList() else it.items, error = null, isLoading = true) }
            syncStackTop { it.copy(isLoading = true, error = null) }
            // 从磁盘缓存预填根目录，加速子目录导航
            CloudDirectoryCache.get(getApplication(), type, "0")?.let { directoryCache["0"] = it }
            loadDirectory("0")
        } else {
            updateUiState { it.copy(isLoggedIn = false, driveType = type, items = if (isSwitching) emptyList() else it.items, error = null, isLoading = false) }
        }
    }

    // endregion

    // region ==================== 登出 ====================

    fun reloadAccount() {
        val provider = _uiState.value.driveType
        val latest = com.fluxplayer.app.core.data.cloud.CloudAccountStore.revisions.value
        if ((latest[provider] ?: 0L) == (accountRevisions[provider] ?: 0L)) return
        accountRevisions = latest
        viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.children?.filter { it !== downloadJob }?.forEach { it.cancel() }
        resetAccount(true)
        apiClient = QuarkApiClient(provider = provider).apply { configureSession(getApplication()) }
        if (!com.fluxplayer.app.core.data.cloud.CloudAccountStore.consumeLogin(provider)) setDriveType(provider)
        observeHistory()
        observeAccountChanges()
    }

    fun logout() = resetAccount(false)

    private fun resetAccount(keepCredentials: Boolean) {
        val driveType = _uiState.value.driveType
        val isUC = driveType == "uc"

        // 只清除当前云盘相关的 GlobalCookieJar、prefs、缓存
        if (!keepCredentials && isUC) {
            GlobalCookieJar.clearHost("pc-api.uc.cn")
            GlobalCookieJar.clearHost("drive.uc.cn")
        } else if (!keepCredentials) {
            GlobalCookieJar.clearHost("drive.quark.cn")
        }

        val prefName = if (isUC) UC_PREF_NAME else PREF_NAME
        val prefs = getApplication<Application>().cloudPreferences(prefName, Context.MODE_PRIVATE)
        if (!keepCredentials) prefs.edit().clear().apply()
        if (!keepCredentials) apiClient.clearCookie()
        // 只清本来源的播放鉴权，另一家仍在登录时不受影响
        if (!keepCredentials) quarkPlaybackAuth(driveType).clear()
        directoryCache.clear()
        CloudDirectoryCache.clear(getApplication(), driveType)

        // 清除 WebView 痕迹
        try {
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
            android.webkit.WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {}
        _uiState.value = QuarkBrowserUiState(driveType = driveType)
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
                                ),
                            )
                        }
                    }
                    val resources = items.map { fileToResource(it) }
                    directoryCache[parentFileId] = resources
                    CloudDirectoryCache.put(getApplication(), provider, parentFileId, resources)
                    updateUiState {
                        it.copy(
                            items = resources,
                            isLoading = false, hasMore = items.size >= apiClient.pageSize, currentPage = 1,
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
                },
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
                    currentPage = (cached.size + apiClient.pageSize - 1) / apiClient.pageSize,
                    hasMore = cached.size >= apiClient.pageSize,
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
                    orderBy = state.orderBy,
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
                                hasMore = items.size >= apiClient.pageSize,
                                currentPage = nextPage,
                            )
                        }
                        syncStackTop { it.copy(items = _uiState.value.items, isLoading = false, error = null) }
                    },
                    onFailure = { e ->
                        updateUiState { it.copy(isLoadingMore = false, error = "加载更多失败: ${e.message}") }
                    },
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
                scrollTargetParentKey = parentKey,
            )
        }
        _navigationStack.update { it + DirectoryStackEntry(fileId = item.path, label = item.name) }
        loadDirectoryCached(item.path)
    }

    fun clearScrollTarget() {
        updateUiState { it.copy(scrollTargetIndex = -1) }
    }

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

    fun refresh() {
        loadDirectory(_uiState.value.currentFileId)
    }

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
                },
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
                },
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
                },
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
                },
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
                },
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
                },
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
        return url to apiClient.featureHeaders()
    }

    // endregion

    // region ==================== 下载 ====================

    fun downloadFile(index: Int) {
        val res = _uiState.value.items.getOrNull(index) ?: return
        val provider = if (_uiState.value.driveType == "uc") "uc" else "quark"

        val downloadOwner = com.fluxplayer.app.core.common.CloudAccountIdentity.active(provider)
        val downloadClient = apiClient
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch(com.fluxplayer.app.core.common.CloudAccountIdentity.bindings.asContextElement(mapOf(provider to downloadOwner))) {
            val downloadContext = kotlinx.coroutines.currentCoroutineContext()
            try {
                val tv = com.fluxplayer.app.core.data.quark.UcTvService(getApplication())
                val useTv = provider == "uc" && tv.isAuthorized()
                val urlResult = if (useTv) runCatching { tv.download(res.path, res.size) } else downloadClient.getDownloadUrl(res.path)
                urlResult.fold(
                    onSuccess = { url ->
                        downloadContext.ensureActive()
                        _downloadProgress.value = DownloadProgressData(fileName = res.name, progress = 0f)

                        val completed = cloudDownloadRepository.download(
                            url = url,
                            fileName = res.name,
                            // 用本账号客户端的头，而不是可能被另一家覆盖的全局值
                            headers = if (useTv) emptyMap() else downloadClient.featureHeaders(),
                            provider = provider,
                            onProgress = { event ->
                                downloadContext.ensureActive()
                                _downloadProgress.value = DownloadProgressData(
                                    fileName = event.fileName, progress = event.progress,
                                    downloadedBytes = event.downloadedBytes, totalBytes = event.totalBytes,
                                )
                            },
                        )
                        downloadContext.ensureActive()
                        _downloadProgress.value = DownloadProgressData(
                            fileName = completed.fileName, progress = 1f,
                            downloadedBytes = completed.size, totalBytes = completed.size,
                            completedFilePath = completed.filePath,
                        )
                        downloadContext.ensureActive()
                        notifier.success("下载完成: ${completed.fileName}")
                    },
                    onFailure = { e ->
                        downloadContext.ensureActive()
                        notifier.error("获取下载链接失败: ${e.message}")
                    },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                downloadContext.ensureActive()
                notifier.error("下载失败: ${e.message}")
                downloadContext.ensureActive()
                _downloadProgress.value = null
            }
        }
    }

    fun dismissDownloadProgress() {
        _downloadProgress.value = null
        downloadJob?.cancel()
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
            createdAt = if (file.createdAt > 0) file.createdAt.toString() else "",
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
    // 账号在设置恢复或隐藏标签页期间变化时，也要刷新存活的浏览器实例。
    private fun observeAccountChanges() {
        viewModelScope.launch {
            com.fluxplayer.app.core.data.cloud.CloudAccountStore.revisions.collect { latest ->
                val provider = _uiState.value.driveType
                if ((latest[provider] ?: 0L) != (accountRevisions[provider] ?: 0L)) reloadAccount()
            }
        }
    }

    init { observeAccountChanges() }

}
