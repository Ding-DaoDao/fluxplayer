package com.fluxplayer.app.feature.videopicker.composables

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.components.CancelButton
import com.fluxplayer.app.core.ui.components.DoneButton
import com.fluxplayer.app.core.ui.components.NextDialog
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.feature.videopicker.DirectoryState

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
) {
    // 从 navigationStack 或扁平参数获取当前目录状态
    val topEntry = navigationStack.lastOrNull()
    val curItems = topEntry?.items ?: items
    val curLoading = topEntry?.isLoading ?: isLoading
    val curError = topEntry?.error ?: error
    var showExitConfirm by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    SideEffect {
        if (cloudProviderKey == "webdav" || cloudProviderKey == "openlist") {
            val videoItems = curItems.filter { it.isVideo }
            val matchedCount = videoItems.count { item ->
                item.path in playedUriSet ||
                (cloudProviderKey.isNotEmpty() && "cloud://$cloudProviderKey/${item.path}" in playedUriSet)
            }
            Log.d(
                "CloudBrowserPanel",
                "[Match] provider=$cloudProviderKey videoItems=${videoItems.size} matchedItems=$matchedCount " +
                "playedUriSetSize=${playedUriSet.size}"
            )
            val unmatched = videoItems.filter { item ->
                item.path !in playedUriSet &&
                !(cloudProviderKey.isNotEmpty() && "cloud://$cloudProviderKey/${item.path}" in playedUriSet)
            }.take(3)
            if (unmatched.isNotEmpty()) {
                Log.d(
                    "CloudBrowserPanel",
                    "[Match] unmatched paths: ${unmatched.joinToString("|") { it.path }}"
                )
            }
            if (playedUriSet.isNotEmpty()) {
                val sampleUris = playedUriSet.take(5).joinToString("|")
                Log.d("CloudBrowserPanel", "[Match] sample played uris: $sampleUris")
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        var isNearBottom by remember { mutableStateOf(false) }
        Column(modifier = Modifier.fillMaxSize()) {
            if (!isConfigured) {
                loginContent()
            } else {
            // 顶层：网盘名 + 设置按钮
            ProviderTopBar(
                providerName = providerName,
                onSettingsClick = onSettingsClick,
            )

            // 面包屑行：路径 + 排序 + 驱动切换(阿里云盘) + 菜单
            BreadcrumbBar(
                breadcrumbs = breadcrumbs,
                breadcrumbLabel = breadcrumbLabel,
                onNavigateToBreadcrumb = onBreadcrumbClick,
                onSortClick = onSortClick,
                actions = breadcrumbActions,
                showMenu = showMenu,
                onMenuClick = if (onExitClick != null) ({ showMenu = true }) else null,
                onMenuDismiss = { showMenu = false },
                menuContent = {
                    providerMenuItems.invoke({ showMenu = false })
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
                        targetState = navigationStack.lastOrNull(),
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
                    ) { topEntry ->
                        if (topEntry != null) {
                            key(topEntry.key) {
                                val listState = rememberLazyListState()
                                DirectoryStackContent(
                                    entry = topEntry,
                                    listState = listState,
                                    isLoadingMore = isLoadingMore,
                                    onItemClick = onItemClick,
                                    onItemMoreClick = onItemMoreClick,
                                    expandedMenuIndex = expandedMenuIndex,
                                    onMenuDismiss = onMenuDismiss,
                                    menuContent = menuContent,
                                    onRefresh = onRefresh,
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
                    onRefresh = onRefresh,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        when {
                            curLoading && curItems.isEmpty() -> {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                            curItems.isEmpty() && curError == null -> {
                                EmptyPlaceholder()
                            }
                            curError != null && curItems.isEmpty() -> {
                                ErrorPlaceholder(
                                    message = curError ?: "未知错误",
                                    onRetry = onRefresh,
                                )
                            }
                            else -> {
                                val flatListState = rememberLazyListState()
                                LaunchedEffect(flatListState, onLoadMore) {
                                    snapshotFlow { flatListState.layoutInfo.visibleItemsInfo }
                                        .collect { visibleItems ->
                                            val lastVisible = visibleItems.lastOrNull()?.index ?: 0
                                            val total = flatListState.layoutInfo.totalItemsCount
                                            if (lastVisible >= total - 5 && total > 0) {
                                                onLoadMore()
                                            }
                                            // 只有当可见项数量 < 总数量时，才认为内容溢出（需要滚动），
                                            // 避免文件不足一屏时也触发 FAB 隐藏
                                            val contentOverflows = visibleItems.size < total
                                            isNearBottom = total > 0 && lastVisible >= total - 3 && contentOverflows
                                        }
                                }
                                LazyColumn(
                                    state = flatListState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 80.dp)
                                ) {
                                    itemsIndexed(curItems) { index, item ->
                                        val isPlayed = !item.isDirectory && (
                                            item.path in playedUriSet ||
                                            (cloudProviderKey.isNotEmpty() && "cloud://$cloudProviderKey/${item.path}" in playedUriSet)
                                        )
                                        ItemCard(
                                            item = item,
                                            index = index,
                                            isPlayed = isPlayed,
                                            onClick = { onItemClick(item) },
                                            onItemMoreClick = onItemMoreClick,
                                            expandedMenuIndex = expandedMenuIndex,
                                            onMenuDismiss = onMenuDismiss,
                                            menuContent = menuContent,
                                        )
                                    }

                                    if (isLoadingMore) {
                                        item {
                                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (curLoading && curItems.isNotEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }

            if (curError != null && curItems.isNotEmpty()) {
                ErrorBanner(
                    message = curError ?: "",
                    onRetry = onRefresh,
                )
            }
        }
        }

        // 新建文件夹 FAB
        if (isConfigured && onCreateFolder != null && !isNearBottom) {
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
            style = MaterialTheme.typography.titleMedium,
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
            .padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
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
                        color = if (index == breadcrumbs.lastIndex)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
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
) {
    LaunchedEffect(listState, onLoadMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
            .collect { visibleItems ->
                val lastVisible = visibleItems.lastOrNull()?.index ?: 0
                val total = listState.layoutInfo.totalItemsCount
                if (lastVisible >= total - 5 && total > 0) {
                    onLoadMore()
                }
                val contentOverflows = visibleItems.size < total
                onNearBottomChanged(total > 0 && lastVisible >= total - 3 && contentOverflows)
            }
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
                        CircularProgressIndicator()
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
                        itemsIndexed(entry.items) { index, item ->
                            val isPlayed = !item.isDirectory && (
                                item.path in playedUriSet ||
                                (cloudProviderKey.isNotEmpty() && "cloud://$cloudProviderKey/${item.path}" in playedUriSet)
                            )
                            ItemCard(
                                item = item,
                                index = index,
                                isPlayed = isPlayed,
                                onClick = { onItemClick(item) },
                                onItemMoreClick = onItemMoreClick,
                                expandedMenuIndex = expandedMenuIndex,
                                onMenuDismiss = onMenuDismiss,
                                menuContent = menuContent,
                            )
                        }

                        if (isLoadingMore) {
                            item {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                }
                            }
                        }
                    }
                }
            }

            if (entry.isLoading && entry.items.isNotEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
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
    onClick: () -> Unit,
    onItemMoreClick: ((Int) -> Unit)?,
    expandedMenuIndex: Int?,
    onMenuDismiss: () -> Unit,
    menuContent: @Composable ((index: Int, onDismiss: () -> Unit) -> Unit)?,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPlayed) {
                Color(0xFFE3F2FD)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileTypeIcon(
                item = item,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
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
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
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
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            Icon(
                imageVector = NextIcons.Priority,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "加载失败",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = onRetry) {
                Text("重试")
            }
        }
    }
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
        shape = RoundedCornerShape(12.dp),
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
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = NextIcons.FolderOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "此目录为空",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
