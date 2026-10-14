package com.fluxplayer.app.feature.videopicker.screens.history

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.fluxplayer.app.core.model.PlaybackHistory
import com.fluxplayer.app.core.model.VideoSource
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.components.FluxIcon
import com.fluxplayer.app.core.ui.components.FluxText
import com.fluxplayer.app.core.ui.components.NextDialog
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.theme.LocalMediaScrollBottomPadding
import java.util.concurrent.TimeUnit

/** 云盘 Source → BrowseTabs provider ID 映射 */
private fun cloudSourceToProvider(source: VideoSource): String? = when (source) {
    VideoSource.ALIYUN -> "alipan"
    VideoSource.PAN123 -> "pan123"
    VideoSource.QUARK -> "quark"
    VideoSource.UC -> "uc"
    VideoSource.CLOUD189 -> "cloud189"
    VideoSource.YUN139 -> "yun139"
    VideoSource.WEBDAV -> "webdav"
    VideoSource.OPENLIST -> "openlist"
    else -> null
}

/** 从 URI 字符串提取父目录路径（用于旧格式/无缓存记录跳转） */
private fun extractParentDirFromUri(uriString: String, providerId: String): String? {
    val uri = Uri.parse(uriString)
    val path = when (providerId) {
        "webdav" -> uri.path
        "openlist" -> uri.path?.removePrefix("/d")
        else -> return null
    } ?: return null
    val segments = path.trimEnd('/').split("/").filter { it.isNotEmpty() }
    if (segments.size <= 1) return "/"
    return "/" + segments.dropLast(1).joinToString("/")
}

private val cloudSources = setOf(
    VideoSource.ALIYUN,
    VideoSource.PAN123,
    VideoSource.QUARK,
    VideoSource.UC,
    VideoSource.CLOUD189,
    VideoSource.YUN139,
    VideoSource.WEBDAV,
    VideoSource.OPENLIST,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HistoryTabContent(
    onPlayVideo: (Uri, String?) -> Unit,
    onPlayVideos: (List<Uri>, Uri, Boolean) -> Unit = { _, _, _ -> },
    onNavigateToCloudDir: (String, String, String) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var itemToDelete by remember { mutableStateOf<PlaybackHistory?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    if (uiState.isLoading) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            com.fluxplayer.app.core.ui.components.MediaListSkeleton()
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (uiState.historyList.isEmpty()) {
            com.fluxplayer.app.core.ui.components.MediaEmptyState(
                icon = NextIcons.History,
                title = "暂无播放记录",
                description = "播放过的视频会显示在这里，方便继续观看。",
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp + LocalMediaScrollBottomPadding.current),
            ) {
                // 一键清除按钮
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(
                            onClick = { showClearDialog = true },
                        ) {
                            FluxIcon(
                                imageVector = NextIcons.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            FluxText("清除全部")
                        }
                    }
                }
                itemsIndexed(
                    items = uiState.historyList,
                    key = { _, item -> item.uriString },
                ) { _, historyItem ->
                    HistoryCard(
                        history = historyItem,
                        onClick = {
                            // 解析 parentPath：新格式 "fileId|label"，旧格式为纯 label（无 "|"）
                            val hasParentFileId = historyItem.parentPath?.contains("|") == true
                            val parentFileId: String?
                            val displayLabel: String?
                            if (hasParentFileId) {
                                val parts = historyItem.parentPath!!.split("|", limit = 2)
                                parentFileId = parts[0].takeIf { it.isNotBlank() }
                                displayLabel = parts.getOrNull(1)
                            } else {
                                parentFileId = null
                                displayLabel = historyItem.parentPath
                            }

                            val providerId = cloudSourceToProvider(historyItem.source)
                            if (providerId != null) {
                                if (parentFileId != null) {
                                    // 云盘播放记录（新格式）→ 跳转到所在目录
                                    onNavigateToCloudDir(providerId, parentFileId, displayLabel ?: "目录")
                                } else {
                                    // 旧格式/无缓存记录 → 从 URI 提取父目录路径再跳转
                                    val dirPath = extractParentDirFromUri(historyItem.uriString, providerId)
                                    if (dirPath != null) {
                                        onNavigateToCloudDir(providerId, dirPath, displayLabel ?: "目录")
                                    } else {
                                        // 无法提取路径，fallback 直接播放
                                        val uri = historyItem.originalUriString?.let { Uri.parse(it) }
                                            ?: Uri.parse(historyItem.uriString)
                                        onPlayVideo(uri, null)
                                    }
                                }
                            } else {
                                // 本地/其他 → 直接播放
                                val uri = historyItem.originalUriString?.let { Uri.parse(it) }
                                    ?: Uri.parse(historyItem.uriString)
                                onPlayVideo(uri, null)
                            }
                        },
                        onLongClick = {
                            itemToDelete = historyItem
                        },

                    )
                }
            }
        }
    }

    if (showClearDialog) {
        NextDialog(
            onDismissRequest = { showClearDialog = false },
            title = { FluxText("清除全部历史") },
            content = { FluxText("确定清除所有播放记录吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAll()
                    showClearDialog = false
                }) {
                    FluxText("清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    FluxText("取消")
                }
            },
        )
    }

    itemToDelete?.let { item ->
        NextDialog(
            onDismissRequest = { itemToDelete = null },
            title = { FluxText("删除历史记录") },
            content = { FluxText("确定删除「${item.title}」的播放记录吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteItem(item.uriString)
                    itemToDelete = null
                }) {
                    FluxText("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    FluxText("取消")
                }
            },
        )
    }
}

/** 历史记录采用完整卡片布局，进度跨整张卡片，避免挤在缩略图旁边。 */
@Composable
private fun HistoryCard(history: PlaybackHistory, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(28.dp),
        color = colors.surfaceContainerLow,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                HistoryThumbnail(history.thumbnailPath, history.source)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(sourceLabel(history.source), style = MaterialTheme.typography.labelMedium, color = colors.primary)
                    Text(history.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        stringResource(R.string.last_playback_position, formatPlaybackPosition(history.playbackPosition)),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            if (history.duration > 0) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlaybackProgressBar(history.playedPercentage, Modifier.weight(1f))
                    Text(
                        stringResource(R.string.playback_progress_percent, (history.playedPercentage * 100).toInt()),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.primary,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val path = history.parentPath?.substringAfter('|', history.parentPath.orEmpty())
                    ?.substringAfterLast('/')?.substringBefore('|').orEmpty()
                Text(path, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text(formatRelativeTime(history.lastPlayedTime), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PlaybackProgressBar(
    percentage: Float,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(percentage.coerceIn(0f, 1f))
                .height(6.dp)
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun sourceIcon(source: VideoSource) = when (source) {
    VideoSource.LOCAL -> NextIcons.Movie
    VideoSource.WEBDAV -> NextIcons.Folder
    VideoSource.OPENLIST -> NextIcons.Link
    VideoSource.ALIYUN,
    VideoSource.PAN123,
    VideoSource.QUARK,
    VideoSource.UC,
    VideoSource.CLOUD189,
    VideoSource.YUN139,
    -> NextIcons.Storage
    VideoSource.OTHER -> NextIcons.Play
}

@Composable
private fun sourceColor(source: VideoSource) = when (source) {
    VideoSource.LOCAL -> MaterialTheme.colorScheme.primary
    VideoSource.WEBDAV -> MaterialTheme.colorScheme.tertiary
    VideoSource.OPENLIST -> MaterialTheme.colorScheme.secondary
    VideoSource.ALIYUN,
    VideoSource.PAN123,
    VideoSource.QUARK,
    VideoSource.UC,
    VideoSource.CLOUD189,
    VideoSource.YUN139,
    -> MaterialTheme.colorScheme.primaryContainer
    VideoSource.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun sourceLabel(source: VideoSource) = when (source) {
    VideoSource.LOCAL -> "本地"
    VideoSource.WEBDAV -> "WebDAV"
    VideoSource.OPENLIST -> "OpenList"
    VideoSource.ALIYUN -> "阿里云盘"
    VideoSource.PAN123 -> "123云盘"
    VideoSource.QUARK -> "夸克"
    VideoSource.UC -> "UC"
    VideoSource.CLOUD189 -> "天翼云盘"
    VideoSource.YUN139 -> "移动云盘"
    VideoSource.OTHER -> "其他"
}

private fun formatPlaybackPosition(position: Long): String {
    val seconds = (position.coerceAtLeast(0L) / 1000L)
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainder = (seconds % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$remainder" else "$minutes:$remainder"
}

private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "刚刚"
        diff < TimeUnit.HOURS.toMillis(1) -> {
            val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
            "${minutes}分钟前"
        }
        diff < TimeUnit.DAYS.toMillis(1) -> {
            val hours = TimeUnit.MILLISECONDS.toHours(diff)
            "${hours}小时前"
        }
        diff < TimeUnit.DAYS.toMillis(7) -> {
            val days = TimeUnit.MILLISECONDS.toDays(diff)
            "${days}天前"
        }
        else -> {
            val days = TimeUnit.MILLISECONDS.toDays(diff)
            "${days}天前"
        }
    }
}

@Composable
private fun HistoryThumbnail(
    thumbnailPath: String?,
    source: VideoSource,
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .width(min(112.dp, LocalConfiguration.current.screenWidthDp.dp * 0.28f))
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
            .aspectRatio(16f / 10f),
    ) {
        // 图标常驻底层，缩略图加载成功时覆盖
        FluxIcon(
            imageVector = sourceIcon(source),
            contentDescription = source.name,
            tint = MaterialTheme.colorScheme.surfaceColorAtElevation(100.dp),
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize(0.5f),
        )
        if (thumbnailPath != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(thumbnailPath)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
