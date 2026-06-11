package dev.anilbeesetti.nextplayer.feature.videopicker.composables

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import dev.anilbeesetti.nextplayer.feature.videopicker.DirectoryStackEntry

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
    navigationStack: List<DirectoryStackEntry> = emptyList(),
) {
    // 从 navigationStack 或扁平参数获取当前目录状态
    val topEntry = navigationStack.lastOrNull()
    val curItems = topEntry?.items ?: items
    val curLoading = topEntry?.isLoading ?: isLoading
    val curError = topEntry?.error ?: error

    Column(modifier = modifier.fillMaxSize()) {
        if (!isConfigured) {
            loginContent()
        } else {
            // 面包屑
            BreadcrumbBar(
                breadcrumbs = breadcrumbs,
                breadcrumbLabel = breadcrumbLabel,
                onNavigateToBreadcrumb = onBreadcrumbClick,
            )

            if (navigationStack.isNotEmpty()) {
                // 栈式渲染：每个目录层级有自己的 key + rememberLazyListState
                Box(modifier = Modifier.weight(1f)) {
                    navigationStack.forEachIndexed { index, entry ->
                        val isTop = index == navigationStack.lastIndex
                        key(entry.fileId) {
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
                                )
                            }
                        }
                    }
                }
            } else if (curLoading && curItems.isEmpty()) {
                CenterCircularProgressBar(modifier = Modifier.weight(1f))
            } else if (curItems.isEmpty() && curError == null) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("此目录为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (curError != null && curItems.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("加载失败", color = MaterialTheme.colorScheme.error)
                        Text(curError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onRefresh) { Text("重试") }
                    }
                }
            } else {
                PullToRefreshBox(
                    isRefreshing = curLoading && curItems.isNotEmpty(),
                    onRefresh = onRefresh,
                    modifier = Modifier.weight(1f)
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        itemsIndexed(curItems) { index, item ->
                            Card(
                                onClick = { onItemClick(item) },
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
                                        if (!item.isDirectory && item.size > 0) {
                                            Text(
                                                text = formatFileSize(item.size),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

}

@Composable
private fun <T> BreadcrumbBar(
    breadcrumbs: List<T>,
    breadcrumbLabel: (T) -> String,
    onNavigateToBreadcrumb: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 4.dp),
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
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DirectoryStackContent(
    entry: DirectoryStackEntry,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isLoadingMore: Boolean,
    onItemClick: (WebDavResource) -> Unit,
    onItemMoreClick: ((Int) -> Unit)?,
    expandedMenuIndex: Int?,
    onMenuDismiss: () -> Unit,
    menuContent: @Composable ((index: Int, onDismiss: () -> Unit) -> Unit)?,
    onRefresh: () -> Unit,
) {
    if (entry.isLoading && entry.items.isEmpty()) {
        CenterCircularProgressBar(modifier = Modifier.fillMaxSize())
    } else if (entry.items.isEmpty() && entry.error == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("此目录为空", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else if (entry.error != null && entry.items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("加载失败", color = MaterialTheme.colorScheme.error)
                Text(entry.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onRefresh) { Text("重试") }
            }
        }
    } else {
        PullToRefreshBox(
            isRefreshing = entry.isLoading && entry.items.isNotEmpty(),
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                itemsIndexed(entry.items) { index, item ->
                    Card(
                        onClick = { onItemClick(item) },
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
                                if (!item.isDirectory && item.size > 0) {
                                    Text(
                                        text = formatFileSize(item.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
}

fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
    }
}
