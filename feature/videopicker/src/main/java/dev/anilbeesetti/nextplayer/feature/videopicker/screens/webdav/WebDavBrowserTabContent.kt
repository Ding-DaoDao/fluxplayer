package dev.anilbeesetti.nextplayer.feature.videopicker.screens.webdav

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.common.onCloudVideoClick
import dev.anilbeesetti.nextplayer.core.model.WebDavResource
import dev.anilbeesetti.nextplayer.core.model.WebDavServer
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.CloudBrowserPanel as SharedCloudBrowserPanel
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.ContextActionMenu
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.CreateFolderDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.RenameDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.SortDropdownMenuContent

/**
 * WebDAV 浏览标签页内容。
 * 复用 CloudBrowserPanel 统一 UI，与阿里云盘、夸克网盘等一致。
 * 保留 WebDAV 特有的 URI 构造逻辑（Basic Auth 内嵌 URI）。
 */
@Composable
fun WebDavBrowserTabContent(
    onPlayVideo: (Uri, String?) -> Unit,
    onPlayVideos: (List<Uri>, Uri) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WebDavBrowserViewModel = hiltViewModel(),
) {
    val navigationStack by viewModel.navigationStack.collectAsStateWithLifecycle()
    val extraState by viewModel.extraState.collectAsStateWithLifecycle()
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()

    val currentDir = navigationStack.lastOrNull()

    BackHandler(enabled = navigationStack.size > 1) {
        viewModel.navigateUp()
    }

    val server = extraState.selectedServer
    val scope = rememberCoroutineScope()
    var contextMenuIndex by remember { mutableStateOf<Int?>(null) }
    var renameIndex by remember { mutableStateOf(-1) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
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
        items = currentDir?.items ?: emptyList(),
        breadcrumbs = state.breadcrumbs,
        isLoading = currentDir?.isLoading ?: false,
        isConfigured = extraState.isConfigured,
        error = currentDir?.error,
        isLoadingMore = false,
        navigationStack = navigationStack,
        onItemClick = { item ->
            if (item.isDirectory) {
                val idx = currentDir?.items?.indexOf(item)
                if (idx != null) viewModel.navigateToDir(idx)
            } else if (item.isVideo && server != null) {
                onCloudVideoClick(
                    item = item,
                    allItems = currentDir?.items ?: emptyList(),
                    resolveUrl = { Uri.parse(buildSingleWebDavAuthUri(item, server)) },
                    buildPlaylistUri = { Uri.parse(buildSingleWebDavAuthUri(it, server)) },
                    onPlayVideos = onPlayVideos,
                    scope = scope,
                )
            }
        },
        onItemMoreClick = { index -> contextMenuIndex = index },
        expandedMenuIndex = contextMenuIndex,
        onMenuDismiss = { contextMenuIndex = null },
        menuContent = { index, onDismiss ->
            val item = currentDir?.items?.getOrNull(index)
            if (item != null) {
                ContextActionMenu(
                    item = item,
                    onDismiss = onDismiss,
                    onMove = { onDismiss() },
                    onCopy = { onDismiss() },
                    onDelete = { onDismiss(); viewModel.deleteItem(index) },
                    onRename = { renameIndex = index; onDismiss() },
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
        providerName = "WebDAV",
        onSettingsClick = onSettingsClick,
        loginContent = {
            WebDavNoServerPlaceholder(onSettingsClick = onSettingsClick)
        },
        onCreateFolder = { showCreateFolderDialog = true }
    )

    val renameItem = currentDir?.items?.getOrNull(renameIndex)
    if (renameItem != null) {
        RenameDialog(
            name = renameItem.name,
            onDismiss = { renameIndex = -1 },
            onDone = { newName -> viewModel.renameItem(renameIndex, newName); renameIndex = -1 },
        )
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onDismiss = { showCreateFolderDialog = false },
            onCreate = { name -> viewModel.createDirectory(name); showCreateFolderDialog = false },
        )
    }

}

@Composable
private fun WebDavNoServerPlaceholder(
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = NextIcons.Link,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "未配置 WebDAV 服务器",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "请在设置中添加一个 WebDAV 服务器",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        TextButton(onClick = onSettingsClick) {
            Icon(
                imageVector = NextIcons.Settings,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("打开设置")
        }
    }
}

// region ==================== WebDAV URI 构建 ====================

private fun buildSingleWebDavAuthUri(
    resource: WebDavResource,
    server: WebDavServer,
): String {
    val baseUrl = server.normalizedUrl.trimEnd('/')
    val fullUrl = if (resource.path.startsWith("/"))
        "$baseUrl${resource.path}"
    else
        "$baseUrl/${resource.path}"
    val originalUri = Uri.parse(fullUrl)
    val hostPort = originalUri.host +
        if (originalUri.port != -1) ":${originalUri.port}" else ""
    return originalUri.buildUpon()
        .encodedAuthority(
            Uri.encode(server.username) + ":" +
                Uri.encode(server.password) + "@" + hostPort
        )
        .build()
        .toString()
}

private fun buildWebDavPlaylist(
    items: List<WebDavResource>,
    server: WebDavServer,
): List<Uri> {
    return items.filter { it.isVideo }.map { resource ->
        Uri.parse(buildSingleWebDavAuthUri(resource, server))
    }
}

// endregion
