package com.fluxplayer.app.feature.videopicker.screens.history

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.fluxplayer.app.core.model.PlaybackHistory
import com.fluxplayer.app.core.model.VideoSource
import com.fluxplayer.app.core.ui.components.NextSegmentedListItem
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.feature.videopicker.composables.CenterCircularProgressBar
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
    VideoSource.ALIYUN, VideoSource.PAN123, VideoSource.QUARK,
    VideoSource.UC, VideoSource.CLOUD189, VideoSource.YUN139,
    VideoSource.WEBDAV, VideoSource.OPENLIST
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HistoryTabContent(
    onPlayVideo: (Uri, String?) -> Unit,
    onPlayVideos: (List<Uri>, Uri) -> Unit = { _, _ -> },
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
            CenterCircularProgressBar()
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (uiState.historyList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = NextIcons.History,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "暂无播放记录",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    TextButton(onClick = { showClearDialog = true }) {
                        Icon(
                            imageVector = NextIcons.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("清除全部")
                    }
                }
            }
        } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
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
                    Icon(
                        imageVector = NextIcons.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("清除全部")
                }
            }
        }
        itemsIndexed(
            items = uiState.historyList,
            key = { _, item -> item.uriString },
        ) { index, historyItem ->
            val isFirst = index == 0
            val isLast = index == uiState.historyList.lastIndex

            NextSegmentedListItem(
                isFirstItem = isFirst,
                isLastItem = isLast,
                contentPadding = PaddingValues(8.dp),
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
                leadingContent = {
                    HistoryThumbnail(
                        thumbnailPath = historyItem.thumbnailPath,
                        source = historyItem.source,
                    )
                },
                content = {
                    Text(
                        text = historyItem.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                supportingContent = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = sourceLabel(historyItem.source),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                            Text(
                                text = " · ",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                            Text(
                                text = formatRelativeTime(historyItem.lastPlayedTime),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                            historyItem.parentPath?.let { parentPath ->
                                val displayLabel: String = if (parentPath.contains("|")) {
                                    // 新格式 "fileId|根目录|root/动漫|123/盗妖行|456" → 提取最后一级目录名
                                    val fullPath = parentPath.split("|", limit = 2).getOrNull(1) ?: parentPath
                                    fullPath.split("/").lastOrNull()?.split("|")?.firstOrNull() ?: fullPath
                                } else {
                                    // 旧格式 — 纯标签
                                    parentPath
                                }
                                Text(
                                    text = " · ",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                )
                                Text(
                                    text = "${displayLabel}/",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }
                        if (historyItem.duration > 0) {
                            PlaybackProgressBar(
                                percentage = historyItem.playedPercentage,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp),
                            )
                        }
                    }
                },
            )
        }
    }
    }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清除全部历史") },
            text = { Text("确定清除所有播放记录吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAll()
                    showClearDialog = false
                }) {
                    Text("清除")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("删除历史记录") },
            text = { Text("确定删除「${item.title}」的播放记录吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteItem(item.uriString)
                    itemToDelete = null
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("取消")
                }
            },
        )
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
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(percentage.coerceIn(0f, 1f))
                .height(3.dp)
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
    VideoSource.YUN139 -> NextIcons.Storage
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
    VideoSource.YUN139 -> MaterialTheme.colorScheme.primaryContainer
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
            .width(min(150.dp, LocalConfiguration.current.screenWidthDp.dp * 0.35f))
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp))
            .aspectRatio(16f / 10f),
    ) {
        // 图标常驻底层，缩略图加载成功时覆盖
        Icon(
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
