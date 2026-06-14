package com.fluxplayer.app.feature.videopicker.cloud189

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import com.fluxplayer.app.core.common.FluxNotificationDelegate
import com.fluxplayer.app.core.model.FluxMessageEvent
import kotlinx.coroutines.flow.SharedFlow
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.fluxplayer.app.core.common.CloudPlaylistCache
import com.fluxplayer.app.core.common.CloudUriScheme
import com.fluxplayer.app.core.common.PickerUtils
import com.fluxplayer.app.core.data.cloud.CloudUriResolver
import com.fluxplayer.app.core.data.GlobalCookieJar
import com.fluxplayer.app.core.data.cloud189.C189ApiClient
import com.fluxplayer.app.core.data.cloud189.C189AuthProvider
import com.fluxplayer.app.core.data.cloud189.C189FileItem
import com.fluxplayer.app.core.data.repository.CloudDownloadRepository
import com.fluxplayer.app.core.data.repository.PlaybackHistoryRepository
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.feature.videopicker.CloudDirectoryCache
import com.fluxplayer.app.feature.videopicker.DirectoryStackEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import javax.inject.Inject

data class C189Breadcrumb(val label: String, val fileId: String)

@HiltViewModel
class C189BrowserViewModel @Inject constructor(
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

    init {
        // P2: 注册 token 刷新回调，API 层刷新成功后自动持久化
        C189AuthProvider.onTokensRefreshed = { saveTokens() }

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
                val maxRetries = 3
                val retryDelays = listOf(1000L, 3000L, 5000L)
                var retryCount = 0
                while (retryCount <= maxRetries) {
                    try {
                        // 使用挂起版本，失败会抛异常从而被 catch 捕获
                        loadDirectorySuspended("-11")
                        // 目录加载成功后才执行后续操作
                        autoSign()
                        detectClipboardShareUrl()
                        return@launch
                    } catch (e: Exception) {
                        android.util.Log.w(TAG, "tryRestoreSession attempt $retryCount failed: ${e.message}")
                        if (retryCount < maxRetries) {
                            delay(retryDelays[retryCount])
                            try {
                                val ok = apiClient.refreshAccessToken()
                                if (ok) {
                                    saveTokens()
                                }
                            } catch (_: Exception) {
                                // 刷新本身失败，继续尝试
                            }
                        }
                        retryCount++
                        if (retryCount > maxRetries) {
                            android.util.Log.w(TAG, "tryRestoreSession: 所有重试均失败，需要重新登录")
                            logout()
                        }
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
                    autoSign()
                    detectClipboardShareUrl()
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

    // region ==================== 登录 — sendSmsCode (发送短信验证码) ====================

    fun sendSmsCode(phone: String) {
        android.util.Log.d(TAG, "sendSmsCode called phone=${phone.take(3)}****")
        updateUiState { it.copy(smsSending = true, smsSentMessage = null, error = null) }
        viewModelScope.launch {
            val result = apiClient.sendSmsCode(phone)
            result.fold(
                onSuccess = {
                    updateUiState {
                        it.copy(
                            smsSending = false, smsCodeSent = true,
                            smsSentMessage = "验证码已发送至 $phone"
                        )
                    }
                },
                onFailure = { e ->
                    updateUiState {
                        it.copy(
                            smsSending = false, smsCodeSent = false,
                            error = "发送失败: ${e.message}"
                        )
                    }
                }
            )
        }
    }

    // endregion

    // region ==================== 登录 — loginBySms (短信验证码登录) ====================

    fun loginBySms(phone: String, smsCode: String) {
        android.util.Log.d(TAG, "loginBySms called phone=${phone.take(3)}****")
        updateUiState { it.copy(loginLoading = true, error = null) }
        viewModelScope.launch {
            val result = apiClient.loginBySms(phone, smsCode)
            result.fold(
                onSuccess = {
                    android.util.Log.d(TAG, "loginBySms SUCCESS, saving tokens")
                    saveTokens()
                    updateUiState {
                        it.copy(isLoggedIn = true, loginLoading = false, smsCodeSent = false)
                    }
                    loadDirectory("-11")
                    autoSign()
                    detectClipboardShareUrl()
                },
                onFailure = { e ->
                    val msg = e.message ?: "短信登录失败"
                    android.util.Log.e(TAG, "loginBySms FAILED: $msg", e)
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
                autoSign()
                detectClipboardShareUrl()
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

        // 清除 OkHttp GlobalCookieJar 中旧账号的 Cookie
        GlobalCookieJar.clearHost("cloud.189.cn")
        GlobalCookieJar.clearHost("api.cloud.189.cn")
        GlobalCookieJar.clearHost("m.cloud.189.cn")
        GlobalCookieJar.clearHost("open.e.189.cn")

        // 重置 ApiClient 自身字段
        apiClient.accessToken = ""
        apiClient.sessionKey = ""
        apiClient.sessionSecret = ""

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
            if (seq != loadSequence) return@launch
            try {
                loadDirectorySuspended(parentFileId)
            } catch (e: Exception) {
                if (seq != loadSequence) return@launch
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
        }
    }

    /**
     * 挂起版本的目录加载，失败时抛异常，供外部重试循环使用。
     * [tryRestoreSession] 通过本函数配合退避重试机制捕获错误并恢复。
     */
    private suspend fun loadDirectorySuspended(parentFileId: String) {
        val state = _uiState.value
        // API 不支持 filesize 排序，用 lastOpTime 获取后客户端排序
        val apiOrderBy = if (state.orderBy == "filesize") "lastOpTime" else state.orderBy
        val result = apiClient.listFiles(
            parentFileId, state.currentPage, 100, apiOrderBy, state.descending
        )

        val listResult = result.getOrThrow()

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

    /**
     * P1: 通用 session 恢复包装器，自动检测 session/token 过期并刷新后重试一次。
     * 适用于 delete/rename/move/createDirectory 等写操作。
     * @param operation 要执行的 API 操作（可能抛异常）
     * @throws Exception 非 session 过期错误或刷新失败时抛出原异常
     */
    private suspend fun withSessionRecovery(operation: suspend () -> Unit) {
        try {
            operation()
        } catch (e: Exception) {
            val msg = e.message ?: ""
            val isSessionError = msg.contains("InvalidAccessToken", ignoreCase = true) ||
                msg.contains("InvalidSessionKey", ignoreCase = true) ||
                msg.contains("SessionKeyInvalid", ignoreCase = true) ||
                msg.contains("require login", ignoreCase = true) ||
                msg.contains("未登录", ignoreCase = true)

            if (isSessionError) {
                try {
                    val ok = apiClient.refreshAccessToken()
                    if (ok) {
                        saveTokens()
                        operation() // 重试
                        return
                    }
                } catch (_: Exception) {
                    // refresh 本身失败，继续抛出原异常
                }
            }
            throw e // 非 session 错误或刷新失败
        }
    }

    fun createDirectory(name: String) {
        viewModelScope.launch {
            val state = _uiState.value
            updateUiState { it.copy(isLoading = true) }
            try {
                withSessionRecovery {
                    apiClient.createFolder(state.currentFolderId, name).getOrThrow()
                }
                notifier.success("文件夹创建成功")
                refresh()
            } catch (e: Exception) {
                notifier.error("创建失败: ${e.message}")
                updateUiState { it.copy(isLoading = false) }
            }
        }
    }

    fun deleteItem(index: Int) {
        val item = _uiState.value.items.getOrNull(index) ?: return
        viewModelScope.launch {
            updateUiState { it.copy(isLoading = true) }
            try {
                withSessionRecovery {
                    apiClient.deleteFiles(listOf(item.path)).getOrThrow()
                }
                notifier.success("删除成功")
                refresh()
            } catch (e: Exception) {
                notifier.error("删除失败: ${e.message}")
                updateUiState { it.copy(isLoading = false) }
            }
        }
    }

    fun renameItem(index: Int, newName: String) {
        val item = _uiState.value.items.getOrNull(index) ?: return
        viewModelScope.launch {
            updateUiState { it.copy(isLoading = true) }
            try {
                withSessionRecovery {
                    apiClient.renameFile(item.path, newName).getOrThrow()
                }
                notifier.success("重命名成功")
                refresh()
            } catch (e: Exception) {
                notifier.error("重命名失败: ${e.message}")
                updateUiState { it.copy(isLoading = false) }
            }
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
            it.copy(pendingAction = null, moveFileId = null,
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
                    notifier.error("加载文件夹失败: ${e.message}")
                }
            )
        }
    }

    fun createFolderInPicker(parentFolderId: String, name: String) {
        viewModelScope.launch {
            updateUiState { it.copy(pickerIsLoading = true) }
            val actualParentId = parentFolderId.ifEmpty { "-11" }
            try {
                withSessionRecovery {
                    apiClient.createFolder(actualParentId, name).getOrThrow()
                }
                notifier.success("文件夹创建成功")
                loadFoldersForPicker(parentFolderId)
            } catch (e: Exception) {
                notifier.error("创建失败: ${e.message}")
                updateUiState { it.copy(pickerIsLoading = false) }
            }
        }
    }

    fun moveTo(targetFolderId: String) {
        val fileId = _uiState.value.moveFileId ?: return
        val actualTargetId = targetFolderId.ifEmpty { "-11" }
        viewModelScope.launch {
            updateUiState { it.copy(isLoading = true) }
            try {
                withSessionRecovery {
                    apiClient.moveFiles(listOf(fileId), actualTargetId).getOrThrow()
                }
                notifier.success("移动成功")
                dismissPicker(); refresh()
            } catch (e: Exception) {
                notifier.error("移动失败: ${e.message}")
                updateUiState { it.copy(isLoading = false) }
            }
        }
    }

    // endregion

    // region ==================== 视频播放 ====================

    suspend fun resolveVideoUri(item: WebDavResource): android.net.Uri? {
        return cloudUriResolver.resolve(CloudUriScheme.buildCloudUri("cloud189", item.path))
    }

    suspend fun resolveImageUrl(item: WebDavResource): Pair<String, Map<String, String>>? {
        val url = apiClient.getDownloadUrl(item.path).getOrNull() ?: return null
        return url to C189AuthProvider.getPlayHeaders()
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
                            headers = C189AuthProvider.getPlayHeaders(),
                            provider = "cloud189"
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
            Log.e("C189VM", "分享文件失败", e)
            notifier.info("无法打开文件: ${e.message}")
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

    // region ==================== 签到 ====================

    /** 静默签到（登录后自动调用，同一天只签一次） */
    fun autoSign() {
        val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        val lastSignDay = prefs.getString("lastSignDay", "") ?: ""
        if (lastSignDay == today) return

        viewModelScope.launch {
            apiClient.userSign().fold(
                onSuccess = { msg ->
                    prefs.edit().putString("lastSignDay", today).apply()
                    Log.d(TAG, "autoSign: $msg")
                },
                onFailure = { e ->
                    Log.w(TAG, "autoSign failed: ${e.message}")
                }
            )
        }
    }

    /** 手动签到（菜单点击） */
    fun manualSign() {
        val app = getApplication<Application>()
        val prefs = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        val lastSignDay = prefs.getString("lastSignDay", "") ?: ""

        if (lastSignDay == today) {
            notifier.success("今日已签到")
            return
        }

        viewModelScope.launch {
            apiClient.userSign().fold(
                onSuccess = { msg ->
                    prefs.edit().putString("lastSignDay", today).apply()
                    notifier.success(msg)
                },
                onFailure = { e ->
                    notifier.error("签到失败: ${e.message}")
                }
            )
        }
    }

    // endregion

    // region ==================== 用户信息 ====================

    fun showUserInfo() {
        if (_uiState.value.userNickname.isNotEmpty()) {
            // 已有缓存数据，直接显示
            updateUiState { it.copy(showUserInfoDialog = true) }
            return
        }
        updateUiState { it.copy(showUserInfoDialog = true, isLoading = true) }
        viewModelScope.launch {
            try {
                val ext = apiClient.getUserInfoExt()
                val info = apiClient.getUserInfo()
                val priv = apiClient.getUserPrivileges()
                updateUiState {
                    it.copy(
                        isLoading = false,
                        userNickname = ext.optString("nickName", ""),
                        userPhone = ext.optString("safeMobile", ""),
                        userCapacity = info.optLong("capacity", 0),
                        userAvailable = info.optLong("available", 0),
                        userVipExpireTime = priv.optString("vipExpiredTime", ""),
                    )
                }
            } catch (e: Exception) {
                updateUiState {
                    it.copy(isLoading = false, error = "获取用户信息失败: ${e.message}")
                }
            }
        }
    }

    fun dismissUserInfo() {
        updateUiState { it.copy(showUserInfoDialog = false) }
    }

    // endregion

    // region ==================== 分享链接 ====================

    fun showShareInput() {
        updateUiState { it.copy(showShareInputDialog = true) }
    }

    fun dismissShareInput() {
        updateUiState { it.copy(showShareInputDialog = false, shareInputText = "") }
    }

    fun updateShareInputText(text: String) {
        updateUiState { it.copy(shareInputText = text) }
    }

    /** 打开分享链接 —— 解析 → 获取信息 → 在弹窗中浏览文件 */
    fun openShareUrl(rawUrl: String) {
        val info = apiClient.parseShareUrl(rawUrl)
        if (info == null) {
            updateUiState { it.copy(showShareInputDialog = false) }
            notifier.error("请检查分享链接格式，应为 cloud.189.cn/t/...")
            return
        }
        updateUiState {
            it.copy(
                showShareInputDialog = false,
                shareInputText = rawUrl,
                showShareBrowse = true,
                shareBrowseKey = info.shareKey,
                shareBrowsePwd = info.sharePwd,
                shareSaveTargetFolderId = "-11",
                shareSaveTargetFolderLabel = "根目录",
                shareSaveTargetBreadcrumbs = listOf(C189Breadcrumb("根目录", "-11")),
                shareIsLoading = true,
                error = null,
            )
        }

        viewModelScope.launch {
            try {
                // 获取分享信息
                val shareJson = apiClient.getShareInfoByCode(info.shareKey, info.sharePwd)
                val shareId = shareJson.optString("shareId", "")
                if (shareId.isEmpty()) {
                    throw IllegalStateException("需要访问码，请在链接后附上「访问码：xxxx」")
                }
                val fileId = shareJson.optString("fileId", "")
                val isFolder = shareJson.optBoolean("isFolder", false)
                val shareMode = shareJson.optInt("shareMode", 0)
                val rawName = {
                    val raw = shareJson.optString("fileName", "分享文件")
                    if (!Regex("%[0-9A-Fa-f]{2}").containsMatchIn(raw)) raw
                    else try {
                        java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
                    } catch (_: Exception) {
                        raw
                    }
                }.invoke()

                // 列出分享文件
                val result = apiClient.listShareDir(
                    shareId = shareId,
                    fileId = fileId,
                    isFolder = isFolder,
                    shareMode = shareMode,
                    accessCode = info.sharePwd,
                )
                val resources = result.items.map { fileToResource(it) }
                val crumbs = listOf(C189Breadcrumb("分享: $rawName", fileId))
                updateUiState {
                    it.copy(
                        shareItems = resources,
                        shareIsLoading = false,
                        shareBreadcrumbs = crumbs,
                        shareName = rawName,
                        shareCurrentFileId = fileId,
                        shareIsFolder = isFolder,
                        shareMode = shareMode,
                        shareBrowseShareId = shareId,
                        shareHasMore = result.items.size >= 200,
                        shareCurrentPage = 1,
                    )
                }
            } catch (e: Exception) {
                updateUiState { it.copy(shareIsLoading = false, showShareBrowse = false) }
                notifier.error("打开分享失败: ${e.message}")
            }
        }
    }

    // ==================== 转存目标文件夹选择器 ====================

    fun showShareTargetFolderPicker() {
        val currentBreadcrumbs = _uiState.value.shareSaveTargetBreadcrumbs
        val currentFolderId = currentBreadcrumbs.lastOrNull()?.fileId ?: "-11"
        updateUiState {
            it.copy(
                showShareTargetPicker = true,
                shareTargetPickerPath = currentBreadcrumbs.ifEmpty {
                    listOf(C189Breadcrumb("根目录", "-11"))
                },
            )
        }
        loadShareTargetFolders(currentFolderId)
    }

    fun dismissShareTargetPicker() {
        updateUiState { it.copy(showShareTargetPicker = false) }
    }

    private fun loadShareTargetFolders(folderId: String) {
        viewModelScope.launch {
            updateUiState { it.copy(shareTargetPickerIsLoading = true) }
            val result = apiClient.listFiles(folderId)
            result.fold(
                onSuccess = { listResult ->
                    val folders = listResult.items
                        .filter { it.isDir }
                        .map { fileToResource(it) }
                    updateUiState { it.copy(shareTargetPickerFolders = folders, shareTargetPickerIsLoading = false) }
                },
                onFailure = { e ->
                    updateUiState { it.copy(shareTargetPickerFolders = emptyList(), shareTargetPickerIsLoading = false) }
                    notifier.error("加载文件夹失败: ${e.message}")
                }
            )
        }
    }

    fun navigateShareTargetFolder(fileId: String, label: String) {
        val currentPath = _uiState.value.shareTargetPickerPath
        updateUiState {
            it.copy(
                shareTargetPickerPath = currentPath + C189Breadcrumb(label, fileId),
                shareSaveTargetFolderId = fileId,
            )
        }
        loadShareTargetFolders(fileId)
    }

    fun navigateShareTargetUp() {
        val path = _uiState.value.shareTargetPickerPath
        if (path.size <= 1) return
        val newPath = path.dropLast(1)
        val parentId = newPath.last().fileId
        updateUiState { it.copy(shareTargetPickerPath = newPath) }
        loadShareTargetFolders(parentId)
    }

    fun navigateShareTargetToIndex(index: Int) {
        val path = _uiState.value.shareTargetPickerPath
        if (index < 0 || index >= path.size || index == path.lastIndex) return
        val target = path[index]
        val newPath = path.subList(0, index + 1)
        updateUiState { it.copy(shareTargetPickerPath = newPath) }
        loadShareTargetFolders(target.fileId)
    }

    fun selectShareTargetCurrentFolder() {
        val state = _uiState.value
        val path = state.shareTargetPickerPath
        val selected = path.last()
        updateUiState {
            it.copy(
                showShareTargetPicker = false,
                shareSaveTargetFolderId = selected.fileId,
                shareSaveTargetFolderLabel = if (path.size == 1) "根目录" else selected.label,
                shareSaveTargetBreadcrumbs = path,
            )
        }
    }

    /** 分享浏览弹窗中转存目标面包屑点击 —— 回到指定层级 */
    fun shareTargetBreadcrumbClick(index: Int) {
        val path = _uiState.value.shareSaveTargetBreadcrumbs
        if (index < 0 || index >= path.size || index == path.lastIndex) return
        val target = path[index]
        val newPath = path.subList(0, index + 1)
        updateUiState {
            it.copy(
                shareSaveTargetFolderId = target.fileId,
                shareSaveTargetFolderLabel = if (newPath.size == 1 && newPath.first().fileId == "-11") "根目录" else target.label,
                shareSaveTargetBreadcrumbs = newPath,
            )
        }
    }

    /** 转存分享中的文件到选定的目标路径 */
    fun shareSaveFile(index: Int) {
        val state = _uiState.value
        if (!state.showShareBrowse) return
        val item = state.shareItems.getOrNull(index) ?: return
        val shareId = state.shareBrowseShareId
        val targetId = state.shareSaveTargetFolderId.ifBlank { "-11" }

        updateUiState { it.copy(shareIsLoading = true) }
        viewModelScope.launch {
            try {
                val ok = apiClient.shareSave(
                    fileId = item.path,
                    fileName = item.name,
                    shareId = shareId,
                    isFolder = if (item.isDirectory) 1 else 0,
                    targetFolderId = targetId,
                )
                if (ok) {
                    notifier.info("已转存到 ${state.shareSaveTargetFolderLabel}: ${item.name}")
                } else {
                    notifier.error("转存失败，请重试")
                }
            } catch (e: Exception) {
                notifier.error("转存失败: ${e.message}")
            } finally {
                updateUiState { it.copy(shareIsLoading = false) }
            }
        }
    }

    /** 批量转存分享中的文件 */
    fun shareSaveFiles(indices: Set<Int>) {
        val state = _uiState.value
        if (!state.showShareBrowse || indices.isEmpty()) return
        val shareId = state.shareBrowseShareId
        val targetId = state.shareSaveTargetFolderId.ifBlank { "-11" }
        val targetLabel = state.shareSaveTargetFolderLabel

        updateUiState { it.copy(shareIsLoading = true) }
        viewModelScope.launch {
            var successCount = 0
            var failCount = 0
            for (index in indices) {
                val item = state.shareItems.getOrNull(index) ?: continue
                try {
                    val ok = apiClient.shareSave(
                        fileId = item.path,
                        fileName = item.name,
                        shareId = shareId,
                        isFolder = if (item.isDirectory) 1 else 0,
                        targetFolderId = targetId,
                    )
                    if (ok) successCount++ else failCount++
                } catch (_: Exception) {
                    failCount++
                }
            }
            updateUiState { it.copy(shareIsLoading = false) }
            val msg = when {
                failCount == 0 -> "已转存 $successCount 项到 $targetLabel"
                successCount == 0 -> "转存失败，请重试"
                else -> "成功 $successCount 项，失败 $failCount 项"
            }
            notifier.info(msg)
        }
    }

    fun exitShareBrowse() {
        updateUiState {
            it.copy(
                showShareBrowse = false,
                shareBrowseKey = "",
                shareBrowsePwd = "",
                shareBrowseShareId = "",
                shareSaveTargetFolderId = "-11",
                shareSaveTargetFolderLabel = "根目录",
                shareSaveTargetBreadcrumbs = emptyList(),
                shareItems = emptyList(),
                shareBreadcrumbs = emptyList(),
                shareName = "",
                shareCurrentFileId = "",
                shareIsFolder = false,
                shareMode = 0,
                shareIsLoading = false,
            )
        }
        // 不再强制 loadDirectory("-11")，保持主界面原有浏览位置
    }

    /** 分享弹窗内进入子目录 */
    fun navigateShareFolder(item: WebDavResource) {
        val state = _uiState.value
        if (!state.showShareBrowse || !item.isDirectory) return
        updateUiState { it.copy(shareIsLoading = true, shareCurrentPage = 1) }
        viewModelScope.launch {
            try {
                val result = apiClient.listShareDir(
                    shareId = state.shareBrowseShareId,
                    fileId = item.path,
                    isFolder = true,
                    shareMode = state.shareMode,
                    accessCode = state.shareBrowsePwd,
                )
                val resources = result.items.map { fileToResource(it) }
                updateUiState {
                    it.copy(
                        shareItems = resources,
                        shareIsLoading = false,
                        shareBreadcrumbs = state.shareBreadcrumbs + C189Breadcrumb(item.name, item.path),
                        shareCurrentFileId = item.path,
                        shareIsFolder = true,
                        shareHasMore = result.items.size >= 200,
                        shareCurrentPage = 1,
                    )
                }
            } catch (e: Exception) {
                updateUiState { it.copy(shareIsLoading = false) }
                notifier.error("进入文件夹失败: ${e.message}")
            }
        }
    }

    /** 分享弹窗内返回上级目录 */
    fun navigateShareUp() {
        val state = _uiState.value
        if (state.shareBreadcrumbs.size <= 1) return
        val target = state.shareBreadcrumbs[state.shareBreadcrumbs.size - 2]
        updateUiState {
            it.copy(
                shareBreadcrumbs = state.shareBreadcrumbs.dropLast(1),
                shareIsLoading = true,
            )
        }
        viewModelScope.launch {
            try {
                val isFolder = target.fileId != state.shareBrowseShareId
                val result = apiClient.listShareDir(
                    shareId = state.shareBrowseShareId,
                    fileId = target.fileId,
                    isFolder = isFolder,
                    shareMode = state.shareMode,
                    accessCode = state.shareBrowsePwd,
                )
                val resources = result.items.map { fileToResource(it) }
                updateUiState {
                    it.copy(
                        shareItems = resources,
                        shareIsLoading = false,
                        shareCurrentFileId = target.fileId,
                        shareIsFolder = isFolder,
                        shareHasMore = result.items.size >= 200,
                        shareCurrentPage = 1,
                    )
                }
            } catch (e: Exception) {
                updateUiState { it.copy(shareIsLoading = false) }
                notifier.error("返回失败: ${e.message}")
            }
        }
    }

    /** 分享弹窗内回退到指定面包屑层级 */
    fun navigateShareToBreadcrumb(index: Int) {
        val state = _uiState.value
        val crumbs = state.shareBreadcrumbs
        if (index >= crumbs.size) return
        val target = crumbs[index]
        if (target.fileId.isEmpty()) return
        updateUiState {
            it.copy(
                shareBreadcrumbs = crumbs.subList(0, index + 1),
                shareIsLoading = true,
            )
        }
        viewModelScope.launch {
            try {
                val isFolder = target.fileId != state.shareBrowseShareId
                val result = apiClient.listShareDir(
                    shareId = state.shareBrowseShareId,
                    fileId = target.fileId,
                    isFolder = isFolder,
                    shareMode = state.shareMode,
                    accessCode = state.shareBrowsePwd,
                )
                val resources = result.items.map { fileToResource(it) }
                updateUiState {
                    it.copy(
                        shareItems = resources,
                        shareIsLoading = false,
                        shareCurrentFileId = target.fileId,
                        shareIsFolder = isFolder,
                        shareHasMore = result.items.size >= 200,
                        shareCurrentPage = 1,
                    )
                }
            } catch (e: Exception) {
                updateUiState { it.copy(shareIsLoading = false) }
                notifier.error("跳转失败: ${e.message}")
            }
        }
    }

    // endregion

    // region ==================== 剪切板检测 ====================

    /** 检测剪切板中是否有天翼云盘分享链接，有则直接打开分享浏览 */
    fun detectClipboardShareUrl() {
        try {
            val clipboard = getApplication<Application>()
                .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clip = clipboard.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0).text?.toString() ?: return
            if (!apiClient.isShareUrl(text)) return

            // 去重：不重复弹窗
            val prefs = getApplication<Application>().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val lastPrompted = prefs.getString("lastClipboardPrompt", "") ?: ""
            if (lastPrompted == text) return

            prefs.edit().putString("lastClipboardPrompt", text).apply()
            // 直接进入分享浏览模式
            openShareUrl(text)
        } catch (_: Exception) {
            // 剪切板读取失败，静默忽略
        }
    }

    // endregion
}
