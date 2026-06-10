package dev.anilbeesetti.nextplayer.feature.videopicker.openlist

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

    BackHandler(enabled = state.breadcrumbs.size > 1) {
        viewModel.navigateUp()
    }

    OpenListBrowserPanel(
        state = state,
        onPlayVideo = onPlayVideo,
        onPlayVideos = onPlayVideos,
        onRecordFootprint = viewModel::recordFootprint,
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
    onRecordFootprint: (String) -> Unit,
    onDownloadFile: (Int) -> Unit,
    onNavigateToDir: (Int) -> Unit,
    onNavigateUpDir: () -> Unit,
    onNavigateToBreadcrumb: (Int) -> Unit,
    onRefresh: () -> Unit,
    onSettingsClick: () -> Unit,
    getPlayUri: (WebDavResource) -> Uri,
    modifier: Modifier = Modifier,
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
                            val currentItemUri = if (item.isVideo)
                                "$serverUrl/d${item.path}"
                            else null
                            val hasPlayFootprint = currentItemUri != null &&
                                state.currentFootprint == currentItemUri
                            val isDirFootprint = item.isDirectory &&
                                state.currentFootprint == item.path
                            val hasFootprint = hasPlayFootprint || isDirFootprint

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
                                            onRecordFootprint(clickedVideoUri)
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
                                    Box {
                                        FileTypeIcon(
                                            item = item,
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
                                            text = item.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            color = if (hasPlayFootprint) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
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

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes.toDouble() / (1024 * 1024))
        else -> "%.2f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
    }
}
