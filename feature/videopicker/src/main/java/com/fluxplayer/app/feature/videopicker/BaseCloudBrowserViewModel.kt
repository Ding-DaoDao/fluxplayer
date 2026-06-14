package com.fluxplayer.app.feature.videopicker

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fluxplayer.app.core.common.FluxNotificationDelegate
import com.fluxplayer.app.core.common.PickerUtils
import com.fluxplayer.app.core.data.repository.PlaybackHistoryRepository
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.model.FluxMessageEvent
import com.fluxplayer.app.core.model.WebDavResource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 云盘浏览器通用基类 —— 对应原版 BaseCloudBrowserViewModel<TBreadcrumb>
 *
 * 采用 "状态快照 + 部分更新" 模式：
 * - [CommonStateSnapshot]：不可变的完整状态快照，UI 层通过 [readState] 获取
 * - [CommonStateUpdate]：部分更新，仅设置需要修改的字段，通过 [updateState] 应用
 *
 * 子类必须实现：
 * - [providerLabel] / [prefName] / [rootFileId] / [rootLabel]
 * - [defaultOrderBy] / [defaultOrderDirection]
 * - [breadcrumbLabel] / [breadcrumbFileId] / [makeBreadcrumb]
 * - [doListFiles] / [doCreateFolder] / [doDeleteResource] / [doRenameResource]
 * - [doMoveResource] / [doGetDownloadInfo]
 */
abstract class BaseCloudBrowserViewModel<TBreadcrumb>(
    application: Application
) : AndroidViewModel(application) {

    // region ==================== 统一通知 ====================

    /** 通知事件委托，供子类和 UI 层调用 */
    val notifier = FluxNotificationDelegate(viewModelScope)

    /** 供 UI 层收集的通知事件流 */
    val messageEvents: SharedFlow<FluxMessageEvent> = notifier.events

    // endregion

    // region ==================== 抽象方法（子类必须实现） ====================

    /** 云盘提供商标识，如 "aliyun"、"quark" */
    protected abstract val providerLabel: String

    /** SharedPreferences 名称 */
    protected abstract val prefName: String

    /** 根目录 ID */
    protected abstract val rootFileId: String

    /** 根目录显示标签 */
    protected abstract val rootLabel: String

    /** 默认排序字段 */
    protected abstract val defaultOrderBy: String

    /** 默认排序方向 */
    protected abstract val defaultOrderDirection: String

    /** 从面包屑中提取显示标签（供 UI 层调用） */
    fun breadcrumbLabel(crumb: TBreadcrumb): String = breadcrumbLabelImpl(crumb)

    /** 子类实现面包屑标签提取 */
    protected abstract fun breadcrumbLabelImpl(crumb: TBreadcrumb): String

    /** 从面包屑中提取文件 ID */
    protected abstract fun breadcrumbFileId(crumb: TBreadcrumb): String

    /** 根据标签和 ID 创建面包屑 */
    protected abstract fun makeBreadcrumb(label: String, fileId: String): TBreadcrumb

    // region ==================== 抽象方法（子类必须实现 — API 调用） ====================

    /**
     * 列出文件
     * @return Result<List<WebDavResource>>
     */
    protected abstract suspend fun doListFiles(
        parentFileId: String,
        page: Int?,
        orderBy: String,
        orderDirection: String
    ): Result<List<WebDavResource>>

    /**
     * 创建文件夹
     */
    protected abstract suspend fun doCreateFolder(
        name: String,
        parentFileId: String
    ): Result<Unit>

    /**
     * 删除资源（文件或文件夹）
     */
    protected abstract suspend fun doDeleteResource(
        res: WebDavResource
    ): Result<Unit>

    /**
     * 重命名资源
     */
    protected abstract suspend fun doRenameResource(
        res: WebDavResource,
        newName: String
    ): Result<Unit>

    /**
     * 移动资源
     */
    protected abstract suspend fun doMoveResource(
        fileId: String,
        targetFolderId: String
    ): Result<Unit>



    /**
     * 获取下载信息（URL、headers、文件名）
     */
    protected abstract suspend fun doGetDownloadInfo(
        res: WebDavResource
    ): Result<DownloadInfo>

    // endregion

    // endregion

    // region ==================== 图片 URL 解析 ====================

    /**
     * 获取图片原图 URL + auth headers，供 ImageViewerScreen 使用。
     * 内部调用子类的 [doGetDownloadInfo]。
     */
    suspend fun resolveImageUrl(res: WebDavResource): Pair<String, Map<String, String>>? {
        val info = doGetDownloadInfo(res).getOrNull() ?: return null
        return info.url to info.headers
    }

    // endregion

    // region ==================== 下载信息 ====================

    data class DownloadInfo(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val fileName: String
    )

    // endregion

    // region ==================== 内部状态 ====================

    protected val pageSize: Int = 100

    /** 内存目录缓存 —— 已访问目录的列表，返回上级时直接恢复，不走网络 */
    private val directoryCache = mutableMapOf<String, List<WebDavResource>>()

    /** 导航栈 —— 每层目录的独立状态，用于栈式叠加导航 */
    private val _navigationStack by lazy {
        MutableStateFlow(listOf(DirectoryStackEntry(fileId = rootFileId, label = rootLabel)))
    }
    val navigationStack: StateFlow<List<DirectoryStackEntry>> by lazy { _navigationStack.asStateFlow() }

    /** 同步栈顶与扁平状态（子类可在加载失败等场景调用） */
    protected fun syncStackTop(transform: (DirectoryStackEntry) -> DirectoryStackEntry) {
        _navigationStack.update { stack ->
            if (stack.isEmpty()) return@update stack
            stack.toMutableList().apply {
                set(lastIndex, transform(get(lastIndex)))
            }
        }
    }

    private val _downloadProgress = MutableStateFlow<DownloadProgressData?>(null)
    val downloadProgress: StateFlow<DownloadProgressData?> = _downloadProgress.asStateFlow()

    /** 下载进度数据类 */
    data class DownloadProgressData(
        val fileName: String = "",
        val progress: Float = 0f,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val completedFilePath: String? = null
    )

    /** 暴露给 UI 层的 StateFlow（延迟初始化，等 abstract val 赋值后再创建） */
    protected val _stateFlow by lazy {
        MutableStateFlow(
            CommonStateSnapshot<TBreadcrumb>(
                breadcrumbs = listOf(makeBreadcrumb(rootLabel, rootFileId)),
                orderBy = defaultOrderBy,
                orderDirection = defaultOrderDirection
            )
        )
    }
    val stateFlow: StateFlow<CommonStateSnapshot<TBreadcrumb>> by lazy { _stateFlow.asStateFlow() }

    protected var downloadJob: Job? = null

    // 以下回调由子类在构造函数中通过 initCommonState 注入
    private var readStateFn: (() -> CommonStateSnapshot<TBreadcrumb>)? = null
    private var updateStateFn: ((CommonStateUpdate<TBreadcrumb>) -> Unit)? = null
    private var loadDirectoryFn: ((String) -> Unit)? = null
    private var resetStateFn: (() -> Unit)? = null
    private var getPrefsRepoFn: (() -> PreferencesRepository)? = null

    // endregion

    // region ==================== 初始化 ====================

    /**
     * 子类在构造函数中调用此方法注入回调
     */
    protected fun initCommonState(
        readState: (() -> CommonStateSnapshot<TBreadcrumb>)? = null,
        updateState: ((CommonStateUpdate<TBreadcrumb>) -> Unit)? = null,
        loadDirectory: (String) -> Unit,
        resetState: (() -> Unit)? = null,
        prefsRepo: () -> PreferencesRepository,
    ) {
        this.readStateFn = readState
        this.updateStateFn = updateState
        this.loadDirectoryFn = loadDirectory
        this.resetStateFn = resetState
        this.getPrefsRepoFn = prefsRepo
    }

    // endregion

    // region ==================== 状态读取与更新 ====================

    protected fun readState(): CommonStateSnapshot<TBreadcrumb> {
        // 优先使用自定义回调，否则使用默认 _stateFlow
        return readStateFn?.invoke() ?: _stateFlow.value
    }

    protected fun updateState(update: CommonStateUpdate<TBreadcrumb>) {
        // 优先使用自定义回调，否则直接更新 _stateFlow
        val fn = updateStateFn
        if (fn != null) {
            fn(update)
        } else {
            val current = _stateFlow.value
            _stateFlow.value = current.copy(
                items = update.items ?: current.items,
                currentFileId = update.currentFileId ?: current.currentFileId,
                breadcrumbs = update.breadcrumbs ?: current.breadcrumbs,
                isLoading = update.isLoading ?: current.isLoading,
                isLoadingMore = update.isLoadingMore ?: current.isLoadingMore,
                hasMore = update.hasMore ?: current.hasMore,
                currentPage = update.currentPage ?: current.currentPage,
                isLoggedIn = update.isLoggedIn ?: current.isLoggedIn,
                error = update.error ?: current.error,
                orderBy = update.orderBy ?: current.orderBy,
                orderDirection = update.orderDirection ?: current.orderDirection,
                scrollTargetIndex = update.scrollTargetIndex ?: current.scrollTargetIndex,
                scrollTargetOffset = update.scrollTargetOffset ?: current.scrollTargetOffset,
                scrollTargetParentKey = update.scrollTargetParentKey ?: current.scrollTargetParentKey,
                pendingAction = update.pendingAction ?: current.pendingAction,
                moveFileId = update.moveFileId ?: current.moveFileId,
                pickerFolders = update.pickerFolders ?: current.pickerFolders,
                pickerIsLoading = update.pickerIsLoading ?: current.pickerIsLoading,
                playedUriSet = update.playedUriSet ?: current.playedUriSet
            )
        }
    }

    // endregion

    // region ==================== 导航 ====================

    /**
     * 子类可重写：对缓存数据按当前排序设置重新排序。
     * 默认不排序（API 已排序的情况）。
     */
    protected open fun sortItems(items: List<WebDavResource>): List<WebDavResource> = items

    /**
     * 加载目录（优先从内存缓存恢复）
     */
    protected fun loadDirectoryCached(fileId: String) {
        val cached = directoryCache[fileId]
        Log.d("BaseCloudVM", "loadDirectoryCached: fileId=$fileId, cacheHit=${cached != null}, cacheSize=${directoryCache.size}")
        if (cached != null) {
            val sorted = sortItems(cached)
            updateState(
                CommonStateUpdate(
                    items = sorted,
                    currentFileId = fileId,
                    isLoading = false,
                    error = null
                )
            )
            syncStackTop { it.copy(items = sorted, isLoading = false, error = null) }
        } else {
            updateState(CommonStateUpdate(items = emptyList(), currentFileId = fileId))
            syncStackTop { it.copy(items = emptyList(), isLoading = true, error = null) }
            loadDirectoryFn?.invoke(fileId)
        }
    }

    /** 进入指定索引的目录 */
    open fun navigateToDir(index: Int) {
        val state = readState()
        val item = state.items.getOrNull(index) ?: return
        if (!item.isDirectory) return

        val fileId = item.path

        updateState(
            CommonStateUpdate(
                breadcrumbs = state.breadcrumbs + makeBreadcrumb(item.name, fileId)
            )
        )
        _navigationStack.update { it + DirectoryStackEntry(fileId = fileId, label = item.name) }
        loadDirectoryCached(fileId)
    }

    fun clearScrollTarget() {
        updateState(CommonStateUpdate(scrollTargetIndex = -1))
    }

    /** 返回上级目录 */
    open fun navigateUp() {
        val state = readState()
        val breadcrumbs = state.breadcrumbs
        if (breadcrumbs.size <= 1) return

        val target = breadcrumbs[breadcrumbs.size - 2]
        val parentFileId = breadcrumbFileId(target)

        updateState(
            CommonStateUpdate(
                breadcrumbs = breadcrumbs.dropLast(1),
                scrollTargetIndex = -1,
                scrollTargetParentKey = null
            )
        )
        _navigationStack.update { if (it.size > 1) it.dropLast(1) else it }
        loadDirectoryCached(parentFileId)
    }

    /** 跳转到指定面包屑位置 */
    open fun navigateToBreadcrumb(index: Int) {
        val breadcrumbs = readState().breadcrumbs
        if (index >= breadcrumbs.size) return

        val target = breadcrumbs[index]
        val targetFileId = breadcrumbFileId(target)
        if (targetFileId.isEmpty()) return

        updateState(
            CommonStateUpdate(
                breadcrumbs = breadcrumbs.subList(0, index + 1),
                scrollTargetIndex = -1,
                scrollTargetParentKey = null
            )
        )
        _navigationStack.update { it.take(index + 1) }
        loadDirectoryCached(targetFileId)
    }

    /**
     * 通用跳转到指定文件夹（通过 label|fileId 路径格式）
     * label 格式如 "dir1|id1/dir2|id2"
     */
    protected fun jumpToFolderCommon(label: String, fileId: String) {
        val state = readState()

        val segments = label.split("/")
        val firstCrumb = state.breadcrumbs.firstOrNull()
        val rootLbl = firstCrumb?.let { breadcrumbLabel(it) } ?: rootLabel
        val rootFid = firstCrumb?.let { breadcrumbFileId(it) } ?: rootFileId

        val root = makeBreadcrumb(rootLbl, rootFid)
        val pathCrumbs = segments.map { seg ->
            val parts = seg.split("|", limit = 2)
            makeBreadcrumb(parts[0], if (parts.size > 1) parts[1] else "")
        }

        updateState(
            CommonStateUpdate(
                items = emptyList(),
                breadcrumbs = listOf(root) + pathCrumbs
            )
        )
        loadDirectoryFn?.invoke(fileId)
    }

    /**
     * 从已构建的面包屑列表重建导航栈并加载目标目录。
     * 用于播放历史跳转 —— 直接从路径段重建面包屑层次，
     * 不再依赖 label 字符串解析（旧方案有 split("/") 歧义 bug）。
     *
     * @param crumbs  完整面包屑列表（含根节点）
     * @param targetFileId 目标目录 fileId
     */
    protected fun jumpToFolderByPath(
        crumbs: List<TBreadcrumb>,
        targetFileId: String,
    ) {
        // 清除目标目录缓存，确保从网络重新加载
        directoryCache.remove(targetFileId)
        // 从面包屑重建导航栈
        _navigationStack.value = crumbs.map { crumb ->
            DirectoryStackEntry(
                fileId = breadcrumbFileId(crumb),
                label = breadcrumbLabelImpl(crumb),
                items = emptyList(),
                isLoading = true,
            )
        }
        updateState(
            CommonStateUpdate(
                items = emptyList(),
                breadcrumbs = crumbs,
                currentFileId = targetFileId,
            )
        )
        loadDirectoryFn?.invoke(targetFileId)
    }

    // endregion

    // region ==================== 排序 ====================

    protected fun setSortCommon(orderBy: String, orderDirection: String = "") {
        val dir = orderDirection.ifEmpty { readState().orderDirection }
        updateState(
            CommonStateUpdate(
                orderBy = orderBy,
                orderDirection = dir
            )
        )
        loadDirectoryFn?.invoke(readState().currentFileId)
    }

    // endregion

    // region ==================== 刷新 ====================

    open fun refresh() {
        val state = readState()
        loadDirectoryFn?.invoke(state.currentFileId)
    }

    // endregion

    // region ==================== 足迹 ====================

    protected var playbackHistoryRepository: PlaybackHistoryRepository? = null

    protected fun initFootprint(repo: PlaybackHistoryRepository) {
        this.playbackHistoryRepository = repo
        viewModelScope.launch {
            repo.getHistoryFlow().collect { history ->
                // 按 parentPath 分组，每组只保留最新一条
                val latestHistory = history
                    .filter { it.parentPath != null }
                    .groupBy { it.parentPath!! }
                    .mapValues { (_, list) -> list.maxByOrNull { it.lastPlayedTime }!! }
                    .values.toList()
                val historyUris = latestHistory.flatMap { item ->
                    val uri = item.uriString
                    val parts = mutableListOf(uri)
                    if (uri.startsWith("http://") || uri.startsWith("https://")) {
                        try {
                            val parsed = Uri.parse(uri)
                            val urlPath = parsed.path
                            if (!urlPath.isNullOrEmpty()) {
                                parts.add(urlPath)
                                if (urlPath.startsWith("/d/")) {
                                    parts.add(urlPath.removePrefix("/d"))
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    parts
                }.toSet()
                Log.d("BaseCloudVM", "[Footprint] provider=$providerLabel latestPerDirSize=${latestHistory.size} playedUriSetSize=${historyUris.size}")
                val sample = historyUris.take(5).joinToString("|")
                Log.d("BaseCloudVM", "[Footprint] sample uris: $sample")
                updateState(CommonStateUpdate(playedUriSet = historyUris))
            }
        }
    }

    // endregion

    // region ==================== 目录加载回调 ====================

    protected fun onDirectoryLoaded(
        parentFileId: String,
        resources: List<WebDavResource>,
        hasMore: Boolean
    ) {
        directoryCache[parentFileId] = resources
        Log.d("BaseCloudVM", "onDirectoryLoaded: cached parentFileId=$parentFileId, items=${resources.size}, cacheSize=${directoryCache.size}")

        updateState(
            CommonStateUpdate(
                items = resources,
                isLoading = false,
                hasMore = hasMore,
                currentPage = 1
            )
        )
        syncStackTop { it.copy(items = resources, isLoading = false, error = null) }
    }

    /**
     * Token 过期回调（供子类重写）
     */
    protected open fun onTokenExpired() {
        // 默认清除登录状态
        updateState(CommonStateUpdate(isLoggedIn = false))
    }

    protected fun onDirectoryLoadFailed(
        parentFileId: String,
        errorMessage: String,
        providerName: String = providerLabel.uppercase(Locale.ROOT),
        onTokenExpiredCallback: () -> Unit = {}
    ) {
        val friendly = when {
            errorMessage.contains("require login", ignoreCase = true) ||
                    errorMessage.contains("token", ignoreCase = true) ||
                    errorMessage.contains("invalid", ignoreCase = true) ||
                    errorMessage.contains("401") -> {
                onTokenExpiredCallback()
                onTokenExpired()
                "登录已过期，请重新登录"
            }

            errorMessage.contains("Failed to connect", ignoreCase = true) ||
                    errorMessage.contains("Unable to resolve", ignoreCase = true) ->
                "无法连接到${providerName}网盘服务"

            else -> "加载失败: $errorMessage"
        }

        updateState(CommonStateUpdate(error = friendly))
        syncStackTop { it.copy(error = friendly) }

        if (!friendly.contains("登录已过期")) {
            updateState(CommonStateUpdate(isLoading = false))
            syncStackTop { it.copy(isLoading = false) }
        }
    }

    // endregion

    // region ==================== 加载更多 ====================

    protected fun loadMoreCommon(
        getItems: () -> List<WebDavResource>,
        onFilesLoaded: (List<WebDavResource>) -> List<WebDavResource>
    ) {
        val state = readState()
        if (!state.hasMore || state.isLoadingMore || state.isLoading) return

        val nextPage = state.currentPage + 1
        updateState(CommonStateUpdate(isLoadingMore = true))

        viewModelScope.launch {
            val result = doListFiles(
                parentFileId = state.currentFileId,
                page = nextPage,
                orderBy = state.orderBy,
                orderDirection = state.orderDirection
            )

            result.fold(
                onSuccess = { newItems ->
                    val existingPaths = getItems().map { it.path }.toSet()
                    val filtered = onFilesLoaded(newItems).filter { it.path !in existingPaths }
                    val combined = sortItems(getItems() + filtered)

                    updateState(
                        CommonStateUpdate(
                            items = combined,
                            isLoadingMore = false,
                            hasMore = newItems.size >= pageSize,
                            currentPage = nextPage
                        )
                    )
                },
                onFailure = { e ->
                    updateState(
                        CommonStateUpdate(
                            isLoadingMore = false,
                            error = "加载更多失败: ${e.message}"
                        )
                    )
                }
            )
        }
    }

    // endregion

    // region ==================== CRUD ====================

    fun createDirectory(name: String) {
        viewModelScope.launch {
            val state = readState()
            updateState(CommonStateUpdate(isLoading = true))

            val result = doCreateFolder(name, state.currentFileId)
            result.fold(
                onSuccess = {
                    notifier.success("文件夹创建成功")
                    refresh()
                },
                onFailure = { e ->
                    notifier.error("创建失败: ${e.message}")
                    updateState(CommonStateUpdate(isLoading = false))
                }
            )
        }
    }

    fun deleteItem(index: Int) {
        val item = readState().items.getOrNull(index) ?: return

        viewModelScope.launch {
            updateState(CommonStateUpdate(isLoading = true))

            val result = doDeleteResource(item)
            result.fold(
                onSuccess = {
                    notifier.success("删除成功")
                    refresh()
                },
                onFailure = { e ->
                    notifier.error("删除失败: ${e.message}")
                    updateState(CommonStateUpdate(isLoading = false))
                }
            )
        }
    }

    fun renameItem(index: Int, newName: String) {
        val item = readState().items.getOrNull(index) ?: return

        viewModelScope.launch {
            updateState(CommonStateUpdate(isLoading = true))

            val result = doRenameResource(item, newName)
            result.fold(
                onSuccess = {
                    notifier.success("重命名成功")
                    refresh()
                },
                onFailure = { e ->
                    notifier.error("重命名失败: ${e.message}")
                    updateState(CommonStateUpdate(isLoading = false))
                }
            )
        }
    }

    // endregion

    // region ==================== 下载 ====================

    /**
     * 子类可重写以注入 CloudDownloadRepository
     */
    protected open var cloudDownloadRepository: com.fluxplayer.app.core.data.repository.CloudDownloadRepository? = null

    fun downloadFile(index: Int) {
        val res = readState().items.getOrNull(index) ?: return
        val repo = cloudDownloadRepository
        if (repo == null) {
            // 回退：无 repo 时使用旧逻辑
            downloadFileLegacy(index)
            return
        }

        Log.d("BaseCloudVM", "downloadFile: index=$index, name=${res.name}, path=${res.path}")

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            try {
                val infoResult = doGetDownloadInfo(res)
                if (infoResult.isFailure) {
                    val e = infoResult.exceptionOrNull()!!
                    Log.e("BaseCloudVM", "获取下载链接失败", e)
                    _downloadProgress.value = null
                    notifier.error("获取下载链接失败: ${e.message}")
                    return@launch
                }

                val info = infoResult.getOrThrow()
                Log.d("BaseCloudVM", "获取下载链接成功: url=${info.url.take(200)}, fileName=${info.fileName}")

                _downloadProgress.value = DownloadProgressData(
                    fileName = info.fileName,
                    progress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = 0L
                )

                // 收集下载事件
                val eventJob = launch {
                    repo.downloadEvents.collect { event ->
                        when (event) {
                            is com.fluxplayer.app.core.data.repository.CloudDownloadRepository.DownloadEvent.Progress -> {
                                if (event.fileName == info.fileName) {
                                    _downloadProgress.value = DownloadProgressData(
                                        fileName = event.fileName,
                                        progress = event.progress,
                                        downloadedBytes = event.downloadedBytes,
                                        totalBytes = event.totalBytes
                                    )
                                }
                            }

                            is com.fluxplayer.app.core.data.repository.CloudDownloadRepository.DownloadEvent.Completed -> {
                                if (event.fileName == info.fileName) {
                                    _downloadProgress.value = DownloadProgressData(
                                        fileName = event.fileName,
                                        progress = 1f,
                                        completedFilePath = event.filePath
                                    )
                                    notifier.success("下载完成: ${event.fileName}")
                                }
                            }

                            is com.fluxplayer.app.core.data.repository.CloudDownloadRepository.DownloadEvent.Failed -> {
                                if (event.fileName == info.fileName) {
                                    _downloadProgress.value = null
                                    if (event.error != "下载已取消") {
                                        notifier.error("下载失败: ${event.error}")
                                    }
                                }
                            }
                        }
                    }
                }

                repo.download(
                    url = info.url,
                    fileName = info.fileName,
                    headers = info.headers,
                    provider = providerLabel
                )

                eventJob.cancel()

            } catch (e: Exception) {
                Log.e("BaseCloudVM", "下载异常", e)
                notifier.error("下载失败: ${e.message}")
                _downloadProgress.value = null
            }
        }
    }

    /**
     * 旧版下载逻辑（无 CloudDownloadRepository 时回退）
     */
    private fun downloadFileLegacy(index: Int) {
        val res = readState().items.getOrNull(index) ?: return
        Log.d("BaseCloudVM", "downloadFileLegacy: index=$index, name=${res.name}")

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            try {
                val infoResult = doGetDownloadInfo(res)
                if (infoResult.isFailure) {
                    val e = infoResult.exceptionOrNull()!!
                    Log.e("BaseCloudVM", "获取下载链接失败", e)
                    _downloadProgress.value = null
                    notifier.error("获取下载链接失败: ${e.message}")
                    return@launch
                }
                val info = infoResult.getOrThrow()
                _downloadProgress.value = DownloadProgressData(
                    fileName = info.fileName,
                    progress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = 0L
                )

                val dm = getApplication<Application>()
                    .getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                val request = android.app.DownloadManager.Request(android.net.Uri.parse(info.url)).apply {
                    setTitle(info.fileName)
                    setDescription("正在下载...")
                    setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, info.fileName)
                    info.headers.forEach { (key, value) -> addRequestHeader(key, value) }
                }
                dm.enqueue(request)
                notifier.info("开始下载: ${info.fileName}")
                _downloadProgress.value = null
            } catch (e: Exception) {
                Log.e("BaseCloudVM", "下载异常", e)
                notifier.error("下载失败: ${e.message}")
                _downloadProgress.value = null
            }
        }
    }

    fun dismissDownloadProgress() {
        _downloadProgress.value = null
        cloudDownloadRepository?.cancel()
    }

    fun openDownloadedFile(filePath: String) {
        val context = getApplication<Application>()
        val file = File(filePath)
        if (!file.exists()) {
            notifier.error("文件不存在")
            return
        }
        try {
            val uri = try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
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
            Log.e("BaseCloudVM", "打开文件失败", e)
            notifier.error("无法打开文件: ${e.message}")
        }
    }

    // endregion

    // region ==================== 移动 / 复制 ====================

    fun startMove(index: Int) {
        val item = readState().items.getOrNull(index) ?: return
        updateState(
            CommonStateUpdate(
                pendingAction = "move",
                moveFileId = item.path
            )
        )
    }

    fun dismissPicker() {
        updateState(
            CommonStateUpdate(
                pendingAction = null,
                moveFileId = null,
                pickerFolders = emptyList(),
                pickerIsLoading = false
            )
        )
    }

    fun loadFoldersForPicker(folderId: String) {
        viewModelScope.launch {
            val state = readState()
            updateState(CommonStateUpdate(pickerIsLoading = true))

            val actualFolderId = folderId.ifEmpty { rootFileId }

            val result = doListFiles(
                parentFileId = actualFolderId,
                page = null,
                orderBy = state.orderBy,
                orderDirection = state.orderDirection
            )

            result.fold(
                onSuccess = { files ->
                    val operatingPath = when (state.pendingAction) {
                        "move" -> state.moveFileId
                        else -> null
                    }
                    val folders = files
                        .filter { it.isDirectory && it.path != operatingPath }
                        .map { it.copy() }

                    updateState(
                        CommonStateUpdate(
                            pickerFolders = folders,
                            pickerIsLoading = false
                        )
                    )
                },
                onFailure = { e ->
                    updateState(
                        CommonStateUpdate(
                            pickerFolders = emptyList(),
                            pickerIsLoading = false
                        )
                    )
                    notifier.error("加载文件夹失败: ${e.message}")
                }
            )
        }
    }

    fun createFolderInPicker(parentFolderId: String, name: String) {
        viewModelScope.launch {
            updateState(CommonStateUpdate(pickerIsLoading = true))

            val actualParentId = parentFolderId.ifEmpty { rootFileId }
            val result = doCreateFolder(name, actualParentId)

            result.fold(
                onSuccess = {
                    notifier.success("文件夹创建成功")
                    loadFoldersForPicker(parentFolderId)
                },
                onFailure = { e ->
                    notifier.error("创建失败: ${e.message}")
                    updateState(CommonStateUpdate(pickerIsLoading = false))
                }
            )
        }
    }

    fun moveTo(targetFolderId: String) {
        val fileId = readState().moveFileId ?: return
        val actualTargetId = targetFolderId.ifEmpty { rootFileId }

        viewModelScope.launch {
            updateState(CommonStateUpdate(isLoading = true))

            val result = doMoveResource(fileId, actualTargetId)
            result.fold(
                onSuccess = {
                    notifier.success("移动成功")
                    dismissPicker()
                    refresh()
                },
                onFailure = { e ->
                    notifier.error("移动失败: ${e.message}")
                    updateState(CommonStateUpdate(isLoading = false))
                }
            )
        }
    }

    // endregion

    // region ==================== 登出 ====================

    protected fun logoutCommon() {
        val prefs = getApplication<Application>().getSharedPreferences(prefName, 0)
        prefs.edit().clear().apply()
        directoryCache.clear()
        _navigationStack.value = listOf(DirectoryStackEntry(fileId = rootFileId, label = rootLabel))
        val fn = resetStateFn
        if (fn != null) {
            fn()
        } else {
            _stateFlow.value = CommonStateSnapshot(
                breadcrumbs = listOf(makeBreadcrumb(rootLabel, rootFileId)),
                orderBy = defaultOrderBy,
                orderDirection = defaultOrderDirection
            )
        }
    }

    // endregion

    // region ==================== 工具方法 ====================

    @Deprecated(
        "使用 notifier.success/error/info 代替",
        ReplaceWith("notifier.success(msg)", "com.fluxplayer.app.core.common.FluxNotificationDelegate"),
    )
    protected fun snackbar(msg: String) {
        notifier.info(msg)
    }

    protected fun formatTimestamp(ts: Long): String {
        if (ts <= 0) return ""
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            sdf.format(Date(ts))
        } catch (_: Exception) {
            ""
        }
    }

    protected fun formatDateString(dateStr: String): String {
        if (dateStr.isBlank()) return ""
        return try {
            val inputFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val outputFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val date = inputFmt.parse(dateStr.take(19))
            date?.let { outputFmt.format(it) } ?: ""
        } catch (_: Exception) {
            dateStr.take(10)
        }
    }

    // endregion
}
