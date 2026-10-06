package com.fluxplayer.app.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.theme.FluxTheme

/**
 * 播放前确认弹窗：书名 + 封面 + 集数 + 播放按钮。
 *
 * 在线书源与本地书库共用；[resumeLabel] 非空时（如「已听到第 27 集」）会提示续播位置。
 */
@Composable
fun PlayConfirmDialog(
    title: String,
    chapterCount: Int,
    coverModel: Any?,
    resumeLabel: String?,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    playEnabled: Boolean = true,
    errorContent: (@Composable () -> Unit)? = null,
) {
    val colors = FluxTheme.colorScheme
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = colors.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        title = null,
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 108.dp, height = 144.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    if (coverModel != null) {
                        AsyncImage(
                            model = coverModel,
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = NextIcons.Headset,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.size(44.dp),
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    text = title,
                    style = FluxTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                if (!loading && errorContent == null) Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colors.primaryContainer,
                ) {
                    Text(
                        text = "$chapterCount 集",
                        style = FluxTheme.typography.labelMedium,
                        color = colors.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                if (!resumeLabel.isNullOrEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = resumeLabel,
                        style = FluxTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (loading) {
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                errorContent?.let {
                    Spacer(Modifier.height(12.dp))
                    it()
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Surface(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(14.dp),
                    color = colors.surfaceContainerHighest,
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 13.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "取消",
                            style = FluxTheme.typography.labelLarge,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                Surface(
                    onClick = onPlay,
                    enabled = playEnabled,
                    shape = RoundedCornerShape(14.dp),
                    color = if (playEnabled) colors.primary else colors.surfaceContainerHighest,
                    modifier = Modifier.weight(1.4f),
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 13.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = NextIcons.Play,
                            contentDescription = null,
                            tint = colors.onPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "播放",
                            style = FluxTheme.typography.labelLarge,
                            color = colors.onPrimary,
                        )
                    }
                }
            }
        },
    )
}

/** 圆形播放图标，供弹窗与横幅复用。 */
@Composable
fun PlayCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    enabled: Boolean = true,
) {
    val colors = FluxTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = colors.primary,
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = NextIcons.Play,
                contentDescription = "播放",
                tint = colors.onPrimary,
                modifier = Modifier.size(size * 0.45f),
            )
        }
    }
}
