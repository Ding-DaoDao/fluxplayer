package dev.anilbeesetti.nextplayer.feature.videopicker.aliyun

import android.app.Application
import android.content.Context
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.anilbeesetti.nextplayer.core.common.CloudPlayHeaders
import dev.anilbeesetti.nextplayer.core.common.CloudPlaylistCache
import dev.anilbeesetti.nextplayer.core.common.VideoQualityCache
import dev.anilbeesetti.nextplayer.core.data.aliyun.AliyunApiClient
import dev.anilbeesetti.nextplayer.core.data.aliyun.AliyunAuthProvider
import dev.anilbeesetti.nextplayer.core.data.aliyun.AliyunTokenExpiredException
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.model.WebDavResource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import dev.anilbeesetti.nextplayer.feature.videopicker.CloudDirectoryCache
import dev.anilbeesetti.nextplayer.feature.videopicker.DirectoryStackEntry
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import javax.inject.Inject

data class AliyunBreadcrumb(val label: String, val fileId: String)

@HiltViewModel
class AliyunBrowserViewModel @Inject constructor(
    application: Application,
    private val preferencesRepository: PreferencesRepository,
    private val videoQualityCache: VideoQualityCache
) : androidx.lifecycle.AndroidViewModel(application) {

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
    private var loadingMore: Boolean = false
    private val directoryCache = mutableMapOf<String, List<WebDavResource>>()

    // endregion

    // region ==================== 登录 — loginWithAuthorization ====================

    fun loginWithAuthorization(auth: String, defaultDriveId: String = "", refreshToken: String = "") {
        apiClient.authorization = auth
        AliyunAuthProvider.authorization = auth
        AliyunAuthProvider.isActive = true
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

        // 跳过 token 验证，直接加载目录（loadDirectory 内已处理 token 失效 → triggerReLogin）
        AliyunAuthProvider.authorization = auth
        AliyunAuthProvider.isActive = true
        updateUiState { it.copy(isLoggedIn = true, isLoading = true) }
        syncStackTop { it.copy(isLoading = true, error = null) }
        // 从磁盘缓存预填根目录，加速子目录导航
        CloudDirectoryCache.get(getApplication(), "alipan", "root")?.let { directoryCache["root"] = it }
        viewModelScope.launch { refreshDriveInfo() }
        loadDirectory("root")
    }

    // endregion

    // region ==================== 登出 ====================

    fun logout() {
        AliyunAuthProvider.clear()
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

    private fun buildDriveOptionsFromInfo(info: dev.anilbeesetti.nextplayer.core.data.aliyun.AliyunDriveInfo): List<DriveOption> {
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

    private fun resolveDefaultDrive(info: dev.anilbeesetti.nextplayer.core.data.aliyun.AliyunDriveInfo): String {
        // 默认选中资源盘
        return info.resourceDriveId.ifEmpty { info.backupDriveId.ifEmpty { info.defaultDriveId } }
    }

    private fun updateDriveOptions(info: dev.anilbeesetti.nextplayer.core.data.aliyun.AliyunDriveInfo) {
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
        try {
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
            android.webkit.WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {}
        updateUiState {
            AliyunBrowserUiState(
                isLoggedIn = false,
                reLoginRequired = true,
                error = "登录已过期，请重新登录"
            )
        }
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
                orderBy = _uiState.value.orderBy.substringBefore(":"),
                orderDirection = _uiState.value.orderBy.substringAfter(":", "ASC")
            )
            if (seq != loadSequence) return@launch

            result.fold(
                onSuccess = { listResult ->
                    // 缓存文件元数据到 CloudPlaylistCache，供播放器显示标题
                    listResult.items.forEach { file ->
                        if (file.category == "video") {
                            CloudPlaylistCache.putFileMetadata(
                                "alipan", file.fileId,
                                CloudPlaylistCache.FileMetadata(fileName = file.fileName)
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
        if (state.nextMarker == null || loadingMore || state.isLoading) return
        loadingMore = true
        updateUiState { it.copy(isLoadingMore = true) }

        viewModelScope.launch {
            val result = apiClient.listFiles(
                parentFileId = state.currentFileId,
                nextMarker = state.nextMarker
            )
            result.fold(
                onSuccess = { listResult ->
                    val newItems = listResult.items.map { fileToResource(it) }
                    updateUiState {
                        it.copy(
                            items = state.items + newItems,
                            isLoadingMore = false,
                            nextMarker = listResult.nextMarker.ifEmpty { null }
                        )
                    }
                    loadingMore = false
                },
                onFailure = { e ->
                    updateUiState {
                        it.copy(isLoadingMore = false, error = "加载更多失败: ${e.message}")
                    }
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
        val state = _uiState.value
        val segments = label.split("/")
        val root = state.breadcrumbs.firstOrNull() ?: AliyunBreadcrumb("根目录", "root")
        val pathCrumbs = segments.map { seg ->
            val parts = seg.split("|", limit = 2)
            AliyunBreadcrumb(parts[0], if (parts.size > 1) parts[1] else "")
        }
        updateUiState { it.copy(breadcrumbs = listOf(root) + pathCrumbs) }
        val rootFileId = root.fileId.ifEmpty { "root" }
        val rootLabel = root.label.ifEmpty { "根目录" }
        _navigationStack.value = listOf(DirectoryStackEntry(fileId = rootFileId, label = rootLabel)) +
            pathCrumbs.map { DirectoryStackEntry(fileId = it.fileId, label = it.label) }
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
            val result = apiClient.trashFile(item.path)
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
            it.copy(
                pendingAction = null, moveFileId = null, copyFileId = null,
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
                        "copy" -> state.copyFileId
                        else -> null
                    }
                    val folders = listResult.items
                        .filter { it.type == "folder" && it.fileId != operatingPath }
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
            val actualParentId = parentFolderId.ifEmpty { "root" }
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
            val result = apiClient.moveFile(fileId, targetFolderId)
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

    fun copyTo(targetFolderId: String) {
        val copyId = _uiState.value.copyFileId ?: return
        viewModelScope.launch {
            updateUiState { it.copy(isLoading = true) }
            val result = apiClient.copyFile(copyId, targetFolderId)
            result.fold(
                onSuccess = {
                    Toast.makeText(getApplication(), "复制成功", Toast.LENGTH_SHORT).show()
                    dismissPicker()
                    refresh()
                },
                onFailure = { e ->
                    Toast.makeText(getApplication(), "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    updateUiState { it.copy(isLoading = false) }
                }
            )
        }
    }

    // endregion

    // region ==================== 视频播放 ====================

    suspend fun resolveVideoUri(item: WebDavResource): android.net.Uri? {
        // 注册阿里云播放头（lambda 实时读取，避免 stale token）
        CloudPlayHeaders.register("vod.alipan.com") { AliyunAuthProvider.getPlayHeaders() }

        val result = apiClient.getVideoPreviewPlayInfo(item.path).getOrNull()
        if (result != null && result.urls.isNotEmpty()) {
            val url = result.urls.first() + "#alipanPlay=true#"
            CloudPlaylistCache.putResolvedUrl("alipan", item.path, url)

            // 缓存清晰度选项，加速后续切换
            if (result.urls.size > 1) {
                val qualityOptions = result.urls.zip(result.names).map { (u, n) ->
                    VideoQualityCache.QualityOption(label = n, url = u)
                }
                videoQualityCache.cacheQualityOptions(
                    provider = "alipan",
                    fileId = item.path,
                    videoName = item.name,
                    options = qualityOptions
                )
            }

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
                val urlResult = apiClient.getDownloadUrl(res.path)
                urlResult.fold(
                    onSuccess = { url ->
                        val dm = getApplication<Application>()
                            .getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                        val request = android.app.DownloadManager.Request(android.net.Uri.parse(url)).apply {
                            setTitle(res.name)
                            setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            setDestinationInExternalPublicDir(
                                android.os.Environment.DIRECTORY_DOWNLOADS, res.name
                            )
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

    private fun updateUiState(transform: (AliyunBrowserUiState) -> AliyunBrowserUiState) {
        while (true) {
            val current = _uiState.value
            val next = transform(current)
            if (_uiState.compareAndSet(current, next)) break
        }
    }

    private fun fileToResource(file: dev.anilbeesetti.nextplayer.core.data.aliyun.AliyunFileItem): WebDavResource {
        return WebDavResource(
            path = file.fileId,
            name = file.fileName,
            isDirectory = file.type == "folder",
            size = file.size,
            lastModified = file.updatedAt
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
