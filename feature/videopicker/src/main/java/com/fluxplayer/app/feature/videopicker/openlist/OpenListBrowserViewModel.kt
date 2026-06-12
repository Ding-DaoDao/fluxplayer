package com.fluxplayer.app.feature.videopicker.openlist

import android.app.Application
import android.util.Log
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.fluxplayer.app.core.common.CloudPlaylistCache
import com.fluxplayer.app.core.data.openlist.OpenListApiClient
import com.fluxplayer.app.core.data.openlist.OpenListFileItem
import com.fluxplayer.app.core.data.openlist.OpenListManagerProvider
import com.fluxplayer.app.core.data.openlist.OpenListServerState
import com.fluxplayer.app.core.data.openlist.OpenListTokenProvider
import com.fluxplayer.app.core.data.repository.PlaybackHistoryRepository
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.feature.videopicker.BaseCloudBrowserViewModel
import com.fluxplayer.app.feature.videopicker.CommonStateUpdate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OpenListBreadcrumb(val label: String, val path: String)

@HiltViewModel
class OpenListBrowserViewModel @Inject constructor(
    application: Application,
    playbackHistoryRepository: PlaybackHistoryRepository,
    private val preferencesRepository: PreferencesRepository,
) : BaseCloudBrowserViewModel<OpenListBreadcrumb>(application) {

    companion object {
        private const val TAG = "OpenListBrowserVM"
        private const val PREF_NAME = "openlist"
        private const val MAX_LIST_RETRIES = 2
        private const val LIST_RETRY_DELAY_MS = 800L
    }

    // region ==================== OpenList 特有 ====================

    private val manager = OpenListManagerProvider.get()
    private var apiClient: OpenListApiClient? = null
    private var currentAdminPassword: String? = null

    // endregion

    // region ==================== 抽象实现 ====================

    override val providerLabel: String = "openlist"
    override val prefName: String = PREF_NAME
    override val rootFileId: String = "/"
    override val rootLabel: String = "根目录"
    override val defaultOrderBy: String = "name"
    override val defaultOrderDirection: String = "ASC"

    override fun breadcrumbLabelImpl(crumb: OpenListBreadcrumb): String = crumb.label
    override fun breadcrumbFileId(crumb: OpenListBreadcrumb): String = crumb.path
    override fun makeBreadcrumb(label: String, path: String): OpenListBreadcrumb =
        OpenListBreadcrumb(label, path)

    // endregion

    // region ==================== 初始化 ====================

    init {
        initCommonState(
            loadDirectory = { path -> loadDirectoryInternal(path) },
            prefsRepo = { preferencesRepository },
        )
        initFootprint(playbackHistoryRepository)

        // 初始化 OpenList 服务连接
        viewModelScope.launch {
            if (manager == null) {
                updateState(CommonStateUpdate(error = "OpenListManager 未初始化"))
                return@launch
            }
            manager.state.collect { state ->
                if (state is OpenListServerState.Running) {
                    initialize()
                } else if (state is OpenListServerState.Error) {
                    updateState(CommonStateUpdate(error = "服务异常: ${state.message}"))
                }
            }
        }

        // 监听密码就绪：首次安装时初始密码来自 stdout，晚于 Running 状态
        // 当密码就绪 + 服务器已运行 + 之前因"密码未就绪"报错时，自动重新初始化
        viewModelScope.launch {
            val mgr = manager ?: return@launch
            mgr.initialPassword.collect { pwd ->
                if (pwd != null && mgr.state.value is OpenListServerState.Running) {
                    val curError = readState().error
                    if (!readState().isLoggedIn && curError != null && curError.contains("密码未就绪")) {
                        Log.d(TAG, "初始密码已就绪，重新初始化")
                        initialize()
                    }
                }
            }
        }
    }

    // endregion

    // region ==================== API 实现 ====================

    override suspend fun doListFiles(
        parentFileId: String,
        page: Int?,
        orderBy: String,
        orderDirection: String
    ): Result<List<WebDavResource>> {
        val client = apiClient
            ?: return Result.failure(IllegalStateException("API Client 未初始化"))
        return client.listFiles(parentFileId, currentAdminPassword, orderBy, orderDirection).map { items ->
            val resources = items.map { it.toWebDavResource() }
            // API 已按排序参数返回，此处做客户端二次排序确保一致性（文件夹始终在前）
            val baseCmp: Comparator<WebDavResource> = when (orderBy) {
                "name" -> compareBy { it.name.lowercase() }
                "size" -> compareBy { it.size }
                "modified" -> compareBy { it.lastModified }
                else -> compareBy { it.name.lowercase() }
            }
            val directedCmp = if (orderDirection.equals("DESC", ignoreCase = true)) baseCmp.reversed() else baseCmp
            val finalCmp = compareByDescending<WebDavResource> { it.isDirectory }.thenComparing(directedCmp)
            resources.sortedWith(finalCmp)
        }
    }

    override fun sortItems(items: List<WebDavResource>): List<WebDavResource> {
        val state = readState()
        // 文件夹和文件分开排序：文件夹始终在前，各自组内按选定字段排序
        val baseCmp: Comparator<WebDavResource> = when (state.orderBy) {
            "name" -> compareBy { it.name.lowercase() }
            "size" -> compareBy { it.size }
            "modified" -> compareBy { it.lastModified }
            else -> compareBy { it.name.lowercase() }
        }
        val directedCmp = if (state.orderDirection.equals("DESC", ignoreCase = true)) baseCmp.reversed() else baseCmp
        val finalCmp = compareByDescending<WebDavResource> { it.isDirectory }.thenComparing(directedCmp)
        return items.sortedWith(finalCmp)
    }

    override suspend fun doCreateFolder(name: String, parentFileId: String): Result<Unit> {
        // OpenList API 没有直接的创建文件夹方法
        return Result.failure(UnsupportedOperationException("OpenList 暂不支持创建文件夹"))
    }

    override suspend fun doDeleteResource(res: WebDavResource): Result<Unit> {
        // OpenList API 没有直接的删除文件方法
        return Result.failure(UnsupportedOperationException("OpenList 暂不支持删除"))
    }

    override suspend fun doRenameResource(res: WebDavResource, newName: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException("OpenList 暂不支持重命名"))
    }

    override suspend fun doMoveResource(fileId: String, targetFolderId: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException("OpenList 暂不支持移动"))
    }

    override suspend fun doCopyResource(copyFileId: String, targetFolderId: String): Result<Unit> {
        return Result.failure(UnsupportedOperationException("OpenList 暂不支持复制"))
    }

    override suspend fun doGetDownloadInfo(res: WebDavResource): Result<DownloadInfo> {
        // OpenList 文件可以直接通过 HTTP 下载
        val baseUrl = "http://127.0.0.1:5244"
        val relativePath = if (res.path.startsWith(baseUrl)) {
            res.path.removePrefix(baseUrl)
        } else {
            res.path
        }
        return Result.success(
            DownloadInfo(
                url = "$baseUrl/d$relativePath",
                fileName = res.name,
                headers = apiClient?.getAdminToken()?.let { mapOf("Authorization" to "Bearer $it") }
                    ?: emptyMap()
            )
        )
    }

    // endregion

    // region ==================== 目录加载 ====================

    private suspend fun initialize() {
        val pwd = manager?.adminSetPassword?.value ?: manager?.initialPassword?.value
        Log.d(TAG, "initialize() called, pwd exists=${pwd != null}")
        if (pwd == null) {
            updateState(CommonStateUpdate(error = "密码未就绪，请先在设置页配置 OpenList"))
            return
        }

        updateState(CommonStateUpdate(isLoggedIn = true, isLoading = true, error = null))
        val client = OpenListApiClient()
        apiClient = client

        val loginResult = client.adminLogin(pwd)
        if (loginResult.isSuccess) {
            Log.d(TAG, "adminLogin success")
            OpenListTokenProvider.bearerToken = client.getAdminToken()
        } else {
            Log.w(TAG, "adminLogin failed: ${loginResult.exceptionOrNull()?.message}")
        }
        currentAdminPassword = pwd

        Log.d(TAG, "calling loadDirectory(/), currentAdminPassword=$currentAdminPassword")
        loadDirectoryInternal(rootFileId)
    }

    private fun loadDirectoryInternal(path: String, retryCount: Int = 0) {
        val client = apiClient ?: run {
            Log.e(TAG, "loadDirectory: apiClient is null!")
            return
        }
        Log.d(TAG, "loadDirectory: path=$path, hasPassword=${currentAdminPassword != null}, retryCount=$retryCount")

        // 仅在首次尝试时重置 loading 状态，重试时保持 isLoading = true
        if (retryCount == 0) {
            updateState(CommonStateUpdate(isLoading = true, error = null))
        }

        viewModelScope.launch {
            try {
                val state = readState()
                val result = client.listFiles(path, currentAdminPassword, state.orderBy, state.orderDirection)
                result.fold(
                    onSuccess = { items ->
                        Log.d(TAG, "loadDirectory success: ${items.size} items for path=$path")
                        val resources = items.map { it.toWebDavResource() }
                        val sorted = sortItems(resources)

                        // 将视频文件元数据写入 CloudPlaylistCache（供播放历史跳转使用）
                        val breadcrumbPath = readState().breadcrumbs.joinToString("/") { "${breadcrumbLabel(it)}|${breadcrumbFileId(it)}" }
                        sorted.filter { it.isVideo }.forEach { file ->
                            CloudPlaylistCache.putFileMetadata(
                                providerLabel, file.path,
                                CloudPlaylistCache.FileMetadata(
                                    fileName = file.name,
                                    parentPath = "${path}|$breadcrumbPath",
                                )
                            )
                        }

                        updateState(CommonStateUpdate(currentFileId = path))
                        onDirectoryLoaded(path, sorted, hasMore = false)
                    },
                    onFailure = { e ->
                        val msg = e.message ?: ""
                        val isConnErr = msg.contains("Failed to connect") || msg.contains("Unable to resolve")
                        if (isConnErr && retryCount < MAX_LIST_RETRIES) {
                            Log.d(TAG, "loadDirectory connect failed, auto-retry ${retryCount + 1}/$MAX_LIST_RETRIES")
                            delay(LIST_RETRY_DELAY_MS)
                            loadDirectoryInternal(path, retryCount + 1)
                            return@launch
                        }
                        Log.e(TAG, "loadDirectory failed for path=$path: ${e.message}")
                        val friendlyMsg = e.message?.let { msg2 ->
                            when {
                                msg2.contains("storage not found") || msg2.contains("add a storage") ->
                                    "OpenList 中没有挂载任何存储，请在 OpenList 网页后台添加存储"
                                msg2.contains("Failed to connect") ->
                                    "无法连接到 OpenList 服务（127.0.0.1:5244），请确认服务已启动"
                                msg2.contains("401") || msg2.contains("token is invalidated") ->
                                    "认证失败，请检查 OpenList 管理员密码"
                                else -> null
                            }
                        }
                        val finalError = friendlyMsg ?: "加载失败: ${e.message}"
                        updateState(
                            CommonStateUpdate(
                                isLoading = false,
                                error = finalError
                            )
                        )
                        syncStackTop { it.copy(isLoading = false, error = finalError) }
                    }
                )
            } catch (e: Exception) {
                val msg = e.message ?: ""
                val isConnErr = msg.contains("Failed to connect") || msg.contains("Unable to resolve")
                if (isConnErr && retryCount < MAX_LIST_RETRIES) {
                    Log.d(TAG, "loadDirectory connect exception, auto-retry ${retryCount + 1}/$MAX_LIST_RETRIES")
                    delay(LIST_RETRY_DELAY_MS)
                    loadDirectoryInternal(path, retryCount + 1)
                    return@launch
                }
                Log.e(TAG, "loadDirectory exception for path=$path: ${e.message}", e)
                val catchError = "加载异常: ${e.message}"
                updateState(
                    CommonStateUpdate(
                        isLoading = false,
                        error = catchError
                    )
                )
                syncStackTop { it.copy(isLoading = false, error = catchError) }
            } finally {
                // 防御性重置：确保 isLoading 在任何情况下都会被重置
                if (readState().isLoading) {
                    Log.w(TAG, "loadDirectory: isLoading still true after completion, force resetting")
                    updateState(CommonStateUpdate(isLoading = false))
                    syncStackTop { it.copy(isLoading = false) }
                }
            }
        }
    }

    // endregion

    // region ==================== 排序 ====================

    fun setSort(orderBy: String, orderDirection: String = "ASC") {
        setSortCommon(orderBy, orderDirection)
    }

    // endregion

    // region ==================== 导航 ====================

    override fun refresh() {
        val state = readState()
        if (state.error != null) {
            viewModelScope.launch {
                initialize()
            }
        } else {
            loadDirectoryInternal(state.currentFileId)
        }
    }

    // endregion

    // region ==================== 足迹（已移除） ====================

    // endregion

    // region ==================== 视频播放 ====================

    fun getPlayUri(resource: WebDavResource): android.net.Uri {
        val baseUrl = "http://127.0.0.1:5244"
        val relativePath = if (resource.path.startsWith(baseUrl)) {
            resource.path.removePrefix(baseUrl)
        } else {
            resource.path
        }
        return android.net.Uri.parse("$baseUrl/d$relativePath")
    }

    fun getBearerToken(): String? = apiClient?.getAdminToken()

    // endregion

    // region ==================== 登出 ====================

    fun logout() {
        logoutCommon()
    }

    /**
     * 从播放历史跳转到指定目录。
     * 直接从 fileId 路径段重建面包屑层次，不再解析 label 字符串。
     * @param fileId 目录路径（如 /Movies/2024）
     * @param label  未使用（保留以兼容调用方）
     */
    fun jumpToFolder(fileId: String, @Suppress("UNUSED_PARAMETER") label: String) {
        if (fileId == "/") {
            jumpToFolderByPath(
                crumbs = listOf(makeBreadcrumb(rootLabel, rootFileId)),
                targetFileId = "/"
            )
            return
        }
        val segments = fileId.trimEnd('/').split("/").filter { it.isNotEmpty() }
        val crumbs = mutableListOf(makeBreadcrumb(rootLabel, rootFileId))
        var accumulatedPath = ""
        for (seg in segments) {
            accumulatedPath += "/$seg"
            crumbs.add(makeBreadcrumb(seg, accumulatedPath))
        }
        jumpToFolderByPath(crumbs, fileId)
    }

    // endregion
}

private fun OpenListFileItem.toWebDavResource(): WebDavResource {
    return WebDavResource(
        name = name,
        path = path,
        isDirectory = isDirectory,
        size = size,
        lastModified = modified,
        thumbnailUrl = thumb.ifBlank { null },
        folderSize = if (isDirectory) size else 0,
        createdAt = created,
    )
}
