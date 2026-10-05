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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.sp
import com.fluxplayer.app.core.ui.cache.rememberBookCoverImageLoader
import coil3.compose.AsyncImage
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.theme.FluxTheme
import com.fluxplayer.app.feature.player.model.AudioBook

@Composable
fun AudiobookBookCard(
    book: AudioBook,
    resumeChapterIndex: Int? = null,
    onClick: () -> Unit,
    coverModel: Any? = book.coverUri,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = FluxTheme.colorScheme

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── 封面缩略图 ──
            Box(
                modifier = Modifier
                    .size(width = 60.dp, height = 80.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                if (coverModel != null) {
                    // 显式限定解码尺寸（2x 显示尺寸），避免大封面全尺寸解码拖慢列表
                    AsyncImage(
                    imageLoader = rememberBookCoverImageLoader(),
                        model = coverModel,
                        contentDescription = book.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = NextIcons.Headset,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                        tint = colors.onSurfaceVariant.copy(alpha = 0.35f),
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // ── 书籍信息 ──
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = book.title,
                    style = FluxTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 19.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 章节数标签
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colors.primaryContainer,
                    ) {
                        Text(
                            text = subtitle ?: "${book.chapterCount} 章节",
                            color = colors.onPrimaryContainer,
                            style = FluxTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    // 上次听到第 N 集
                    if (resumeChapterIndex != null && resumeChapterIndex < book.chapterCount) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = colors.surfaceContainerHighest,
                        ) {
                            Text(
                                text = "听到第 ${resumeChapterIndex + 1} 集",
                                color = colors.onSurfaceVariant,
                                style = FluxTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
            }

            // ── 右箭头 ──
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
