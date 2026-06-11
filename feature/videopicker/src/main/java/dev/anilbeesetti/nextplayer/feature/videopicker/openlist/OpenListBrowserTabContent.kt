package dev.anilbeesetti.nextplayer.feature.videopicker.openlist

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.common.onCloudVideoClick
import dev.anilbeesetti.nextplayer.core.model.WebDavResource
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.CloudBrowserPanel as SharedCloudBrowserPanel
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.ContextActionMenu
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.SortOption
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.SortOptionSheet

/**
 * OpenList 浏览标签页内容。
 * 复用 CloudBrowserPanel 统一 UI，与阿里云盘、夸克网盘等一致。
 */
@Composable
fun OpenListBrowserTabContent(
    onPlayVideo: (Uri, String?) -> Unit,
    onPlayVideos: (List<Uri>, Uri) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OpenListBrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    val navigationStack by viewModel.navigationStack.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    BackHandler(enabled = state.isLoggedIn && state.breadcrumbs.size > 1) {
        viewModel.navigateUp()
    }

    val context = LocalContext.current
    var contextMenuIndex by remember { mutableStateOf<Int?>(null) }
    var showSortSheet by remember { mutableStateOf(false) }

    val currentSortKey = remember(state.orderBy, state.orderDirection) {
        val dir = state.orderDirection.uppercase()
        when (state.orderBy) {
            "name" -> if (dir == "DESC") "name:desc" else "name:asc"
            "modified" -> if (dir == "ASC") "time:asc" else "time:desc"
            "size" -> if (dir == "ASC") "size:asc" else "size:desc"
            else -> "name:asc"
        }
    }

    SharedCloudBrowserPanel(
        modifier = modifier,
        items = state.items,
        breadcrumbs = state.breadcrumbs,
        isLoading = state.isLoading,
        isConfigured = state.isLoggedIn,
        error = state.error,
        isLoadingMore = false,
        navigationStack = navigationStack,
        onItemClick = { item ->
            if (item.isDirectory) {
                viewModel.navigateToDir(state.items.indexOf(item))
            } else if (item.isVideo) {
                onCloudVideoClick(
                    item = item,
                    allItems = state.items,
                    resolveUrl = { viewModel.getPlayUri(item) },
                    buildPlaylistUri = { viewModel.getPlayUri(it) },
                    onPlayVideos = onPlayVideos,
                    scope = scope,
                )
            }
        },
        onItemMoreClick = { index -> contextMenuIndex = index },
        expandedMenuIndex = contextMenuIndex,
        onMenuDismiss = { contextMenuIndex = null },
        menuContent = { index, onDismiss ->
            val item = state.items.getOrNull(index)
            if (item != null) {
                ContextActionMenu(
                    item = item,
                    onDismiss = onDismiss,
                    onMove = { onDismiss(); Toast.makeText(context, "暂不支持移动", Toast.LENGTH_SHORT).show() },
                    onCopy = { onDismiss(); Toast.makeText(context, "暂不支持复制", Toast.LENGTH_SHORT).show() },
                    onDelete = { onDismiss(); Toast.makeText(context, "暂不支持删除", Toast.LENGTH_SHORT).show() },
                    onRename = { onDismiss(); Toast.makeText(context, "暂不支持重命名", Toast.LENGTH_SHORT).show() },
                    onDownload = { onDismiss(); viewModel.downloadFile(index) },
                )
            }
        },
        onBreadcrumbClick = { viewModel.navigateToBreadcrumb(it) },
        onRefresh = { viewModel.refresh() },
        onLoadMore = {},
        onSortClick = { showSortSheet = true },
        breadcrumbLabel = { it.label },
        loginContent = {
            OpenListNoServerPlaceholder(
                error = state.error,
                onSettingsClick = onSettingsClick,
            )
        },
    )

    if (showSortSheet) {
        SortOptionSheet(
            currentKey = currentSortKey,
            onSelect = { option ->
                showSortSheet = false
                val field = when (option.key) {
                    "name:asc", "name:desc" -> "name"
                    "time:asc", "time:desc" -> "modified"
                    "size:asc", "size:desc" -> "size"
                    else -> "name"
                }
                val dir = if (option.key.endsWith(":desc")) "DESC" else "ASC"
                viewModel.setSort(field, dir)
            },
            onDismiss = { showSortSheet = false },
        )
    }
}

@Composable
private fun OpenListNoServerPlaceholder(
    error: String?,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = NextIcons.Settings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "OpenList 服务未运行",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = onSettingsClick) {
                Text("前往设置")
            }
        }
    }
}
