package dev.anilbeesetti.nextplayer.feature.videopicker

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.common.PickerUtils
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.model.WebDavResource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * - [doMoveResource] / [doCopyResource] / [doGetDownloadInfo]
 */
abstract class BaseCloudBrowserViewModel<TBreadcrumb>(
    application: Application
) : AndroidViewModel(application) {

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
     * 复制资源
     */
    protected abstract suspend fun doCopyResource(
        copyFileId: String,
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

    // region ==================== 下载信息 ====================

    data class DownloadInfo(
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val fileName: String
    )

    // endregion

    // region ==================== 内部状态 ====================

    protected val pageSize: Int = 100

    private val _downloadProgress = MutableStateFlow<DownloadProgressData?>(null)
    val downloadProgress: StateFlow<DownloadProgressData?> = _downloadProgress.asStateFlow()

    /** 下载进度数据类 */
    data class DownloadProgressData(
        val fileName: String = "",
        val progress: Float = 0f,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0
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
    private var recordFootprintFn: ((String) -> Unit)? = null

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
        recordFootprint: (String) -> Unit = {}
    ) {
        this.readStateFn = readState
        this.updateStateFn = updateState
        this.loadDirectoryFn = loadDirectory
        this.resetStateFn = resetState
        this.getPrefsRepoFn = prefsRepo
        this.recordFootprintFn = recordFootprint
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
                currentFootprint = update.currentFootprint ?: current.currentFootprint,
                scrollTargetIndex = update.scrollTargetIndex ?: current.scrollTargetIndex,
                scrollTargetParentKey = update.scrollTargetParentKey ?: current.scrollTargetParentKey,
                pendingAction = update.pendingAction ?: current.pendingAction,
                moveFileId = update.moveFileId ?: current.moveFileId,
                copyFileId = update.copyFileId ?: current.copyFileId,
                pickerFolders = update.pickerFolders ?: current.pickerFolders,
                pickerIsLoading = update.pickerIsLoading ?: current.pickerIsLoading
            )
        }
    }

    // endregion

    // region ==================== 导航 ====================

    /** 进入指定索引的目录 */
    open fun navigateToDir(index: Int) {
        val state = readState()
        val item = state.items.getOrNull(index) ?: return
        if (!item.isDirectory) return

        val fileId = item.path
        val parentKey = state.breadcrumbs.joinToString("/") { breadcrumbLabel(it) }

        updateState(
            CommonStateUpdate(
                items = emptyList(),
                breadcrumbs = state.breadcrumbs + makeBreadcrumb(item.name, fileId),
                scrollTargetIndex = index,
                scrollTargetParentKey = parentKey
            )
        )
        loadDirectoryFn?.invoke(fileId)
    }

    fun clearScrollTarget() {
        updateState(CommonStateUpdate(scrollTargetIndex = -1))
    }

    /** 返回上级目录 */
    open fun navigateUp() {
        val breadcrumbs = readState().breadcrumbs
        if (breadcrumbs.size <= 1) return

        val target = breadcrumbs[breadcrumbs.size - 2]
        updateState(
            CommonStateUpdate(
                breadcrumbs = breadcrumbs.dropLast(1)
            )
        )
        loadDirectoryFn?.invoke(breadcrumbFileId(target))
    }

    /** 跳转到指定面包屑位置 */
    open fun navigateToBreadcrumb(index: Int) {
        val breadcrumbs = readState().breadcrumbs
        if (index >= breadcrumbs.size) return

        val target = breadcrumbs[index]
        if (breadcrumbFileId(target).isEmpty()) return

        updateState(
            CommonStateUpdate(
                breadcrumbs = breadcrumbs.subList(0, index + 1)
            )
        )
        loadDirectoryFn?.invoke(breadcrumbFileId(target))
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
        // CloudDirectoryCache 是 @Singleton 注入类，子类可按需调用
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

    protected fun recordFootprintCommon(path: String) {
        val dir = readState().currentFileId
        recordFootprintFn?.invoke(path)

        viewModelScope.launch {
            try {
                val prefs = getPrefsRepoFn?.invoke() ?: return@launch
                val appPrefs = prefs.applicationPreferences.value
                val footprintMap = appPrefs.latestFootprintPerDir.toMutableMap()
                footprintMap["$providerLabel:$dir"] = path
                prefs.updateApplicationPreferences { it.copy(latestFootprintPerDir = footprintMap) }
            } catch (_: Exception) {
                // 足迹记录失败不影响主流程
            }
        }
    }

    // endregion

    // region ==================== 缓存 ====================

    protected fun cacheKey(fileId: String? = null): String {
        return "$providerLabel:${fileId ?: readState().currentFileId}"
    }

    protected fun tryLoadFromCache(parentFileId: String): Boolean {
        val key = cacheKey(parentFileId)
        // CloudDirectoryCache 是 @Singleton 注入类，需要通过 Hilt 获取
        // 这里直接跳过缓存（子类可按需重写）
        return false
        /*
        val cached = CloudDirectoryCache.getCachedDirectory(providerLabel, parentFileId)
        if (cached == null) return false

        updateState(
            CommonStateUpdate(
                items = cached.items,
                currentFileId = parentFileId,
                isLoading = false,
                isLoadingMore = false,
                hasMore = true,
                currentPage = 1
            )
        )

        // 异步加载足迹
        viewModelScope.launch {
            try {
                val prefs = getPrefsRepoFn?.invoke() ?: return@launch
                val fp = prefs.applicationPreferences.value.latestFootprintPerDir[key]
                updateState(CommonStateUpdate(currentFootprint = fp))
            } catch (_: Exception) { }
        }

        return true
        */
    }

    // endregion

    // region ==================== 目录加载回调 ====================

    protected fun onDirectoryLoaded(
        parentFileId: String,
        resources: List<WebDavResource>,
        hasMore: Boolean
    ) {
        // CloudDirectoryCache 是 @Singleton 注入类，子类可按需调用
        // CloudDirectoryCache.cacheDirectory(...)

        viewModelScope.launch {
            try {
                val prefs = getPrefsRepoFn?.invoke()
                val fp = prefs?.applicationPreferences?.value?.latestFootprintPerDir?.get(cacheKey(parentFileId))
                updateState(
                    CommonStateUpdate(
                        items = resources,
                        isLoading = false,
                        hasMore = hasMore,
                        currentPage = 1,
                        currentFootprint = fp
                    )
                )
            } catch (_: Exception) {
                updateState(
                    CommonStateUpdate(
                        items = resources,
                        isLoading = false,
                        hasMore = hasMore,
                        currentPage = 1
                    )
                )
            }
        }
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

        if (!friendly.contains("登录已过期")) {
            updateState(CommonStateUpdate(isLoading = false))
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

                    updateState(
                        CommonStateUpdate(
                            items = getItems() + filtered,
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
                    Toast.makeText(getApplication(), "文件夹创建成功", Toast.LENGTH_SHORT).show()
                    refresh()
                },
                onFailure = { e ->
                    Toast.makeText(getApplication(), "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(getApplication(), "删除成功", Toast.LENGTH_SHORT).show()
                    refresh()
                },
                onFailure = { e ->
                    Toast.makeText(getApplication(), "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(getApplication(), "重命名成功", Toast.LENGTH_SHORT).show()
                    refresh()
                },
                onFailure = { e ->
                    Toast.makeText(getApplication(), "重命名失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    updateState(CommonStateUpdate(isLoading = false))
                }
            )
        }
    }

    // endregion

    // region ==================== 下载 ====================

    fun downloadFile(index: Int) {
        val res = readState().items.getOrNull(index) ?: return
        Log.d("BaseCloudVM", "downloadFile: index=$index, name=${res.name}, path=${res.path}")

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            try {
                // 获取下载信息
                val infoResult = doGetDownloadInfo(res)
                if (infoResult.isFailure) {
                    val e = infoResult.exceptionOrNull()!!
                    Log.e("BaseCloudVM", "获取下载链接失败", e)
                    _downloadProgress.value = null
                    Toast.makeText(
                        getApplication(),
                        "获取下载链接失败: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val info = infoResult.getOrThrow()
                Log.d(
                    "BaseCloudVM",
                    "获取下载链接成功: url=${info.url.take(200)}, fileName=${info.fileName}, headers=${
                        info.headers.map { "${it.key}: ${it.value.take(30)}" }
                    }"
                )

                // 初始化进度
                _downloadProgress.value = DownloadProgressData(
                    fileName = info.fileName,
                    progress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = 0L
                )

                // 使用系统 DownloadManager 下载
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

                Toast.makeText(
                    getApplication(),
                    "开始下载: ${info.fileName}",
                    Toast.LENGTH_SHORT
                ).show()

                _downloadProgress.value = null

            } catch (e: Exception) {
                Log.e("BaseCloudVM", "下载异常", e)
                Toast.makeText(getApplication(), "下载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                _downloadProgress.value = null
            }
        }
    }

    fun dismissDownloadProgress() {
        _downloadProgress.value = null
    }

    fun openDownloadedFile(filePath: String) {
        val context = getApplication<Application>()
        val file = File(filePath)
        if (!file.exists()) {
            Toast.makeText(context, "文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, PickerUtils.getMimeType(filePath))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("BaseCloudVM", "打开文件失败", e)
            Toast.makeText(context, "无法打开文件: ${e.message}", Toast.LENGTH_SHORT).show()
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

    fun startCopy(index: Int) {
        val item = readState().items.getOrNull(index) ?: return
        updateState(
            CommonStateUpdate(
                pendingAction = "copy",
                copyFileId = item.path
            )
        )
    }

    fun dismissPicker() {
        updateState(
            CommonStateUpdate(
                pendingAction = null,
                moveFileId = null,
                copyFileId = null,
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
                        "copy" -> state.copyFileId
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
                    Toast.makeText(
                        getApplication(),
                        "加载文件夹失败: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
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
                    Toast.makeText(getApplication(), "文件夹创建成功", Toast.LENGTH_SHORT).show()
                    loadFoldersForPicker(parentFolderId)
                },
                onFailure = { e ->
                    Toast.makeText(getApplication(), "创建失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    updateState(CommonStateUpdate(pickerIsLoading = false))
                }
            )
        }
    }

    fun moveTo(targetFolderId: String) {
        val fileId = readState().moveFileId ?: return

        viewModelScope.launch {
            updateState(CommonStateUpdate(isLoading = true))

            val result = doMoveResource(fileId, targetFolderId)
            result.fold(
                onSuccess = {
                    Toast.makeText(getApplication(), "移动成功", Toast.LENGTH_SHORT).show()
                    dismissPicker()
                    refresh()
                },
                onFailure = { e ->
                    Toast.makeText(getApplication(), "移动失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    updateState(CommonStateUpdate(isLoading = false))
                }
            )
        }
    }

    fun copyTo(targetFolderId: String) {
        val copyId = readState().copyFileId ?: return

        viewModelScope.launch {
            updateState(CommonStateUpdate(isLoading = true))

            val result = doCopyResource(copyId, targetFolderId)
            result.fold(
                onSuccess = {
                    Toast.makeText(getApplication(), "复制成功", Toast.LENGTH_SHORT).show()
                    dismissPicker()
                    refresh()
                },
                onFailure = { e ->
                    Toast.makeText(getApplication(), "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
        // CloudDirectoryCache 是 @Singleton 注入类，子类可按需调用
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

    protected fun snackbar(msg: String) {
        Toast.makeText(getApplication(), msg, Toast.LENGTH_SHORT).show()
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
