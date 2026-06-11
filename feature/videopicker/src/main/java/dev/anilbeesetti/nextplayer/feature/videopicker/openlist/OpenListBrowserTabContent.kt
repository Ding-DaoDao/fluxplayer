package dev.anilbeesetti.nextplayer.feature.videopicker.openlist

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.model.WebDavResource
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.feature.videopicker.DirectoryStackEntry
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.CenterCircularProgressBar
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.FileTypeIcon

/**
 * OpenList 浏览标签页内容。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
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

    BackHandler(enabled = state.breadcrumbs.size > 1) {
        viewModel.navigateUp()
    }

    OpenListBrowserPanel(
        state = state,
        navigationStack = navigationStack,
        onPlayVideo = onPlayVideo,
        onPlayVideos = onPlayVideos,
        onDownloadFile = { /* OpenList 下载使用基类的 downloadFile */ },
        onNavigateToDir = viewModel::navigateToDir,
        onNavigateUpDir = viewModel::navigateUp,
        onNavigateToBreadcrumb = viewModel::navigateToBreadcrumb,
        onRefresh = viewModel::refresh,
        onSettingsClick = onSettingsClick,
        getPlayUri = viewModel::getPlayUri,
        modifier = modifier,
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OpenListBrowserPanel(
    state: dev.anilbeesetti.nextplayer.feature.videopicker.CommonStateSnapshot<OpenListBreadcrumb>,
    onPlayVideo: (Uri, String?) -> Unit,
    onPlayVideos: (List<Uri>, Uri) -> Unit,
    onDownloadFile: (Int) -> Unit,
    onNavigateToDir: (Int) -> Unit,
    onNavigateUpDir: () -> Unit,
    onNavigateToBreadcrumb: (Int) -> Unit,
    onRefresh: () -> Unit,
    onSettingsClick: () -> Unit,
    getPlayUri: (WebDavResource) -> Uri,
    modifier: Modifier = Modifier,
    navigationStack: List<DirectoryStackEntry> = emptyList(),
) {
    val serverUrl = "http://127.0.0.1:5244"

    Column(modifier = modifier.fillMaxSize()) {
        if (!state.isLoggedIn) {
            NoServerPlaceholder(
                error = state.error,
                onSettingsClick = onSettingsClick,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            OpenListBreadcrumbBar(
                breadcrumbs = state.breadcrumbs,
                onNavigateToBreadcrumb = onNavigateToBreadcrumb,
                onNavigateUpDir = onNavigateUpDir,
            )

            if (navigationStack.isNotEmpty()) {
                // 栈式渲染
                Box(modifier = Modifier.weight(1f)) {
                    navigationStack.forEachIndexed { index, entry ->
                        val isTop = index == navigationStack.lastIndex
                        key(entry.fileId) {
                            val listState = rememberLazyListState()
                            if (isTop) {
                                OpenListDirectoryContent(
                                    entry = entry,
                                    listState = listState,
                                    serverUrl = serverUrl,
                                    onNavigateToDir = onNavigateToDir,
                                    onPlayVideos = onPlayVideos,
                                    onDownloadFile = onDownloadFile,
                                    onRefresh = onRefresh,
                                )
                            }
                        }
                    }
                }
            } else {
            PullToRefreshBox(
                isRefreshing = state.isLoading && state.items.isNotEmpty(),
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f),
            ) {
                if (state.isLoading && state.items.isEmpty()) {
                    CenterCircularProgressBar(modifier = Modifier.fillMaxSize())
                } else if (state.items.isEmpty()) {
                    EmptyFolderHint()
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        itemsIndexed(state.items) { index, item ->
                            Card(
                                onClick = {
                                    if (item.isDirectory) {
                                        onNavigateToDir(index)
                                    } else if (item.isVideo) {
                                        val clickedVideoUri = "$serverUrl/d${item.path}"
                                        val allVideos = state.items.filter { it.isVideo }
                                        val videoUris = allVideos.map { Uri.parse("$serverUrl/d${it.path}") }
                                        val clickedUri = Uri.parse(clickedVideoUri)
                                        if (videoUris.isNotEmpty()) {
                                            onPlayVideos(videoUris, clickedUri)
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
                                    if (item.isDirectory) {
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
internal fun NoServerPlaceholder(
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

@Composable
internal fun OpenListBreadcrumbBar(
    breadcrumbs: List<OpenListBreadcrumb>,
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
internal fun EmptyFolderHint() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OpenListDirectoryContent(
    entry: DirectoryStackEntry,
    listState: androidx.compose.foundation.lazy.LazyListState,
    serverUrl: String,
    onNavigateToDir: (Int) -> Unit,
    onPlayVideos: (List<Uri>, Uri) -> Unit,
    onDownloadFile: (Int) -> Unit,
    onRefresh: () -> Unit,
) {
    if (entry.isLoading && entry.items.isEmpty()) {
        CenterCircularProgressBar(modifier = Modifier.fillMaxSize())
    } else if (entry.items.isEmpty()) {
        EmptyFolderHint()
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
                        onClick = {
                            if (item.isDirectory) {
                                onNavigateToDir(index)
                            } else if (item.isVideo) {
                                val clickedVideoUri = "$serverUrl/d${item.path}"
                                val allVideos = entry.items.filter { it.isVideo }
                                val videoUris = allVideos.map { Uri.parse("$serverUrl/d${it.path}") }
                                val clickedUri = Uri.parse(clickedVideoUri)
                                if (videoUris.isNotEmpty()) {
                                    onPlayVideos(videoUris, clickedUri)
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
                            if (item.isDirectory) {
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
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
        else -> "%.2f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
    }
}
