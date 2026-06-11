package dev.anilbeesetti.nextplayer.feature.videopicker.openlist

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.model.WebDavResource
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.CloudBrowserPanel as SharedCloudBrowserPanel
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.ContextActionMenu

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

    BackHandler(enabled = state.isLoggedIn && state.breadcrumbs.size > 1) {
        viewModel.navigateUp()
    }

    var contextMenuIndex by remember { mutableStateOf<Int?>(null) }

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
                val allVideos = state.items.filter { it.isVideo }
                val videoUris = allVideos.map { viewModel.getPlayUri(it) }
                if (videoUris.isNotEmpty()) {
                    onPlayVideos(videoUris, viewModel.getPlayUri(item))
                }
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
                    onMove = {},
                    onCopy = {},
                    onDelete = {},
                    onRename = {},
                    onDownload = { onDismiss(); viewModel.downloadFile(index) },
                )
            }
        },
        onBreadcrumbClick = { viewModel.navigateToBreadcrumb(it) },
        onRefresh = { viewModel.refresh() },
        onLoadMore = {},
        breadcrumbLabel = { it.label },
        loginContent = {
            OpenListNoServerPlaceholder(
                error = state.error,
                onSettingsClick = onSettingsClick,
            )
        },
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
