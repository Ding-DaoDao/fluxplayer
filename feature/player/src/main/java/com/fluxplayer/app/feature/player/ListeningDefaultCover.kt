package com.fluxplayer.app.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxplayer.app.core.ui.theme.FluxTheme

/**
 * 听书默认封面：没有真实封面时画一张仿实体书的封面。
 *
 * 版式参考番茄系 App 的默认封面——书脊折痕、内封描边、书名签（带分割线和作者）、底部微章。
 * 所有颜色取自主题，浅色与深色主题下都保持可读；同一书名永远得到同一种书脊配色。
 */
@Composable
fun ListeningDefaultCover(
    title: String,
    subtitle: String = "",
    modifier: Modifier = Modifier,
) {
    val info = remember(title, subtitle) { listeningCoverInfo(title, subtitle) }
    val colors = FluxTheme.colorScheme
    val accent = remember(info.title) { listeningCoverAccent(info.title) }
    val base = accent.copy(alpha = 0.14f).compositeOver(colors.surfaceContainerHighest)

    BoxWithConstraints(modifier) {
        val width = maxWidth
        val height = if (maxHeight.value.isFinite()) maxHeight else width * 1.4f
        val compact = width < 72.dp
        val spine = minOf(width * 0.1f, maxOf(3.dp, width * 0.055f))

        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawRect(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.09f),
                        base,
                        Color.Black.copy(alpha = 0.06f),
                    ),
                ),
            )
            // 左侧书脊：靠内侧渐隐的暗部 + 一道折痕高光
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.20f),
                    0.55f to Color.Black.copy(alpha = 0.05f),
                    1f to Color.Transparent,
                    startX = 0f,
                    endX = spine.toPx(),
                ),
                size = Size(spine.toPx(), h),
            )
            drawLine(
                color = Color.White.copy(alpha = 0.14f),
                start = Offset(spine.toPx(), 0f),
                end = Offset(spine.toPx(), h),
                strokeWidth = 0.8.dp.toPx(),
            )
            // 内封描边
            val inset = 6.dp.toPx()
            val borderLeft = spine.toPx() + 5.dp.toPx()
            drawRoundRect(
                color = accent.copy(alpha = 0.22f),
                topLeft = Offset(borderLeft, inset),
                size = Size(
                    (w - borderLeft - inset).coerceAtLeast(1f),
                    (h - inset * 2).coerceAtLeast(1f),
                ),
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(width = 0.8.dp.toPx()),
            )
        }

        val titleSize = listeningCoverTitleSize(width.value, info.title.length).sp
        val contentStart = spine + 9.dp
        val contentEnd = 8.dp

        if (compact) {
            Box(
                Modifier.fillMaxSize().padding(start = contentStart, end = contentEnd),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = info.title,
                    color = colors.onSurface,
                    fontSize = 9.sp,
                    lineHeight = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = contentStart,
                        end = contentEnd,
                        top = 8.dp,
                        bottom = if (height > 110.dp) 24.dp else 8.dp,
                    )
                    .offset(y = height * -0.14f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(min = (width - contentStart - contentEnd) * 0.72f)
                        .background(colors.surface.copy(alpha = 0.55f), RoundedCornerShape(3.5.dp))
                        .border(0.6.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(3.5.dp))
                        .padding(horizontal = 6.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = info.title,
                        color = colors.onSurface,
                        fontSize = titleSize,
                        lineHeight = titleSize * 1.25f,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.3.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (info.subtitle.isNotBlank() && height > 96.dp) {
                        Spacer(Modifier.height(5.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val line = accent.copy(alpha = 0.35f)
                            Box(Modifier.width(12.dp).height(0.6.dp).background(line))
                            Spacer(Modifier.width(3.5.dp))
                            Box(Modifier.size(2.2.dp).background(line, CircleShape))
                            Spacer(Modifier.width(3.5.dp))
                            Box(Modifier.width(12.dp).height(0.6.dp).background(line))
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = "${info.subtitle} 著",
                            color = colors.onSurfaceVariant.copy(alpha = 0.9f),
                            fontSize = (titleSize.value * 0.78f).coerceIn(8.5f, 12f).sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.2.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        if (height > 110.dp) {
            // 底部古典微章
            Text(
                text = "精品",
                color = accent.copy(alpha = 0.7f),
                fontSize = (titleSize.value * 0.45f).coerceIn(6.5f, 10f).sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .border(0.6.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                    .padding(horizontal = 9.dp, vertical = 2.dp),
            )
        }
    }
}

/** 书名宽度不同时按封面实际宽度给字号，避免大字散落或小字看不清。 */
private fun listeningCoverTitleSize(widthDp: Float, length: Int): Float {
    val base = when {
        widthDp < 125f -> when {
            length <= 5 -> 12.5f
            length <= 9 -> 11f
            else -> 10f
        }
        else -> {
            val density = (widthDp / 110f).coerceIn(1f, 1.55f)
            when {
                length <= 5 -> 14.5f
                length <= 9 -> 13f
                else -> 11.5f
            } * density
        }
    }
    return base.coerceIn(9.5f, 22f)
}

private data class ListeningCoverInfo(val title: String, val subtitle: String)

private val coverNoiseTag = Regex(
    """[（(\[【]\s*(?:精校版?|校对版?|全本|全集|完结|完本|精修版?|无错版?|未删节|未删减|手打|更新|排版|精编)[^）)\]】]*[）)\]】]""",
)
private val coverAuthorPrefix = Regex("""^(?:作者|by)[:：\s]*""", RegexOption.IGNORE_CASE)
private val coverAuthorInline = Regex("""[（(\[【]?\s*作者\s*[:：\s]+\s*([^）)\]】\s]+)[）)\]】]?""")
private val coverByInline = Regex("""[（(\[【]?\s*by\s*[:：\s]+\s*([^）)\]】\s]+)[）)\]】]?""", RegexOption.IGNORE_CASE)
private val coverEdgePunctuation = Regex("""^[_\-—\s,，:：]+|[_\-—\s,，:：]+$""")
private val coverSpaces = Regex("""\s+""")

/**
 * 清洗书名与作者：把混在标题里的「作者：xxx」「by xxx」抽成作者，
 * 并去掉 (精校版)、(全本) 之类的打包噪音，还原本来的书名。
 */
private fun listeningCoverInfo(rawTitle: String, rawSubtitle: String): ListeningCoverInfo {
    var title = rawTitle.trim()
    var subtitle = rawSubtitle.trim()

    if (subtitle.isEmpty() || subtitle == "未知作者") {
        val inline = coverAuthorInline.find(title) ?: coverByInline.find(title)
        if (inline != null) {
            subtitle = inline.groupValues.getOrNull(1)?.trim().orEmpty()
            title = title.removeRange(inline.range).trim()
        }
    }

    title = title.replace(coverNoiseTag, "").replace(coverSpaces, " ").trim()
    title = title.replace(coverEdgePunctuation, "").trim()
    subtitle = subtitle.replace(coverAuthorPrefix, "").trim()

    if (title.isEmpty()) title = rawTitle.trim()
    return ListeningCoverInfo(title = title, subtitle = subtitle)
}

private val listeningCoverPalette = listOf(
    Color(0xFF5B7BB5),
    Color(0xFF5E8F72),
    Color(0xFF9A7A4E),
    Color(0xFF7D6BA0),
    Color(0xFF4E8391),
    Color(0xFFA06070),
)

/** 同一个书名永远同一个色，方便在书库里靠颜色认书。 */
private fun listeningCoverAccent(title: String): Color =
    listeningCoverPalette[Math.floorMod(title.hashCode(), listeningCoverPalette.size)]
