package dev.anilbeesetti.nextplayer.feature.player.danmaku

import android.util.Log
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import dev.anilbeesetti.nextplayer.core.model.DanmakuConfig

private const val TAG = "DanmakuOverlay"

/**
 * 弹幕叠加层 Composable。
 *
 * 使用自定义 [DanmakuView]（SurfaceView）渲染弹幕，取代 DFM DanmakuTextureView。
 */
@Composable
fun DanmakuOverlay(
    player: Player?,
    danmakuList: List<Danmaku>?,
    config: DanmakuConfig,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onController: ((DanmakuController) -> Unit)? = null,
    onMediaChanged: (() -> Unit)? = null,
) {
    var danmakuView by remember { mutableStateOf<DanmakuView?>(null) }
    var controller by remember { mutableStateOf<DanmakuController?>(null) }
    var density by remember { mutableFloatStateOf(1f) }

    // 获取 density
    val context = LocalContext.current
    density = context.resources.displayMetrics.density

    if (enabled && danmakuList != null) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                val d = ctx.resources.displayMetrics.density
                density = d

                val view = DanmakuView(ctx).apply {
                    danmakuOpacity = config.opacity
                    speedMultiplier = config.speed
                    bottomMargin = (config.bottomSafeAreaPx * d).toInt()
                    maxActiveDanmaku = config.maxDanmakuCount
                    displayDensity = d
                    displayMode = config.displayMode
                    timeOffsetMs = config.timeOffsetMs.toLong()
                    trackSpacingPx = (config.trackSpacingDp * d).toInt()
                    targetFps = config.targetFps
                    baseFontSizePx = config.fontSize * d
                    setZOrderOnTop(true)
                }

                val ctrl = DanmakuController(view, onMediaChanged = onMediaChanged)
                controller = ctrl
                view.setTag(ctrl)
                onController?.invoke(ctrl)

                view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    private var loaded = false
                    override fun onViewAttachedToWindow(v: View) {
                        if (loaded) return
                        loaded = true
                        view.post {
                            Log.d(TAG, "View attached, loading ${danmakuList.size} danmaku items")
                            // 从 tag 取最新数据
                            val latestCtl = view.getTag() as? DanmakuController ?: ctrl
                            latestCtl.loadDanmaku(
                                player = player ?: return@post,
                                list = danmakuList,
                            )
                        }
                    }
                    override fun onViewDetachedFromWindow(v: View) {
                        ctrl.release()
                    }
                })

                danmakuView = view
                view
            },
            update = { view ->
                view.danmakuOpacity = config.opacity
                view.speedMultiplier = config.speed
                view.timeOffsetMs = config.timeOffsetMs.toLong()
                view.trackSpacingPx = (config.trackSpacingDp * density).toInt()
                view.targetFps = config.targetFps
                view.bottomMargin = (config.bottomSafeAreaPx * density).toInt()
                view.maxActiveDanmaku = config.maxDanmakuCount
                view.displayDensity = density
                view.displayMode = config.displayMode
                view.baseFontSizePx = config.fontSize * density
                // 配置变化后重新计算轨道
                view.post { view.reconfigure() }

                // 从 View tag 取 Controller（避免 Compose state 时序问题）
                val latestCtl = view.getTag() as? DanmakuController ?: controller
                if (latestCtl != null && player != null) {
                    Log.d(TAG, "update: calling loadDanmaku (list=${danmakuList?.size ?: 0}, player=$player)")
                    latestCtl.loadDanmaku(player, danmakuList)
                } else {
                    Log.w(TAG, "update: skipping - controller=${latestCtl != null} player=${player != null}")
                }
            },
        )

        DisposableEffect(Unit) {
            onDispose {
                Log.d(TAG, "Disposing DanmakuOverlay")
                controller?.release()
                controller = null
                danmakuView = null
            }
        }
    }
}
