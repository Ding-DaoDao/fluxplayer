package com.fluxplayer.app.feature.videopicker.composables

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.util.fastAny
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.fluxplayer.app.core.ui.R as UiR

@Composable
fun ZoomableImage(
    imageUrl: String,
    headers: Map<String, String>,
    modifier: Modifier = Modifier,
    onScaleChanged: (Float) -> Unit = {},
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val animatedScale by animateFloatAsState(
        targetValue = scale,
        animationSpec = tween(200),
        label = "zoomScale",
    )

    val context = LocalContext.current
    val networkHeaders = remember(headers) {
        if (headers.isEmpty()) null
        else NetworkHeaders.Builder().apply {
            headers.forEach { (k, v) -> set(k, v) }
        }.build()
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUrl)
                .apply { networkHeaders?.let { httpHeaders(it) } }
                .crossfade(true)
                .build(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = animatedScale
                    scaleY = animatedScale
                    translationX = offsetX
                    translationY = offsetY
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                                onScaleChanged(1f)
                            } else {
                                scale = 2.5f
                                onScaleChanged(2.5f)
                            }
                        },
                    )
                }
                // 缩放 + 平移手势：未放大时不消费事件（让 HorizontalPager 处理左右滑动）
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var pastSlop = false
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.changes.fastAny { it.isConsumed }) break
                            if (event.type == PointerEventType.Release ||
                                event.type == PointerEventType.Exit
                            ) break

                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()

                            // 检测是否超过了水平/垂直 slop 距离
                            if (!pastSlop) {
                                val anyMoved = event.changes.fastAny {
                                    val dx = it.position.x - it.previousPosition.x
                                    val dy = it.position.y - it.previousPosition.y
                                    kotlin.math.abs(dx) > viewConfiguration.touchSlop ||
                                        kotlin.math.abs(dy) > viewConfiguration.touchSlop
                                }
                                if (anyMoved) pastSlop = true
                                // 还没超过 slop：仅有缩放时不消费，让 pager 继续等待
                                if (!pastSlop && zoom == 1f) continue
                            }

                            // 已超过 slop：缩放时始终处理
                            if (zoom != 1f) {
                                val newScale = (scale * zoom).coerceIn(0.5f, 5f)
                                scale = newScale
                                onScaleChanged(newScale)
                                if (newScale > 1f) {
                                    val maxX = (newScale - 1f) * size.width / 2f
                                    val maxY = (newScale - 1f) * size.height / 2f
                                    offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                    offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            // 平移：仅在放大状态下消费（平移图片），否则交给 HorizontalPager
                            if (scale > 1f) {
                                val maxX = (scale - 1f) * size.width / 2f
                                val maxY = (scale - 1f) * size.height / 2f
                                offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
            contentScale = ContentScale.Fit,
            placeholder = painterResource(UiR.drawable.ic_file_image),
            error = painterResource(UiR.drawable.ic_file_image),
            fallback = painterResource(UiR.drawable.ic_file_image),
        )
    }
}
