package dev.anilbeesetti.nextplayer.feature.videopicker.screens.webdav

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.model.WebDavResource
import dev.anilbeesetti.nextplayer.core.model.WebDavServer
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.CenterCircularProgressBar
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.FileTypeIcon
import dev.anilbeesetti.nextplayer.feature.videopicker.CommonStateSnapshot

/**
 * WebDAV 浏览内容面板（不带 Scaffold / TopAppBar），供标签页直接内嵌。
 */
@Composable
fun WebDavBrowserTabContent(
    onPlayVideo: (Uri, String?) -> Unit,
    onPlayVideos: (List<Uri>, Uri) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WebDavBrowserViewModel = hiltViewModel(),
) {
    val state by viewModel.stateFlow.collectAsStateWithLifecycle()
    val extraState by viewModel.extraState.collectAsStateWithLifecycle()

    BackHandler(enabled = state.breadcrumbs.size > 1) {
        viewModel.navigateUp()
    }

    WebDavBrowserPanel(
        state = state,
        extraState = extraState,
        onPlayVideo = onPlayVideo,
        onPlayVideos = onPlayVideos,
        onRecordFootprint = viewModel::recordFootprint,
        onDownloadFile = viewModel::downloadFile,
        onNavigateToDir = viewModel::navigateToDir,
        onNavigateUpDir = viewModel::navigateUp,
        onNavigateToBreadcrumb = viewModel::navigateToBreadcrumb,
        onRefresh = viewModel::refresh,
        onSettingsClick = onSettingsClick,
        onSetActive = viewModel::selectServer,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WebDavBrowserPanel(
    state: CommonStateSnapshot<WebDavBreadcrumb>,
    extraState: WebDavBrowserViewModel.WebDavExtraState,
    onPlayVideo: (Uri, String?) -> Unit,
    onPlayVideos: (List<Uri>, Uri) -> Unit,
    onRecordFootprint: (String) -> Unit,
    onDownloadFile: (Int) -> Unit,
    onNavigateToDir: (Int) -> Unit,
    onNavigateUpDir: () -> Unit,
    onNavigateToBreadcrumb: (Int) -> Unit,
    onRefresh: () -> Unit,
    onSettingsClick: () -> Unit,
    onSetActive: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // 服务器标签栏
        if (extraState.activeServers.size > 1) {
            val selectedId = extraState.selectedServer?.id
            val selectedIndex = extraState.activeServers.indexOfFirst { it.id == selectedId }
                .coerceAtLeast(0)

            SecondaryTabRow(selectedTabIndex = selectedIndex) {
                extraState.activeServers.forEachIndexed { idx, server ->
                    Tab(
                        selected = idx == selectedIndex,
                        onClick = { onSetActive(server.id) },
                        text = { Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }

        if (!extraState.isConfigured) {
            NoServerPlaceholder(
                onSettingsClick = onSettingsClick,
                modifier = Modifier.weight(1f),
            )
        } else {
            BreadcrumbBar(
                breadcrumbs = state.breadcrumbs,
                onNavigateToBreadcrumb = onNavigateToBreadcrumb,
                onNavigateUpDir = onNavigateUpDir,
            )

            if (state.isLoading && state.items.isEmpty()) {
                CenterCircularProgressBar(modifier = Modifier.weight(1f))
            } else if (state.items.isEmpty()) {
                EmptyFolder(modifier = Modifier.weight(1f))
            } else {
                PullToRefreshBox(
                    isRefreshing = state.isLoading && state.items.isNotEmpty(),
                    onRefresh = onRefresh,
                    modifier = Modifier.weight(1f),
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        itemsIndexed(state.items) { index, resource ->
                            val currentItemUri = if (resource.isVideo)
                                buildSingleWebDavAuthUri(resource, extraState.selectedServer)
                            else null
                            val hasPlayFootprint = currentItemUri != null &&
                                state.currentFootprint == currentItemUri
                            val isDirFootprint = resource.isDirectory &&
                                state.currentFootprint == resource.path
                            val hasFootprint = hasPlayFootprint || isDirFootprint

                            Card(
                                onClick = {
                                    if (resource.isDirectory) {
                                        onNavigateToDir(index)
                                    } else if (resource.isVideo) {
                                        val clickedVideoUri = buildSingleWebDavAuthUri(resource, extraState.selectedServer)
                                        val allVideos = state.items.filter { it.isVideo }
                                        val videoUris = buildWebDavPlaylist(
                                            items = allVideos,
                                            server = extraState.selectedServer,
                                        )
                                        if (videoUris.isNotEmpty()) {
                                            onRecordFootprint(clickedVideoUri)
                                            onPlayVideos(videoUris, Uri.parse(clickedVideoUri))
                                        }
                                    } else {
                                        onDownloadFile(index)
                                    }
                                },
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
                                    Box {
                                        FileTypeIcon(
                                            item = resource,
                                            modifier = Modifier
                                                .size(40.dp)
                                                .clip(RoundedCornerShape(8.dp)),
                                        )
                                        if (hasFootprint) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .size(16.dp)
                                                    .background(
                                                        color = MaterialTheme.colorScheme.primary,
                                                        shape = CircleShape,
                                                    ),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Icon(
                                                    imageVector = NextIcons.Check,
                                                    contentDescription = "足迹",
                                                    tint = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.size(10.dp),
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = resource.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = if (hasPlayFootprint) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                        if (!resource.isDirectory && resource.size > 0) {
                                            Text(
                                                text = formatFileSize(resource.size),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (resource.isDirectory) {
                                        Text(
                                            text = "›",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.titleLarge,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            state.error?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
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
internal fun BreadcrumbBar(
    breadcrumbs: List<WebDavBreadcrumb>,
    onNavigateToBreadcrumb: (Int) -> Unit,
    onNavigateUpDir: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (breadcrumbs.size > 1) {
            FilledTonalIconButton(
                onClick = onNavigateUpDir,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = NextIcons.ArrowBack,
                    contentDescription = "上级目录",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        breadcrumbs.forEachIndexed { index, crumb ->
            TextButton(
                onClick = { onNavigateToBreadcrumb(index) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = crumb.label,
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

@Composable
internal fun NoServerPlaceholder(
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

@Composable
internal fun EmptyFolder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "此目录为空",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun buildSingleWebDavAuthUri(
    resource: WebDavResource,
    server: WebDavServer?,
): String {
    if (server == null) return resource.path
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
    server: WebDavServer?,
): List<Uri> {
    return items.filter { it.isVideo }.map { resource ->
        Uri.parse(buildSingleWebDavAuthUri(resource, server))
    }
}

internal fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
    }
}
