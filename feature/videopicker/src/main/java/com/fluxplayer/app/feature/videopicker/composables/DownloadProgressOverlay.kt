package com.fluxplayer.app.feature.videopicker.composables

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import kotlinx.coroutines.delay

/** 字节数格式化为可读大小（MB/GB） */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        .coerceAtMost(units.size - 1)
    val size = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "%.1f %s".format(size, units[digitGroups])
}

/**
 * 下载进度顶部通知栏（轻量浮动栏，不阻塞页面操作）
 * 下载完成后自动 3 秒消失
 *
 * @param progress 下载进度 0f..1f
 * @param fileName 下载文件名
 * @param completedFilePath 下载完成后文件路径，非空表示已完成
 * @param downloadedBytes 已下载字节数
 * @param totalBytes 文件总字节数
 * @param onCancel 取消下载回调
 * @param onOpenFile 打开已下载文件回调
 * @param onDismiss 关闭弹窗回调
 * @param autoDismissMs 完成状态自动消失毫秒数，默认 3000
 */
@Composable
fun DownloadNotificationBar(
    progress: Float,
    fileName: String,
    completedFilePath: String? = null,
    downloadedBytes: Long = 0,
    totalBytes: Long = 0,
    onCancel: () -> Unit,
    onOpenFile: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    autoDismissMs: Long = 3000,
) {
    val surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    // 下载完成后自动消失
    if (completedFilePath != null) {
        LaunchedEffect(completedFilePath) {
            delay(autoDismissMs)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = true,
        modifier = modifier,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(12.dp),
            shadowElevation = 6.dp,
            tonalElevation = 3.dp,
            color = surfaceColor,
        ) {
            if (completedFilePath != null) {
                // 下载完成
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = NextIcons.CheckBox,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "下载完成",
                            style = MaterialTheme.typography.labelMedium,
                            color = onSurfaceColor,
                        )
                        val detail = if (totalBytes > 0) "$fileName · ${formatBytes(totalBytes)}" else fileName
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = onSurfaceColor.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = NextIcons.Close,
                            contentDescription = "关闭",
                            tint = onSurfaceColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    TextButton(onClick = { onOpenFile(completedFilePath) }) {
                        Text("打开", color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else {
                // 下载中
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = fileName,
                            style = MaterialTheme.typography.bodySmall,
                            color = onSurfaceColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) {
                            Text("取消", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = onSurfaceColor.copy(alpha = 0.12f),
                    )
                    Spacer(Modifier.height(4.dp))
                    val progressText = if (totalBytes > 0) {
                        "${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}"
                    } else {
                        "${(progress * 100).toInt()}%"
                    }
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.labelSmall,
                        color = onSurfaceColor.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

/**
 * 下载进度底部弹窗（ModalBottomSheet） —— 保留旧版兼容
 *
 * @param progress 下载进度 0f..1f
 * @param fileName 下载文件名
 * @param completedFilePath 下载完成后文件路径，非空表示已完成
 * @param onCancel 取消下载回调
 * @param onOpenFile 打开已下载文件回调
 * @param onDismiss 关闭弹窗回调
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadBottomSheet(
    progress: Float,
    fileName: String,
    completedFilePath: String? = null,
    onCancel: () -> Unit,
    onOpenFile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (completedFilePath != null) {
                // 下载完成
                Icon(
                    imageVector = NextIcons.CheckBox,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text("下载完成", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss) { Text("关闭") }
                    Button(onClick = { onOpenFile(completedFilePath) }) { Text("打开文件") }
                }
            } else {
                // 下载中
                Text("下载中", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = onCancel) { Text("取消下载") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
