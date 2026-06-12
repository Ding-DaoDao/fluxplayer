package dev.anilbeesetti.nextplayer.feature.videopicker.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.model.WebDavResource
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.videopicker.DirectoryState

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
) {
    // 从 navigationStack 或扁平参数获取当前目录状态
    val topEntry = navigationStack.lastOrNull()
    val curItems = topEntry?.items ?: items
    val curLoading = topEntry?.isLoading ?: isLoading
    val curError = topEntry?.error ?: error

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (!isConfigured) {
                loginContent()
            } else {
            // 顶层：网盘名 + 设置按钮
            ProviderTopBar(
                providerName = providerName,
                onSettingsClick = onSettingsClick,
            )

            // 面包屑行：路径 + 排序 + 驱动切换(阿里云盘) + 退出
            BreadcrumbBar(
                breadcrumbs = breadcrumbs,
                breadcrumbLabel = breadcrumbLabel,
                onNavigateToBreadcrumb = onBreadcrumbClick,
                onSortClick = onSortClick,
                actions = breadcrumbActions,
                onExitClick = onExitClick,
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
                // 栈式渲染：每个目录层级有自己的 key + rememberLazyListState
                Box(modifier = Modifier.weight(1f)) {
                    navigationStack.forEachIndexed { index, entry ->
                        val isTop = index == navigationStack.lastIndex
                        key(entry.key) {
                            val listState = rememberLazyListState()
                            if (isTop) {
                                DirectoryStackContent(
                                    entry = entry,
                                    listState = listState,
                                    isLoadingMore = isLoadingMore,
                                    onItemClick = onItemClick,
                                    onItemMoreClick = onItemMoreClick,
                                    expandedMenuIndex = expandedMenuIndex,
                                    onMenuDismiss = onMenuDismiss,
                                    menuContent = menuContent,
                                    onRefresh = onRefresh,
                                    onLoadMore = onLoadMore,
                                )
                            }
                        }
                    }
                }
            } else {
                PullToRefreshBox(
                    isRefreshing = false,
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
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("此目录为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            curError != null && curItems.isEmpty() -> {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("加载失败", color = MaterialTheme.colorScheme.error)
                                        Text(curError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedButton(onClick = onRefresh) { Text("重试") }
                                    }
                                }
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
                                        }
                                }
                                LazyColumn(
                                    state = flatListState,
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    itemsIndexed(curItems) { index, item ->
                                        ItemCard(
                                            item = item,
                                            index = index,
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
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(curError, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onRefresh) {
                            Text("重试", color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }
        }
        }

        // 新建文件夹 FAB
        if (isConfigured && onCreateFolder != null) {
            FloatingActionButton(
                onClick = onCreateFolder,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(Icons.Default.Add, contentDescription = "新建文件夹")
            }
        }
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
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
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
    onExitClick: (() -> Unit)? = null,
    showSortMenu: Boolean = false,
    onSortMenuDismiss: () -> Unit = {},
    sortMenuContent: @Composable ColumnScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
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
        if (onExitClick != null) {
            TextButton(
                onClick = onExitClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "退出",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
) {
    LaunchedEffect(listState, onLoadMore) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
            .collect { visibleItems ->
                val lastVisible = visibleItems.lastOrNull()?.index ?: 0
                val total = listState.layoutInfo.totalItemsCount
                if (lastVisible >= total - 5 && total > 0) {
                    onLoadMore()
                }
            }
    }
    PullToRefreshBox(
        isRefreshing = false,
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
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("此目录为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                entry.error != null && entry.items.isEmpty() -> {
                    val errorMsg = entry.error!!
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("加载失败", color = MaterialTheme.colorScheme.error)
                            Text(errorMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onRefresh) { Text("重试") }
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        itemsIndexed(entry.items) { index, item ->
                            ItemCard(
                                item = item,
                                index = index,
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
    onClick: () -> Unit,
    onItemMoreClick: ((Int) -> Unit)?,
    expandedMenuIndex: Int?,
    onMenuDismiss: () -> Unit,
    menuContent: @Composable ((index: Int, onDismiss: () -> Unit) -> Unit)?,
) {
    val subtitle = remember(item) { buildItemSubtitle(item) }

    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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

private fun buildItemSubtitle(item: WebDavResource): String {
    if (item.isDirectory) {
        val parts = buildList {
            item.fileCount?.let { if (it > 0) add("${it}个子项") }
            if (item.folderSize > 0) add(formatFileSize(item.folderSize))
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
