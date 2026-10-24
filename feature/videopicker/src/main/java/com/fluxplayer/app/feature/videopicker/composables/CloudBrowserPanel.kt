package com.fluxplayer.app.feature.videopicker.composables

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.data.cloud.CloudDirectorySearchCache
import com.fluxplayer.app.core.data.cloud.CloudRemoteFile
import com.fluxplayer.app.core.data.cloud.CloudSearchCategory
import com.fluxplayer.app.core.model.FluxMessageEvent
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.components.CancelButton
import com.fluxplayer.app.core.ui.components.DoneButton
import com.fluxplayer.app.core.ui.components.FluxNotificationBanner
import com.fluxplayer.app.core.ui.components.FluxNotificationState
import com.fluxplayer.app.core.ui.components.MediaDropdownMenu as DropdownMenu
import com.fluxplayer.app.core.ui.components.MediaLoadingIndicator
import com.fluxplayer.app.core.ui.components.MediaPullToRefreshBox as PullToRefreshBox
import com.fluxplayer.app.core.ui.components.NextDialog
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.feature.videopicker.DirectoryState
import kotlinx.coroutines.flow.SharedFlow

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> CloudBrowserPanel(
    items: List<WebDavResource>,
    breadcrumbs: List<T>,
    isLoading: Boolean,
    isConfigured: Boolean,
    error: String?,
    isLoadingMore: Boolean,
    onItemClick: (WebDavResource) -> Unit,
    onBreadcrumbClick: (Int) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    breadcrumbLabel: (T) -> String,
    loginContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onItemMoreClick: ((Int) -> Unit)? = null,
    expandedMenuIndex: Int? = null,
    onMenuDismiss: () -> Unit = {},
    menuContent: @Composable ((index: Int, onDismiss: () -> Unit) -> Unit)? = null,
    reLoginRequired: Boolean = false,
    navigationStack: List<DirectoryState> = emptyList(),
    breadcrumbActions: @Composable (RowScope.() -> Unit)? = null,
    onSortClick: (() -> Unit)? = null,
    onCreateFolder: (() -> Unit)? = null,
    providerName: String = "",
    onSettingsClick: (() -> Unit)? = null,
    onExitClick: (() -> Unit)? = null,
    showSortMenu: Boolean = false,
    onSortMenuDismiss: () -> Unit = {},
    sortMenuContent: @Composable ColumnScope.() -> Unit = {},
    playedUriSet: Set<String> = emptySet(),
    cloudProviderKey: String = "",
    providerMenuItems: @Composable ((onDismiss: () -> Unit) -> Unit) = {},
    notificationEvents: SharedFlow<FluxMessageEvent>? = null,
    providerFeatures: com.fluxplayer.app.core.data.cloud.CloudProviderFeatures? = null,
    currentFolderId: String = "",
    onPlayExtraFile: (android.net.Uri, String?) -> Unit = { _, _ -> },
    isCurrentDirectoryComplete: Boolean = false,
    onAccountChanged: (() -> Unit)? = null,
) {
    // 从 navigationStack 或扁平参数获取当前目录状态
    val topEntry = navigationStack.lastOrNull()
    val curItems = topEntry?.items ?: items
    val curLoading = topEntry?.isLoading ?: isLoading
    val curError = topEntry?.error ?: error
    var showAccounts by remember { mutableStateOf(false) }
    val recoveryRequired by com.fluxplayer.app.core.data.cloud.CloudAccountStore.recoveryRequired.collectAsState()
    val accountRevisions by com.fluxplayer.app.core.data.cloud.CloudAccountStore.revisions.collectAsState()
    val initialRevision = remember(cloudProviderKey) { accountRevisions[cloudProviderKey] ?: 0L }
    val revision = accountRevisions[cloudProviderKey] ?: 0L
    LaunchedEffect(revision, cloudProviderKey) {
        if (revision != initialRevision) onAccountChanged?.invoke()
    }
    if (showAccounts) CloudAccountsDialog(cloudProviderKey) { showAccounts = false }
    var showExitConfirm by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var featureAction by remember(revision) { mutableStateOf<CloudFeatureAction?>(null) }

    val searchSession = providerFeatures?.searchSessionKey().orEmpty()
    var searchVisible by remember(searchSession, currentFolderId, isConfigured) { mutableStateOf(false) }
    var searchQuery by remember(searchSession, isConfigured) { mutableStateOf("") }
    var submittedQuery by remember(searchSession, isConfigured) { mutableStateOf<String?>(null) }
    var entireDrive by remember(searchSession) { mutableStateOf(true) }
    var category by remember(searchSession) { mutableStateOf(CloudSearchCategory.All) }
    var searchFocused by remember { mutableStateOf(false) }
    val history = rememberSearchHistory(searchSession + ":personal")
    var searchRequest by remember(providerFeatures, isConfigured) { mutableIntStateOf(0) }
    val searchCache = remember(searchSession, isConfigured) { CloudDirectorySearchCache() }
    val keyboard = LocalSoftwareKeyboardController.current
    val batchSelection = if (providerFeatures != null && isConfigured) {
        rememberCloudBatchSelection(providerFeatures, currentFolderId, curItems, isCurrentDirectoryComplete && !curLoading && curError == null) {
            searchCache.clear()
            onRefresh()
        }
    } else {
        null
    }
    val batchBusy = batchSelection?.busy == true
    val refreshDirectory = {
        if (!batchBusy) {
            batchSelection?.clear()
            searchCache.clear()
            onRefresh()
        }
    }
    fun submitSearch() {
        if (batchBusy || searchQuery.isBlank() && category == CloudSearchCategory.All) return
        batchSelection?.clear()
        history.remember(searchQuery)
        submittedQuery = searchQuery.trim()
        searchRequest++
        keyboard?.hide()
    }
    LaunchedEffect(curItems, currentFolderId, curLoading, isCurrentDirectoryComplete, searchCache) {
        searchCache.invalidate(currentFolderId)
        if (!curLoading && curError == null && isCurrentDirectoryComplete) {
            searchCache.put(currentFolderId, curItems.map { CloudRemoteFile(it.path, it.name, it.isDirectory, it.size, parentId = currentFolderId) })
        }
    }
    LaunchedEffect(currentFolderId) {
        searchQuery = ""
        submittedQuery = null
    }

    fun closeSearch() {
        searchVisible = false
        searchQuery = ""
        submittedQuery = null
        searchFocused = false
        keyboard?.hide()
    }
    androidx.activity.compose.BackHandler(enabled = searchVisible) { closeSearch() }

    // 缓存每层目录的 LazyListState，使得返回时滚动位置保持不变
    val listStateCache = remember { mutableStateMapOf<String, androidx.compose.foundation.lazy.LazyListState>() }

    // 当栈缩小时，清理已不在栈中的 listState，避免内存泄漏
    LaunchedEffect(navigationStack.map { it.key }) {
        val currentKeys = navigationStack.map { it.key }.toSet()
        listStateCache.keys.filter { it !in currentKeys }.forEach { listStateCache.remove(it) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        var isNearBottom by remember { mutableStateOf(false) }
        Column(modifier = Modifier.fillMaxSize()) {
            if (!isConfigured) {
                if (onAccountChanged != null) TextButton(onClick = { showAccounts = true }) { Text("账号管理与恢复") }
                Box(Modifier.weight(1f)) {
                    if (cloudProviderKey in recoveryRequired) Text("本地账号数据无法读取，请通过账号管理恢复。原数据已保留。", Modifier.padding(24.dp)) else key(revision) { loginContent() }
                }
                if (providerFeatures != null) {
                    TextButton(onClick = { featureAction = CloudFeatureAction.Import }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("浏览分享链接")
                    }
                }
            } else {
                // 顶层：网盘名 + 设置按钮
                ProviderTopBar(
                    providerName = providerName,
                    onSettingsClick = if (batchBusy) null else onSettingsClick,
                )

                if (providerFeatures != null && searchVisible) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = ::closeSearch) { Text("返回目录") }
                        Text("搜索网盘", style = MaterialTheme.typography.titleMedium)
                    }
                    OutlinedTextField(
                        value = searchQuery,
                        enabled = !batchBusy,
                        onValueChange = {
                            searchQuery = it
                            if (it.isBlank()) submittedQuery = null
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).onFocusChanged { searchFocused = it.isFocused },
                        placeholder = { Text("搜索文件及子文件夹") },
                        leadingIcon = { Icon(NextIcons.Search, null) },
                        trailingIcon = {
                            Row {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = {
                                        searchQuery = ""
                                        submittedQuery = null
                                    }) { Icon(NextIcons.Close, "清除搜索") }
                                }
                                TextButton(enabled = searchQuery.isNotBlank() || category != CloudSearchCategory.All, onClick = ::submitSearch) { Text("搜索") }
                            }
                        },
                        shape = MaterialTheme.shapes.large,
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                    )
                    if (searchVisible) {
                        CloudSearchControls(
                            category,
                            onCategory = {
                                category = it
                                if (it == CloudSearchCategory.All && searchQuery.isBlank()) {
                                    submittedQuery = null
                                } else if (submittedQuery != null || it != CloudSearchCategory.All) submitSearch()
                            },
                            history = history,
                            showHistory = submittedQuery == null,
                            onHistory = {
                                searchQuery = it
                                submitSearch()
                            },
                            entireDrive = entireDrive,
                            onScope = {
                                entireDrive = it
                                if (submittedQuery != null) submitSearch()
                            },
                        )
                    }
                }

                // 面包屑行：路径 + 排序 + 驱动切换(阿里云盘) + 菜单
                run {
                    BreadcrumbBar(
                        breadcrumbs = breadcrumbs,
                        breadcrumbLabel = breadcrumbLabel,
                        onNavigateToBreadcrumb = { if (!batchBusy) onBreadcrumbClick(it) },
                        onSortClick = if (batchBusy) null else onSortClick,
                        actions = breadcrumbActions,
                        showMenu = showMenu,
                        onMenuClick = if ((onExitClick != null || providerFeatures != null) && !batchBusy) ({ showMenu = true }) else null,
                        onMenuDismiss = { showMenu = false },
                        menuContent = {
                            if (onAccountChanged != null) DropdownMenuItem(
                                text = { Text("账号管理与恢复") },
                                onClick = { showMenu = false; showAccounts = true },
                                enabled = !batchBusy,
                            )
                            providerMenuItems.invoke({ showMenu = false })
                            if (providerFeatures != null) {
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("搜索") },
                                    onClick = {
                                        showMenu = false
                                        batchSelection?.clear()
                                        searchVisible = true
                                    },
                                )
                                val actions = buildList {
                                    add(CloudFeatureAction.Upload)
                                    add(CloudFeatureAction.Create)
                                    add(CloudFeatureAction.Import)
                                    if (providerFeatures.provider in setOf("alipan", "yun139", "cloud189")) add(CloudFeatureAction.Spaces)
                                    if (providerFeatures.provider in setOf("alipan", "quark", "uc", "yun139")) add(CloudFeatureAction.Account)
                                    if (providerFeatures.provider in setOf("quark", "uc")) add(CloudFeatureAction.Playback)
                                }
                                actions.forEach { action ->
                                    DropdownMenuItem(
                                        text = { Text(if (action == CloudFeatureAction.Spaces && providerFeatures.provider == "alipan") "相册" else action.label) },
                                        onClick = {
                                            showMenu = false
                                            featureAction = action
                                        },
                                    )
                                }
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("退出") },
                                onClick = {
                                    showMenu = false
                                    showExitConfirm = true
                                },
                            )
                        },
                        showSortMenu = showSortMenu,
                        onSortMenuDismiss = onSortMenuDismiss,
                        sortMenuContent = sortMenuContent,
                    )
                }

                if (submittedQuery != null && providerFeatures != null) {
                    Box(Modifier.weight(1f)) {
                        CloudSearchDialog(
                            providerFeatures,
                            onPlayExtraFile,
                            onDismiss = {
                                closeSearch()
                            },
                            embedded = true,
                            queryRequest = submittedQuery,
                            requestId = searchRequest,
                            directoryCache = searchCache,
                            categoryRequest = category,
                            rootId = if (entireDrive) providerFeatures.rootId else currentFolderId.ifBlank { providerFeatures.rootId },
                            rootLabel = if (entireDrive) providerName.ifBlank { "网盘" } else breadcrumbs.lastOrNull()?.let(breadcrumbLabel) ?: "当前文件夹",
                            sessionKeyRequest = searchSession,
                        )
                    }
                } else {
                    if (batchSelection != null) key(searchSession, currentFolderId) { CloudBatchToolbar(batchSelection) }
                    // 统计行
                    val summaryText = remember(curItems) { ItemCounts.from(curItems).toSummaryText() }
                    if (summaryText != null) {
                        Text(
                            text = summaryText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp),
                        )
                    }

                    if (navigationStack.isNotEmpty()) {
                        // 栈式渲染 + 目录导航动画
                        var prevStackSize by remember { mutableIntStateOf(navigationStack.size) }
                        var stackGrowing by remember { mutableStateOf(true) }
                        LaunchedEffect(navigationStack.size) {
                            stackGrowing = navigationStack.size >= prevStackSize
                            prevStackSize = navigationStack.size
                        }

                        val dirTween = tween<IntOffset>(280, easing = EaseOutCubic)
                        val dirAlphaTween = tween<Float>(280, easing = EaseOutCubic)

                        Box(modifier = Modifier.weight(1f)) {
                            AnimatedContent(
                                targetState = navigationStack.lastOrNull()?.key,
                                transitionSpec = {
                                    if (stackGrowing) {
                                        slideInHorizontally(dirTween) { it / 4 } + fadeIn(dirAlphaTween) togetherWith
                                            slideOutHorizontally(dirTween) { -it / 4 } + fadeOut(dirAlphaTween)
                                    } else {
                                        slideInHorizontally(dirTween) { -it / 4 } + fadeIn(dirAlphaTween) togetherWith
                                            slideOutHorizontally(dirTween) { it / 4 } + fadeOut(dirAlphaTween)
                                    }.using(SizeTransform(clip = false))
                                },
                                label = "DirectoryTransition",
                            ) { targetKey ->
                                val entry = if (targetKey != null) {
                                    navigationStack.lastOrNull { it.key == targetKey }
                                } else {
                                    null
                                }
                                if (targetKey != null && entry != null) {
                                    key(entry.key) {
                                        val listState = listStateCache.getOrPut(entry.key) { rememberLazyListState() }
                                        DirectoryStackContent(
                                            entry = entry,
                                            listState = listState,
                                            isLoadingMore = isLoadingMore,
                                            onItemClick = { if (!batchBusy) onItemClick(it) },
                                            onItemMoreClick = if (batchBusy) null else onItemMoreClick,
                                            batchSelection = if (entry === navigationStack.lastOrNull()) batchSelection else null,
                                            expandedMenuIndex = expandedMenuIndex,
                                            onMenuDismiss = onMenuDismiss,
                                            menuContent = menuContent,
                                            onRefresh = refreshDirectory,
                                            onLoadMore = onLoadMore,
                                            onNearBottomChanged = { isNearBottom = it },
                                            playedUriSet = playedUriSet,
                                            cloudProviderKey = cloudProviderKey,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        PullToRefreshBox(
                            isRefreshing = curLoading && curItems.isNotEmpty(),
                            onRefresh = refreshDirectory,
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                when {
                                    curLoading && curItems.isEmpty() -> {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            com.fluxplayer.app.core.ui.components.MediaListSkeleton()
                                        }
                                    }
                                    curItems.isEmpty() && curError == null -> {
                                        EmptyPlaceholder()
                                    }
                                    curError != null && curItems.isEmpty() -> {
                                        ErrorPlaceholder(
                                            message = curError ?: "未知错误",
                                            onRetry = refreshDirectory,
                                        )
                                    }
                                    else -> {
                                        val flatListState = rememberLazyListState()
                                        // 派生为布尔值，仅在翻转时触发，避免滚动每帧回调
                                        val currentOnLoadMore by rememberUpdatedState(onLoadMore)
                                        val nearLoadMore by remember(flatListState, curItems.size) {
                                            derivedStateOf {
                                                val info = flatListState.layoutInfo
                                                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                                                curItems.isNotEmpty() && lastVisible >= curItems.size - 5
                                            }
                                        }
                                        val nearBottom by remember {
                                            derivedStateOf {
                                                val info = flatListState.layoutInfo
                                                if (info.totalItemsCount <= 0) return@derivedStateOf false
                                                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                                                // 只有当可见项数量 < 总数量时，才认为内容溢出（需要滚动），
                                                // 避免文件不足一屏时也触发 FAB 隐藏
                                                lastVisible >= info.totalItemsCount - 3 &&
                                                    info.visibleItemsInfo.size < info.totalItemsCount
                                            }
                                        }
                                        // Only restart for new data or a directory refresh, not loading/error recompositions.
                                        LaunchedEffect(flatListState, curItems.size, curLoading) {
                                            if (!curLoading) {
                                                snapshotFlow { nearLoadMore }.collect { if (it) currentOnLoadMore() }
                                            }
                                        }
                                        LaunchedEffect(flatListState) {
                                            snapshotFlow { nearBottom }.collect { isNearBottom = it }
                                        }
                                        LazyColumn(
                                            state = flatListState,
                                            modifier = Modifier.fillMaxSize(),
                                            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 80.dp),
                                        ) {
                                            itemsIndexed(curItems, key = { _, item -> item.path }) { index, item ->
                                                val isPlayed = !item.isDirectory && (
                                                    item.path in playedUriSet ||
                                                        (cloudProviderKey.isNotEmpty() && "cloud://$cloudProviderKey/${item.path}" in playedUriSet)
                                                    )
                                                ItemCard(
                                                    item = item,
                                                    index = index,
                                                    isPlayed = isPlayed,
                                                    onClick = { if (!batchBusy) onItemClick(item) },
                                                    batchSelection = batchSelection,
                                                    onItemMoreClick = if (batchBusy) null else onItemMoreClick,
                                                    expandedMenuIndex = expandedMenuIndex,
                                                    onMenuDismiss = onMenuDismiss,
                                                    menuContent = menuContent,
                                                )
                                            }

                                            if (isLoadingMore) {
                                                item {
                                                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                                        MediaLoadingIndicator(modifier = Modifier.size(24.dp))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (curError != null && curItems.isNotEmpty()) {
                        ErrorBanner(
                            message = curError ?: "",
                            onRetry = refreshDirectory,
                        )
                    }
                }
            }
        }

        // 新建文件夹 FAB
        if (isConfigured && !batchBusy && submittedQuery == null && onCreateFolder != null && !isNearBottom) {
            FloatingActionButton(
                onClick = onCreateFolder,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 88.dp, end = 16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Default.Add, contentDescription = "新建文件夹")
            }
        }

        // 统一通知浮层
        notificationEvents?.let { flow ->
            val notificationState = remember { FluxNotificationState() }
            LaunchedEffect(flow) {
                flow.collect { notificationState.show(it) }
            }
            FluxNotificationBanner(
                event = notificationState.currentEvent,
                onDismiss = { notificationState.dismiss() },
                onRetry = { refreshDirectory() },
                modifier = Modifier
                    .align(Alignment.BottomCenter),
            )
        }
    }

    if (featureAction != null && providerFeatures != null) {
        key(isConfigured, providerFeatures, featureAction) {
            CloudFeaturesDialog(
                features = providerFeatures,
                parentId = currentFolderId,
                currentItems = curItems,
                onRefresh = refreshDirectory,
                onPlay = onPlayExtraFile,
                onDismiss = { featureAction = null },
                isLoggedIn = isConfigured,
                action = checkNotNull(featureAction),
            )
        }
    }

    // 退出确认对话框
    if (showExitConfirm) {
        NextDialog(
            onDismissRequest = { showExitConfirm = false },
            title = {
                Text(
                    text = stringResource(id = R.string.logout_confirmation_title),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                DoneButton(onClick = {
                    showExitConfirm = false
                    onExitClick?.invoke()
                })
            },
            dismissButton = {
                CancelButton(onClick = { showExitConfirm = false })
            },
            content = {
                Text(text = stringResource(id = R.string.logout_confirmation_message))
            },
        )
    }
}

@Composable
private fun ProviderTopBar(
    providerName: String,
    onSettingsClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = providerName,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (onSettingsClick != null) {
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = NextIcons.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun <T> BreadcrumbBar(
    breadcrumbs: List<T>,
    breadcrumbLabel: (T) -> String,
    onNavigateToBreadcrumb: (Int) -> Unit,
    onSortClick: (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
    showMenu: Boolean = false,
    onMenuClick: (() -> Unit)? = null,
    onMenuDismiss: () -> Unit = {},
    menuContent: @Composable ColumnScope.() -> Unit = {},
    showSortMenu: Boolean = false,
    onSortMenuDismiss: () -> Unit = {},
    sortMenuContent: @Composable ColumnScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(28.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            breadcrumbs.forEachIndexed { index, crumb ->
                TextButton(
                    onClick = { onNavigateToBreadcrumb(index) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = breadcrumbLabel(crumb),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (index == breadcrumbs.lastIndex) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (index == breadcrumbs.lastIndex) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                if (index < breadcrumbs.lastIndex) {
                    Text(
                        text = "›",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        if (onSortClick != null) {
            Box {
                TextButton(
                    onClick = onSortClick,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = "排序",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = onSortMenuDismiss,
                ) {
                    sortMenuContent()
                }
            }
        }
        if (actions != null) {
            actions()
        }
        if (onMenuClick != null) {
            Box {
                TextButton(
                    onClick = onMenuClick,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Menu,
                        contentDescription = "菜单",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = onMenuDismiss,
                ) {
                    menuContent()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DirectoryStackContent(
    entry: DirectoryState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isLoadingMore: Boolean,
    onItemClick: (WebDavResource) -> Unit,
    onItemMoreClick: ((Int) -> Unit)?,
    expandedMenuIndex: Int?,
    onMenuDismiss: () -> Unit,
    menuContent: @Composable ((index: Int, onDismiss: () -> Unit) -> Unit)?,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onNearBottomChanged: (Boolean) -> Unit = {},
    playedUriSet: Set<String> = emptySet(),
    cloudProviderKey: String = "",
    batchSelection: CloudBatchSelection? = null,
) {
    // 派生为布尔值，仅在翻转时触发，避免滚动每帧回调
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    val nearLoadMore by remember(listState, entry.items.size) {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            entry.items.isNotEmpty() && lastVisible >= entry.items.size - 5
        }
    }
    val nearBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            if (info.totalItemsCount <= 0) return@derivedStateOf false
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= info.totalItemsCount - 3 &&
                info.visibleItemsInfo.size < info.totalItemsCount
        }
    }
    // Loading indicators and callback replacements must not retry the same page.
    LaunchedEffect(listState, entry.items.size, entry.isLoading) {
        if (!entry.isLoading) {
            snapshotFlow { nearLoadMore }.collect { if (it) currentOnLoadMore() }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { nearBottom }.collect { onNearBottomChanged(it) }
    }
    PullToRefreshBox(
        isRefreshing = entry.isLoading && entry.items.isNotEmpty(),
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                entry.isLoading && entry.items.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        com.fluxplayer.app.core.ui.components.MediaListSkeleton()
                    }
                }
                entry.items.isEmpty() && entry.error == null -> {
                    EmptyPlaceholder()
                }
                entry.error != null && entry.items.isEmpty() -> {
                    ErrorPlaceholder(
                        message = entry.error!!,
                        onRetry = onRefresh,
                    )
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 80.dp),
                    ) {
                        itemsIndexed(entry.items, key = { _, item -> item.path }) { index, item ->
                            val isPlayed = !item.isDirectory && (
                                item.path in playedUriSet ||
                                    (cloudProviderKey.isNotEmpty() && "cloud://$cloudProviderKey/${item.path}" in playedUriSet)
                                )
                            ItemCard(
                                item = item,
                                index = index,
                                isPlayed = isPlayed,
                                onClick = { onItemClick(item) },
                                batchSelection = batchSelection,
                                onItemMoreClick = onItemMoreClick,
                                expandedMenuIndex = expandedMenuIndex,
                                onMenuDismiss = onMenuDismiss,
                                menuContent = menuContent,
                            )
                        }

                        if (isLoadingMore) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    MediaLoadingIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ItemCard(
    item: WebDavResource,
    index: Int,
    isPlayed: Boolean = false,
    batchSelection: CloudBatchSelection? = null,
    onClick: () -> Unit,
    onItemMoreClick: ((Int) -> Unit)?,
    expandedMenuIndex: Int?,
    onMenuDismiss: () -> Unit,
    menuContent: @Composable ((index: Int, onDismiss: () -> Unit) -> Unit)?,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlayed) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (batchSelection != null) {
                androidx.compose.material3.Checkbox(
                    checked = item.path in batchSelection.selected,
                    enabled = !batchSelection.busy,
                    onCheckedChange = { batchSelection.toggle(item) },
                    modifier = Modifier.semantics { contentDescription = "选择：${item.name}" },
                )
            }
            FileTypeIcon(
                item = item,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = remember(item) { buildItemSubtitle(item) }
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPlayed) {
                    Text(
                        text = "已播放",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                if (item.isDirectory) {
                    Text(
                        text = "›",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                if (onItemMoreClick != null) {
                    Box {
                        IconButton(
                            onClick = { onItemMoreClick(index) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = NextIcons.MoreVert,
                                contentDescription = "更多操作",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        if (menuContent != null && expandedMenuIndex == index) {
                            menuContent(index, onMenuDismiss)
                        }
                    }
                }
            }
        }
    }
}

fun buildItemSubtitle(item: WebDavResource): String {
    if (item.isDirectory) {
        val isEmpty = item.fileCount == 0
        val parts = buildList {
            if (isEmpty) {
                add("空目录")
            } else {
                item.fileCount?.let { if (it > 0) add("${it}个子项") }
                if (item.folderSize > 0) add(formatFileSize(item.folderSize))
            }
            val date = formatRelativeDate(item.createdAt)
            if (date != null) add(date)
        }
        return parts.joinToString(" · ")
    }

    val parts = buildList {
        val typeLabel = item.fileTypeLabel
        if (typeLabel.isNotBlank()) add(typeLabel)
        val date = formatRelativeDate(item.createdAt)
        if (date != null) add(date)
        if (item.size > 0) add(formatFileSize(item.size))
    }
    return parts.joinToString(" · ")
}

fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
        bytes < 1024L * 1024 * 1024 * 1024 -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
        bytes < 1024L * 1024 * 1024 * 1024 * 1024 -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024 * 1024))} TB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024 * 1024 * 1024))} PB"
    }
}

// ====================================================================
// 可复用错误 UI 组件
// ====================================================================

/** 全屏错误占位：图标 + 标题 + 描述 + 重试按钮 */
@Composable
fun ErrorPlaceholder(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    com.fluxplayer.app.core.ui.components.MediaEmptyState(
        icon = NextIcons.Priority,
        title = "加载失败",
        description = message,
        modifier = modifier,
        actionLabel = "重试",
        onAction = onRetry,
    )
}

/** 内联错误横幅：图标 + 消息 + 重试，用于已有部分数据时的错误提示 */
@Composable
fun ErrorBanner(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = NextIcons.Priority,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text("重试", color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

/** 空目录占位：文件夹图标 + 提示文字 */
@Composable
fun EmptyPlaceholder(
    modifier: Modifier = Modifier,
) {
    com.fluxplayer.app.core.ui.components.MediaEmptyState(
        icon = NextIcons.FolderOff,
        title = "此目录为空",
        modifier = modifier,
    )
}
