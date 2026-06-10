package dev.anilbeesetti.nextplayer.feature.player.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import dev.anilbeesetti.nextplayer.feature.player.extensions.noRippleClickable
import java.io.File

@Composable
fun BoxScope.VideoInfoView(
    show: Boolean,
    player: Player,
    qualityLabel: String?,
    onDismiss: () -> Unit,
) {
    if (!show) return

    var fileSizeText by remember { mutableStateOf<String?>(null) }
    var playUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(show) {
        val uri = player.currentMediaItem?.localConfiguration?.uri
        playUrl = uri?.toString() ?: "未知"
        fileSizeText = uri?.let { computeFileSize(it) } ?: "未知"
    }

    Box(
        modifier = Modifier
            .matchParentSize()
            .noRippleClickable(onClick = onDismiss),
    )

    OverlayView(show = show, title = "视频信息") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoRow("播放链接", playUrl ?: "加载中...")
            InfoRow("文件大小", fileSizeText ?: "加载中...")
            InfoRow("清晰度", qualityLabel ?: "未知")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            text = value,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

private suspend fun computeFileSize(uri: Uri): String {
    return try {
        val path = uri.path ?: return "未知"
        val file = File(path)
        if (!file.exists()) return "未知"
        formatFileSize(file.length())
    } catch (e: Exception) {
        "未知"
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
