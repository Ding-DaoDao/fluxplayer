package com.fluxplayer.app.core.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.ui.theme.FluxTheme
import kotlinx.coroutines.launch

/**
 * 章节列表右侧可拖拽滚动条（自绘实现，不依赖 Compose Scrollbar API）。
 *
 * 设计：克制胶囊底座 + 进度地图增强。
 * - 静止时细胶囊（6dp / 45% 透明），拖动时生长变亮（8dp / 80%）并浮现极淡轨道；
 * - [playedChapters] 已播章节在轨道上打点（primary 圆点），整书收听进度一览；
 * - 滚动条高度默认占可视区 65%（[heightFraction]），垂直居中，更紧凑；
 * - 拖动 thumb 按 y 坐标比例映射到列表索引并滚动定位，通过 [onChapterChanged] 回调。
 *
 * @param listState 列表滚动状态（LazyColumn 需传入相同 state）
 * @param totalCount LazyColumn 的总 item 数（含 header 等固定项）
 * @param itemOffset 列表头部固定项数量（如 header/catalog），打点索引需减去该值才是章节索引
 * @param onChapterChanged 拖动定位时回调当前列表索引（全局索引）
 * @param playedChapters 已播放章节（显示顺序索引），用于轨道打点
 * @param heightFraction 滚动条高度占可视区比例（0~1）
 */
@Composable
fun ChapterDragScrollbar(
    listState: LazyListState,
    totalCount: Int,
    itemOffset: Int = 0,
    onChapterChanged: (Int) -> Unit = {},
    playedChapters: Set<Int> = emptySet(),
    heightFraction: Float = 0.65f,
    modifier: Modifier = Modifier,
) {
    if (totalCount <= 0) return
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val colors = FluxTheme.colorScheme
    var isDragging by remember { mutableStateOf(false) }

    // 方案 1：静止 / 拖动双态动画（thumb 实心不透明；轨道常驻淡显，让 thumb 有底座、视觉平齐）
    val thumbWidth by animateDpAsState(if (isDragging) 8.dp else 6.dp, label = "thumbWidth")
    val thumbAlpha by animateFloatAsState(if (isDragging) 0.95f else 0.85f, label = "thumbAlpha")
    val trackAlpha by animateFloatAsState(if (isDragging) 0.18f else 0.10f, label = "trackAlpha")
    val thumbColorStatic = colors.onSurfaceVariant

    // 当前首可见章节（用于 thumb 位置）
    val firstVisibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    // 可视行数（用于 thumb 高度比例）
    val visibleCount by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.size } }
    val viewportRatio = (visibleCount.coerceAtLeast(1).toFloat() / totalCount).coerceIn(0.08f, 1f)

    fun indexFromY(yPx: Float, trackHeightPx: Int): Int {
        if (trackHeightPx <= 0) return 0
        val fraction = (yPx / trackHeightPx).coerceIn(0f, 1f)
        return (fraction * totalCount).toInt().coerceIn(0, totalCount - 1)
    }

    // 测量容器：撑满可视区取高度，实际滚动条区按 heightFraction 缩短并垂直居中。
    // 整体视觉隐形（alpha=0），仅保留右侧拖拽热区，拖动功能不受影响。
    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(28.dp)
            .graphicsLayer { alpha = 0f },
    ) {
        val scrollbarHeight = maxHeight * heightFraction.coerceIn(0.2f, 1f)
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(28.dp)
                .height(scrollbarHeight)
                .pointerInput(totalCount) {
                    var lastIndex = -1
                    fun scrollToChapter(index: Int) {
                        if (index != lastIndex) {
                            lastIndex = index
                            onChapterChanged(index)
                            scope.launch { listState.scrollToItem(index) }
                        }
                    }
                    detectDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            lastIndex = -1
                            scrollToChapter(indexFromY(offset.y, size.height))
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false },
                        onDrag = { change, _ ->
                            change.consume()
                            scrollToChapter(indexFromY(change.position.y, size.height))
                        },
                    )
                },
        ) {
            val trackHeightPx = with(density) { scrollbarHeight.toPx() }
            // thumb 高度 = 可视比例，最小 44dp 保证可抓取
            val thumbHeightPx = (trackHeightPx * viewportRatio).coerceAtLeast(with(density) { 44.dp.toPx() })
            val thumbHeight = with(density) { thumbHeightPx.toDp() }
            val thumbTop = with(density) {
                if (totalCount > 0) (trackHeightPx * firstVisibleIndex / totalCount).toDp() else 0.dp
            }

            // 轨道（仅拖动时浮现，动画淡入淡出）
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(2.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(1.dp))
                    .background(thumbColorStatic.copy(alpha = trackAlpha)),
            )
            // 已播章节打点（进度地图）：单 Canvas 绘制，避免数百个 Box 节点拖慢弹窗首帧
            Canvas(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(6.dp)
                    .fillMaxHeight(),
            ) {
                val dotRadius = 3.dp.toPx()
                val centerX = dotRadius
                playedChapters.forEach { chapterIndex ->
                    val dotTop = size.height * (chapterIndex + itemOffset) / totalCount
                    drawCircle(
                        color = colors.primary.copy(alpha = 0.55f),
                        radius = dotRadius,
                        center = Offset(centerX, dotTop),
                    )
                }
            }
            // thumb（可拖拽，圆润胶囊 + 动画，无投影避免浅色背景下出现杂边）
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = thumbTop)
                    .width(thumbWidth)
                    .height(thumbHeight)
                     .clip(RoundedCornerShape(4.dp))
                    .background(colors.onSurfaceVariant.copy(alpha = thumbAlpha)),
            )
        }
    }
}
