package com.fluxplayer.app.feature.videopicker.aliyun

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
import com.fluxplayer.app.core.data.aliyun.AliyunApiClient
import com.fluxplayer.app.core.data.cloud.CloudUriResolver
import com.fluxplayer.app.core.data.aliyun.AliyunAuthProvider
import com.fluxplayer.app.core.data.aliyun.AliyunTokenExpiredException
import com.fluxplayer.app.core.data.repository.CloudDownloadRepository
import com.fluxplayer.app.core.data.repository.PlaybackHistoryRepository
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.model.WebDavResource
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.fluxplayer.app.feature.videopicker.CloudDirectoryCache
import com.fluxplayer.app.feature.videopicker.DirectoryStackEntry
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

data class AliyunBreadcrumb(val label: String, val fileId: String)

@HiltViewModel
class AliyunBrowserViewModel @Inject constructor(
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
        private const val PREF_NAME = "alipan"
    }

    // region ==================== API Client & Auth ====================

    val apiClient = AliyunApiClient()
    val authProvider = AliyunAuthProvider

    // endregion

    // region ==================== 状态 ====================

    private val _uiState = MutableStateFlow(AliyunBrowserUiState())
    val uiState: StateFlow<AliyunBrowserUiState> = _uiState.asStateFlow()

    private val _navigationStack = MutableStateFlow(
        listOf(DirectoryStackEntry(fileId = "root", label = "根目录"))
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
                _uiState.update { it.copy(playedUriSet = latestPerDir) }
            }
        }
    }

    // endregion

    // region ==================== 登录 — loginWithAuthorization ====================

    fun loginWithAuthorization(auth: String, defaultDriveId: String = "", refreshToken: String = "") {
        apiClient.authorization = auth
        AliyunAuthProvider.authorization = auth
        AliyunAuthProvider.isActive = true
        CloudPlayHeaders.registerSuffix(".alipan.com") { AliyunAuthProvider.getPlayHeaders() }
        CloudPlayHeaders.registerSuffix("aliyundrive.net") { AliyunAuthProvider.getPlayHeaders() }
        if (defaultDriveId.isNotBlank()) apiClient.driveId = defaultDriveId

        updateUiState { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            val result = withTimeoutOrNull(20_000L) { apiClient.getUserDriveInfo() }
            when {
                result == null -> {
                    updateUiState { it.copy(isLoggedIn = false, isLoading = false, error = "连接超时，请检查网络后重试") }
                }
                result.isSuccess -> {
                    val driveInfo = result.getOrNull()
                    val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    val driveId = defaultDriveId.ifEmpty { driveInfo?.let { resolveDefaultDrive(it) } ?: "" }
                    apiClient.driveId = driveId
                    prefs.edit()
                        .putString("authorization", auth)
                        .putString("drive_id", driveId)
                        .putString("refresh_token", refreshToken)
                        .putString("device_id", apiClient.getDeviceId())
                        .putString("signature", apiClient.getSignature())
                        .apply()
                    // 将 WebView Cookie 持久化到 SharedPreferences，供备份系统读取
                    try {
                        val cookieManager = android.webkit.CookieManager.getInstance()
                        val cookies = cookieManager.getCookie("https://www.alipan.com")
                        if (!cookies.isNullOrBlank()) {
                            prefs.edit().putString("cookies", cookies).apply()
                        }
                    } catch (_: Exception) {}

                    val driveOptions = if (driveInfo != null) buildDriveOptionsFromInfo(driveInfo)
                        else emptyList()

                    updateUiState {
                        it.copy(
                            breadcrumbs = listOf(AliyunBreadcrumb("我的云盘", "root")),
                            isLoggedIn = true,
                            isLoading = false,
                            driveOptions = driveOptions,
                            currentDriveId = driveId
                        )
                    }
                    refreshDriveInfo()
                    loadDirectory("root")
                }
                else -> {
                    val errMsg = result.exceptionOrNull()?.message ?: "未知错误"
                    val friendly = when {
                        errMsg.contains("AccessTokenInvalid", ignoreCase = true) -> "token已失效，请重新登录"
                        errMsg.contains("Failed to connect", ignoreCase = true) ||
                        errMsg.contains("Unable to resolve", ignoreCase = true) -> "无法连接到阿里云盘服务"
                        else -> "token验证失败: $errMsg"
                    }
                    updateUiState { it.copy(isLoggedIn = false, isLoading = false, error = friendly) }
                }
            }
        }
    }

    // endregion

    // region ==================== 登录 — loginWithTokenJson（完整JSON解析） ====================

    fun loginWithTokenJson(tokenJsonStr: String) {
        try {
            val json = JSONObject(tokenJsonStr)
            val accessToken = json.optString("access_token", "")
            if (accessToken.isBlank()) {
                updateUiState { it.copy(error = "未能获取access_token，请返回重试") }
                return
            }
            val tokenType = json.optString("token_type", "Bearer")
            val auth = "$tokenType $accessToken"
            val refreshToken = json.optString("refresh_token", "")
            var deviceId = json.optString("device_id", "")
            if (deviceId.isBlank()) deviceId = apiClient.getDeviceId()
            var signature = json.optString("x_signature", "")
            if (signature.isBlank()) signature = apiClient.getSignature()

            // 设置额外参数
            if (deviceId.isNotBlank()) apiClient.setDeviceId(deviceId)
            if (signature.isNotBlank()) apiClient.setSignature(signature)

            // 不传 defaultDriveId，由 loginWithAuthorization 从 getUserDriveInfo 解析（首次默认资源盘）
            loginWithAuthorization(auth, defaultDriveId = "", refreshToken)
        } catch (_: Exception) {
            updateUiState { it.copy(error = "登录信息解析失败，请重试") }
        }
    }

    // endregion

    // region ==================== 登录 — tryRestoreSession ====================

    fun tryRestoreSession() {
        if (_uiState.value.isLoggedIn) return

        val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val auth = prefs.getString("authorization", "") ?: ""
        if (auth.isBlank()) {
            updateUiState { it.copy(isLoggedIn = false) }
            return
        }

        apiClient.authorization = auth
        val savedDriveId = prefs.getString("drive_id", "") ?: ""
        if (savedDriveId.isNotBlank()) {
            apiClient.driveId = savedDriveId
        }
        val savedDeviceId = prefs.getString("device_id", "") ?: ""
        if (savedDeviceId.isNotBlank()) {
            apiClient.setDeviceId(savedDeviceId)
        }
        val savedSignature = prefs.getString("signature", "") ?: ""
        if (savedSignature.isNotBlank()) {
            apiClient.setSignature(savedSignature)
        }

        AliyunAuthProvider.authorization = auth
        AliyunAuthProvider.isActive = true
        CloudPlayHeaders.registerSuffix(".alipan.com") { AliyunAuthProvider.getPlayHeaders() }
        CloudPlayHeaders.registerSuffix("aliyundrive.net") { AliyunAuthProvider.getPlayHeaders() }

        // 恢复 WebView Cookie（备份恢复时 CookieManager 可能为空，从 SharedPreferences 补回）
        val savedCookies = prefs.getString("cookies", null)
        if (!savedCookies.isNullOrBlank()) {
            try {
                val cookieManager = android.webkit.CookieManager.getInstance()
                val existing = cookieManager.getCookie("https://www.alipan.com")
                if (existing.isNullOrBlank()) {
                    cookieManager.setCookie("https://www.alipan.com", savedCookies)
                    cookieManager.flush()
                }
            } catch (_: Exception) {}
        }

        updateUiState { it.copy(isLoggedIn = true, isLoading = true) }
        syncStackTop { it.copy(isLoading = true, error = null) }

        // 先验证 token 有效性，过期则通过 WebView 自动续期
        viewModelScope.launch {
            val verifyResult = withTimeoutOrNull(15_000L) { apiClient.verifyToken() }
            when {
                verifyResult == null -> {
                    // 网络超时，保留登录状态，让用户手动重试
                    updateUiState { it.copy(isLoading = false, error = "连接超时，请检查网络后重试") }
                    syncStackTop { it.copy(isLoading = false, error = "连接超时，请检查网络后重试") }
                }
                verifyResult.isSuccess -> {
                    // Token 有效，正常加载
                    CloudDirectoryCache.get(getApplication(), "alipan", "root")?.let { directoryCache["root"] = it }
                    refreshDriveInfo()
                    loadDirectory("root")
                }
                verifyResult.isFailure -> {
                    val e = verifyResult.exceptionOrNull()
                    if (e is AliyunTokenExpiredException) {
                        // Token 过期 → 触发 WebView 自动续期（不清除 Cookie，保留 WebView 会话）
                        triggerReLogin()
                    } else {
                        val msg = e?.message ?: "未知错误"
                        updateUiState { it.copy(isLoading = false, error = "验证失败: $msg") }
                        syncStackTop { it.copy(isLoading = false, error = "验证失败: $msg") }
                    }
                }
            }
        }
    }

    // endregion

    // region ==================== 登出 ====================

    fun logout() {
        AliyunAuthProvider.clear()

        // 清除 OkHttp GlobalCookieJar 中旧账号的 Cookie
        GlobalCookieJar.clearHost("api.alipan.com")
        GlobalCookieJar.clearHost("www.alipan.com")

        // 重置 ApiClient 自身字段
        apiClient.authorization = ""
        apiClient.driveId = ""

        val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        directoryCache.clear()
        CloudDirectoryCache.clear(getApplication(), "alipan")
        // 清除 WebView 痕迹（cookie、localStorage、缓存等）
        try {
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
            android.webkit.WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {}
        _uiState.value = AliyunBrowserUiState()
        _navigationStack.value = listOf(DirectoryStackEntry(fileId = "root", label = "根目录"))
    }

    // endregion

    // region ==================== 驱动信息刷新 ====================

    private suspend fun refreshDriveInfo() {
        val result = apiClient.getUserDriveInfo().getOrNull() ?: return
        val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedDriveId = prefs.getString("drive_id", "")
        val driveId = if (savedDriveId.isNullOrBlank()) resolveDefaultDrive(result) else savedDriveId
        apiClient.driveId = driveId
        prefs.edit().putString("drive_id", driveId).apply()
        updateUiState { it.copy(currentDriveId = driveId) }
        // 同步驱动选项，只包含备份盘和资源盘
        updateDriveOptions(result)
    }

    private fun buildDriveOptionsFromInfo(info: com.fluxplayer.app.core.data.aliyun.AliyunDriveInfo): List<DriveOption> {
        val options = mutableListOf<DriveOption>()
        // 默认盘（我的云盘）不放入下拉选项，面包屑已显示
        if (info.backupDriveId.isNotBlank()) {
            options.add(DriveOption("备份盘", info.backupDriveId))
        }
        if (info.resourceDriveId.isNotBlank()) {
            options.add(DriveOption("资源盘", info.resourceDriveId))
        }
        return options
    }

    private fun resolveDefaultDrive(info: com.fluxplayer.app.core.data.aliyun.AliyunDriveInfo): String {
        // 默认选中资源盘
        return info.resourceDriveId.ifEmpty { info.backupDriveId.ifEmpty { info.defaultDriveId } }
    }

    private fun updateDriveOptions(info: com.fluxplayer.app.core.data.aliyun.AliyunDriveInfo) {
        val options = buildDriveOptionsFromInfo(info)
        if (options.isNotEmpty()) {
            updateUiState { it.copy(driveOptions = options) }
        }
    }

    // endregion

    // region ==================== 切换驱动 ====================

    fun switchDrive(driveId: String) {
        apiClient.driveId = driveId
        val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("drive_id", driveId).apply()
        updateUiState { it.copy(currentDriveId = driveId) }
        loadDirectory("root")
    }

    // endregion

    // region ==================== 重新登录触发 ====================

    fun triggerReLogin() {
        val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        AliyunAuthProvider.clear()
        directoryCache.clear()
        // 不清理 Cookie/WebStorage！保留 WebView 中的登录会话，
        // 以便 AliyunLoginScreen 在 autoOpenWebView 模式下自动跳转到 /drive 提取新 token
        updateUiState {
            AliyunBrowserUiState(
                isLoggedIn = false,
                reLoginRequired = true,
                error = "登录已过期，正在尝试自动续期..."
            )
        }
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
                orderBy = _uiState.value.orderBy.substringBefore(":"),
                orderDirection = _uiState.value.orderBy.substringAfter(":", "ASC")
            )
            if (seq != loadSequence) return@launch

            result.fold(
                onSuccess = { listResult ->
                    // 缓存文件元数据到 CloudPlaylistCache，供播放器显示标题
                    val currentFileId = _navigationStack.value.lastOrNull()?.fileId ?: "root"
                    val fullPathLabel = _navigationStack.value.joinToString("/") { "${it.label}|${it.fileId}" }
                    listResult.items.forEach { file ->
                        if (file.category == "video") {
                            CloudPlaylistCache.putFileMetadata(
                                "alipan", file.fileId,
                                CloudPlaylistCache.FileMetadata(
                                    fileName = file.fileName,
                                    parentPath = "$currentFileId|$fullPathLabel",
                                )
                            )
                        }
                    }
                    val resources = listResult.items.map { fileToResource(it) }
                    directoryCache[parentFileId] = resources
                    CloudDirectoryCache.put(getApplication(), "alipan", parentFileId, resources)
                    updateUiState {
                        it.copy(
                            items = resources,
                            isLoading = false,
                            nextMarker = listResult.nextMarker.ifEmpty { null }
                        )
                    }
                    syncStackTop { it.copy(items = resources, isLoading = false, error = null) }
                },
                onFailure = { e ->
                    val friendly = when (e) {
                        is AliyunTokenExpiredException -> {
                            triggerReLogin()
                            return@fold
                        }
                        else -> {
                            val msg = e.message ?: "未知错误"
                            when {
                                msg.contains("Failed to connect", ignoreCase = true) ||
                                msg.contains("Unable to resolve", ignoreCase = true) ->
                                    "无法连接到阿里云盘服务"
                                else -> "加载失败: $msg"
                            }
                        }
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
            updateUiState {
                it.copy(
                    items = cached,
                    currentFileId = fileId,
                    isLoading = false,
                    isLoadingMore = false,
                    error = null,
                    nextMarker = null
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
        if (state.nextMarker == null || loadingMore || state.isLoading) return
        loadingMore = true
        updateUiState { it.copy(isLoadingMore = true) }

        loadMoreJob = viewModelScope.launch {
            try {
                val result = apiClient.listFiles(
                    parentFileId = state.currentFileId,
                    nextMarker = state.nextMarker
                )
                result.fold(
                    onSuccess = { listResult ->
                        val newItems = listResult.items.map { fileToResource(it) }
                        val existingPaths = _uiState.value.items.map { it.path }.toSet()
                        val filtered = newItems.filter { it.path !in existingPaths }
                        if (filtered.isEmpty()) {
                            updateUiState { it.copy(isLoadingMore = false, nextMarker = null) }
                            return@fold
                        }
                        updateUiState {
                            it.copy(
                                items = _uiState.value.items + filtered,
                                isLoadingMore = false,
                                nextMarker = listResult.nextMarker.ifEmpty { null }
                            )
                        }
                        syncStackTop { it.copy(items = _uiState.value.items, isLoading = false, error = null) }
                    },
                    onFailure = { e ->
                        updateUiState {
                            it.copy(isLoadingMore = false, error = "加载更多失败: ${e.message}")
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
                breadcrumbs = state.breadcrumbs + AliyunBreadcrumb(item.name, item.path),
                scrollTargetIndex = index,
                scrollTargetParentKey = parentKey
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
            AliyunBreadcrumb(parts[0], if (parts.size > 1) parts[1] else "")
        }
        if (pathCrumbs.isEmpty()) return
        updateUiState { it.copy(breadcrumbs = pathCrumbs) }
        _navigationStack.value = pathCrumbs.map { DirectoryStackEntry(fileId = it.fileId, label = it.label) }
        loadDirectoryCached(fileId)
    }

    // endregion

    // region ==================== 排序 ====================

    fun setSort(orderBy: String, orderDirection: String = "") {
        val dir = orderDirection.ifEmpty {
            _uiState.value.orderBy.substringAfter(":", "ASC")
        }
        updateUiState { it.copy(orderBy = "$orderBy:$dir") }
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
                }
            )
        }
    }

    fun deleteItem(index: Int) {
        val item = _uiState.value.items.getOrNull(index) ?: return
        viewModelScope.launch {
            updateUiState { it.copy(isLoading = true) }
            val result = apiClient.trashFile(item.path)
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

    // region ==================== 移动 / 复制 ====================

    fun startMove(index: Int) {
        val item = _uiState.value.items.getOrNull(index) ?: return
        updateUiState { it.copy(pendingAction = "move", moveFileId = item.path) }
    }

    fun dismissPicker() {
        updateUiState {
            it.copy(
                pendingAction = null, moveFileId = null,
                pickerFolders = emptyList(), pickerIsLoading = false
            )
        }
    }

    fun loadFoldersForPicker(folderId: String) {
        viewModelScope.launch {
            val state = _uiState.value
            updateUiState { it.copy(pickerIsLoading = true) }
            val actualFolderId = folderId.ifEmpty { "root" }
            val result = apiClient.listFiles(actualFolderId)
            result.fold(
                onSuccess = { listResult ->
                    val operatingPath = when (state.pendingAction) {
                        "move" -> state.moveFileId
                        else -> null
                    }
                    val folders = listResult.items
                        .filter { it.type == "folder" && it.fileId != operatingPath }
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
            val actualParentId = parentFolderId.ifEmpty { "root" }
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
        val actualTargetId = targetFolderId.ifEmpty { "root" }
        viewModelScope.launch {
            updateUiState { it.copy(isLoading = true) }
            val result = apiClient.moveFile(fileId, actualTargetId)
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
        return cloudUriResolver.resolve(CloudUriScheme.buildCloudUri("alipan", item.path))
    }

    suspend fun resolveImageUrl(item: WebDavResource): Pair<String, Map<String, String>>? {
        val url = apiClient.getDownloadUrl(item.path).getOrNull() ?: return null
        return url to AliyunAuthProvider.getPlayHeaders()
    }

    // endregion

    // region ==================== 下载 ====================

    fun downloadFile(index: Int) {
        val res = _uiState.value.items.getOrNull(index) ?: return

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            try {
                val urlResult = apiClient.getDownloadUrl(res.path)
                urlResult.fold(
                    onSuccess = { url ->
                        _downloadProgress.value = DownloadProgressData(
                            fileName = res.name, progress = 0f
                        )

                        val eventJob = launch {
                            cloudDownloadRepository.downloadEvents.collect { event ->
                                when (event) {
                                    is CloudDownloadRepository.DownloadEvent.Progress -> {
                                        if (event.fileName == res.name) {
                                            _downloadProgress.value = DownloadProgressData(
                                                fileName = event.fileName,
                                                progress = event.progress,
                                                downloadedBytes = event.downloadedBytes,
                                                totalBytes = event.totalBytes
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
                            headers = AliyunAuthProvider.getPlayHeaders(),
                            provider = "aliyun"
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
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uri)
                type = PickerUtils.getMimeType(filePath)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "分享文件").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.e("AliyunVM", "分享文件失败", e)
            notifier.info("无法打开文件: ${e.message}")
        }
    }

    // endregion

    // region ==================== 工具方法 ====================

    private fun updateUiState(transform: (AliyunBrowserUiState) -> AliyunBrowserUiState) {
        while (true) {
            val current = _uiState.value
            val next = transform(current)
            if (_uiState.compareAndSet(current, next)) break
        }
    }

    private fun fileToResource(file: com.fluxplayer.app.core.data.aliyun.AliyunFileItem): WebDavResource {
        return WebDavResource(
            path = file.fileId,
            name = file.fileName,
            isDirectory = file.type == "folder",
            size = file.size,
            lastModified = file.updatedAt,
            thumbnailUrl = file.thumbnail.ifBlank { null },
            folderSize = if (file.type == "folder") file.size else 0,
            category = file.category,
            createdAt = file.createdAt
        )
    }

    private fun buildBreadcrumbs(fileId: String, label: String?): List<AliyunBreadcrumb> {
        val crumbs = mutableListOf(AliyunBreadcrumb("根目录", "root"))
        if (fileId != "root" && label != null) {
            crumbs.add(AliyunBreadcrumb(label, fileId))
        }
        return crumbs
    }

    // endregion
}
