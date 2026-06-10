package dev.anilbeesetti.nextplayer.feature.videopicker.openlist

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.anilbeesetti.nextplayer.core.data.openlist.OpenListApiClient
import dev.anilbeesetti.nextplayer.core.data.openlist.OpenListFileItem
import dev.anilbeesetti.nextplayer.core.data.openlist.OpenListManagerProvider
import dev.anilbeesetti.nextplayer.core.data.openlist.OpenListServerState
import dev.anilbeesetti.nextplayer.core.data.openlist.OpenListTokenProvider
import dev.anilbeesetti.nextplayer.core.data.repository.PlaybackHistoryRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.model.WebDavResource
import dev.anilbeesetti.nextplayer.feature.videopicker.BaseCloudBrowserViewModel
import dev.anilbeesetti.nextplayer.feature.videopicker.CommonStateUpdate
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OpenListBreadcrumb(val label: String, val path: String)

@HiltViewModel
class OpenListBrowserViewModel @Inject constructor(
    application: Application,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val preferencesRepository: PreferencesRepository,
) : BaseCloudBrowserViewModel<OpenListBreadcrumb>(application) {

    companion object {
        private const val TAG = "OpenListBrowserVM"
        private const val PREF_NAME = "openlist"
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
        return client.listFiles(parentFileId, currentAdminPassword).map { items ->
            items.map { it.toWebDavResource() }
        }
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

    private fun loadDirectoryInternal(path: String) {
        val client = apiClient ?: run {
            Log.e(TAG, "loadDirectory: apiClient is null!")
            return
        }
        Log.d(TAG, "loadDirectory: path=$path, hasPassword=${currentAdminPassword != null}")
        updateState(CommonStateUpdate(isLoading = true, error = null))

        viewModelScope.launch {
            val result = client.listFiles(path, currentAdminPassword)
            result.fold(
                onSuccess = { items ->
                    Log.d(TAG, "loadDirectory success: ${items.size} items for path=$path")
                    val resources = items.map { it.toWebDavResource() }
                    val freshPrefs = preferencesRepository.applicationPreferences.value
                    updateState(
                        CommonStateUpdate(
                            items = resources,
                            currentFileId = path,
                            isLoading = false,
                            currentFootprint = freshPrefs.latestFootprintPerDir[path]
                        )
                    )
                },
                onFailure = { e ->
                    Log.e(TAG, "loadDirectory failed for path=$path: ${e.message}")
                    val friendlyMsg = e.message?.let { msg ->
                        when {
                            msg.contains("storage not found") || msg.contains("add a storage") ->
                                "OpenList 中没有挂载任何存储，请在 OpenList 网页后台添加存储"
                            msg.contains("Failed to connect") ->
                                "无法连接到 OpenList 服务（127.0.0.1:5244），请确认服务已启动"
                            msg.contains("401") || msg.contains("token is invalidated") ->
                                "认证失败，请检查 OpenList 管理员密码"
                            else -> null
                        }
                    }
                    updateState(
                        CommonStateUpdate(
                            isLoading = false,
                            error = friendlyMsg ?: "加载失败: ${e.message}"
                        )
                    )
                }
            )
        }
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

    // region ==================== 足迹 ====================

    fun recordFootprint(path: String) {
        recordFootprintCommon(path)
    }

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

    // endregion
}

private fun OpenListFileItem.toWebDavResource(): WebDavResource {
    return WebDavResource(
        name = name,
        path = path,
        isDirectory = isDirectory,
        size = size,
        lastModified = modified,
    )
}
