package com.fluxplayer.app.feature.player

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import coil3.Image
import coil3.compose.AsyncImage
import coil3.toBitmap
import com.fluxplayer.app.core.ui.cache.rememberBookCoverImageLoader
import com.fluxplayer.app.core.ui.theme.FluxTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun PlaybackArtworkBackground(color: Color, modifier: Modifier = Modifier) {
    val tint by animateColorAsState(color, tween(600), label = "封面背景")
    Box(
        modifier.background(
            Brush.verticalGradient(
                0f to tint.copy(alpha = tint.alpha * 0.12f),
                0.25f to tint.copy(alpha = tint.alpha * 0.22f),
                0.6f to tint.copy(alpha = tint.alpha * 0.06f),
                0.75f to Color.Transparent,
            ),
        ),
    )
}

/** 复用封面加载结果提取颜色，避免为了背景重复请求网盘。 */
@Composable
internal fun PlaybackAlbumCover(artwork: Any?, title: String, onColor: (Color) -> Unit, modifier: Modifier = Modifier) {
    var image by remember(artwork) { mutableStateOf<Image?>(null) }
    val updateColor by rememberUpdatedState(onColor)
    LaunchedEffect(image) {
        val loaded = image ?: return@LaunchedEffect
        val color = try {
            withContext(Dispatchers.Default) {
                val bitmap = loaded.toBitmap()
                val readable = if (bitmap.config == Bitmap.Config.HARDWARE) bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
                readable?.let {
                    val palette = Palette.from(it).resizeBitmapArea(128 * 128).generate()
                    palette.mutedSwatch?.rgb ?: palette.dominantSwatch?.rgb
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        color?.let { updateColor(Color(it)) }
    }
    val opacity by animateFloatAsState(if (image == null) 0f else 1f, tween(350), label = "封面淡入")
    val ratio = image?.let { (it.width.toFloat() / it.height.coerceAtLeast(1)).coerceIn(0.5f, 2f) } ?: 0.75f
    // 保持原有封面区域尺寸，封面在区域内完整展示，不挤动下方播放控件。
    BoxWithConstraints(modifier.fillMaxWidth(0.72f).aspectRatio(1f), contentAlignment = Alignment.Center) {
        val width = if (ratio < 1f) maxHeight * ratio else maxWidth
        val height = if (ratio < 1f) maxHeight else maxWidth / ratio
        Box(
            Modifier.size(width, height)
                .shadow(12.dp, RoundedCornerShape(18.dp))
                .clip(RoundedCornerShape(18.dp))
                .background(FluxTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(FluxTheme.colorScheme.secondaryContainer, FluxTheme.colorScheme.surfaceContainer)),
                ).padding(20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("有声读物", style = FluxTheme.typography.labelSmall, color = FluxTheme.colorScheme.onSurfaceVariant)
                Text(
                    title.ifBlank { "正在收听" },
                    modifier = Modifier.padding(top = 16.dp),
                    style = FluxTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    color = FluxTheme.colorScheme.onSurface,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (artwork != null) {
                AsyncImage(
                    imageLoader = rememberBookCoverImageLoader(),
                    model = artwork,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().alpha(opacity),
                    onSuccess = { image = it.result.image },
                    onError = { image = null },
                )
            }
        }
    }
}
