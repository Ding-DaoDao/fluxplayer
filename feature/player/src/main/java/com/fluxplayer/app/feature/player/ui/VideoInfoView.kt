package com.fluxplayer.app.feature.player.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import com.fluxplayer.app.core.ui.components.FluxText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.fluxplayer.app.feature.player.R
import com.fluxplayer.app.feature.player.extensions.noRippleClickable
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

    // 在 Composable 上下文取值，供 LaunchedEffect 与渲染使用
    val unknownText = stringResource(R.string.video_info_unknown)
    val loadingText = stringResource(R.string.video_info_loading)

    LaunchedEffect(show) {
        val uri = player.currentMediaItem?.localConfiguration?.uri
        playUrl = uri?.toString() ?: unknownText
        fileSizeText = uri?.let { computeFileSize(it) } ?: unknownText
    }

    Box(
        modifier = Modifier
            .matchParentSize()
            .noRippleClickable(onClick = onDismiss),
    )

    OverlayView(show = show, title = stringResource(R.string.video_info_title)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoRow(stringResource(R.string.video_info_play_url), playUrl ?: loadingText)
            InfoRow(stringResource(R.string.video_info_file_size), fileSizeText ?: loadingText)
            InfoRow(stringResource(R.string.quality_title), qualityLabel ?: unknownText)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FluxText(
            text = label,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.titleSmall,
        )
        FluxText(
            text = value,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** @return 格式化后的文件大小字符串；未知/无法访问时返回 null */
private suspend fun computeFileSize(uri: Uri): String? {
    return try {
        val path = uri.path ?: return null
        val file = File(path)
        if (!file.exists()) return null
        formatFileSize(file.length())
    } catch (e: Exception) {
        null
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
