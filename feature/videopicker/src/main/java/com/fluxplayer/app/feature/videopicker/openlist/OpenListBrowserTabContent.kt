package com.fluxplayer.app.feature.videopicker.openlist

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
import com.fluxplayer.app.core.common.onCloudVideoClick
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.feature.videopicker.composables.CloudBrowserPanel as SharedCloudBrowserPanel
import com.fluxplayer.app.feature.videopicker.composables.ContextActionMenu
import com.fluxplayer.app.feature.videopicker.composables.SortOption
import com.fluxplayer.app.feature.videopicker.composables.SortDropdownMenuContent

/**
 * OpenList 浏览标签页内容。
 * 复用 CloudBrowserPanel 统一 UI，与阿里云盘、夸克网盘等一致。
 */
@Composable
fun OpenListBrowserTabContent(
    onPlayVideo: (Uri, String?) -> Unit,
    onPlayVideos: (List<Uri>, Uri) -> Unit,
    onSettingsClick: () -> Unit,
    navigateToDirParam: Pair<String, String>? = null,
    onNavigateToDirConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: OpenListBrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    val navigationStack by viewModel.navigationStack.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // 从历史页面跳转到 OpenList 指定目录
    LaunchedEffect(state.isLoggedIn, navigateToDirParam) {
        val (fileId, label) = navigateToDirParam ?: return@LaunchedEffect
        if (state.isLoggedIn) {
            viewModel.jumpToFolder(fileId, label)
            onNavigateToDirConsumed()
        }
    }

    BackHandler(enabled = state.isLoggedIn && state.breadcrumbs.size > 1) {
        viewModel.navigateUp()
    }

    val context = LocalContext.current
    var contextMenuIndex by remember { mutableStateOf<Int?>(null) }
    var showSortMenu by remember { mutableStateOf(false) }

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
        onSortClick = { showSortMenu = true },
        showSortMenu = showSortMenu,
        onSortMenuDismiss = { showSortMenu = false },
        sortMenuContent = {
            SortDropdownMenuContent(
                currentKey = currentSortKey,
                onSelect = { option ->
                    showSortMenu = false
                    val field = when (option.key) {
                        "name:asc", "name:desc" -> "name"
                        "time:asc", "time:desc" -> "modified"
                        "size:asc", "size:desc" -> "size"
                        else -> "name"
                    }
                    val dir = if (option.key.endsWith(":desc")) "DESC" else "ASC"
                    viewModel.setSort(field, dir)
                },
                onDismiss = { showSortMenu = false },
            )
        },
        breadcrumbLabel = { it.label },
        providerName = "OpenList",
        onSettingsClick = onSettingsClick,
        loginContent = {
            OpenListNoServerPlaceholder(
                error = state.error,
                onSettingsClick = onSettingsClick,
            )
        },
        playedUriSet = state.playedUriSet,
        cloudProviderKey = "openlist",
    )

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
