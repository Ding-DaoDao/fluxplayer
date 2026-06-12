package com.fluxplayer.app.feature.player.danmaku

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
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.fluxplayer.app.core.model.DanmakuConfig

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
    onMediaItemTransitioned: ((Int) -> Unit)? = null,
) {
    var danmakuView by remember { mutableStateOf<DanmakuView?>(null) }
    var controller by remember { mutableStateOf<DanmakuController?>(null) }
    var density by remember { mutableFloatStateOf(1f) }

    // 获取 density
    val context = LocalContext.current
    density = context.resources.displayMetrics.density

    // 从 Player 获取当前播放速度
    var playbackSpeed by remember(player) {
        mutableFloatStateOf(player?.playbackParameters?.speed ?: 1.0f)
    }

    // DisposableEffect 1: 监听播放参数变化（播放速度）
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackParametersChanged(params: PlaybackParameters) {
                playbackSpeed = params.speed
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val v = danmakuView
                if (v != null) {
                    if (isPlaying) v.resumePlayback() else v.pausePlayback()
                }
            }
        }
        player?.addListener(listener)
        onDispose {
            player?.removeListener(listener)
        }
    }

    // disabled 时也保持 View 实例（用 visibility 控制），避免反复重建
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val d = ctx.resources.displayMetrics.density
            density = d
            val speed = playbackSpeed

            val view = DanmakuView(ctx).apply {
                danmakuOpacity = config.opacity
                speedMultiplier = config.speed
                this.playbackSpeed = speed
                bottomMargin = (config.bottomSafeAreaPx * d).toInt()
                maxActiveDanmaku = config.maxDanmakuCount
                this.displayDensity = d
                this.danmakuDensity = config.density
                this.blockKeywords = config.blockKeywords
                displayMode = config.displayMode
                timeOffsetMs = config.timeOffsetMs.toLong()
                trackSpacingPx = (config.trackSpacingDp * d).toInt()
                targetFps = config.targetFps
                baseFontSizePx = config.fontSize * d
                setZOrderOnTop(true)
            }

            val ctrl = DanmakuController(view, onMediaItemTransitioned = onMediaItemTransitioned)
            controller = ctrl
            view.setTag(ctrl)
            onController?.invoke(ctrl)

            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                private var loaded = false
                override fun onViewAttachedToWindow(v: View) {
                    if (loaded) return
                    loaded = true
                    view.post {
                        if (!enabled) return@post
                        Log.d(TAG, "View attached, loading ${danmakuList?.size ?: 0} danmaku items")
                        // 从 tag 取最新数据
                        val latestCtl = view.getTag() as? DanmakuController ?: ctrl
                        latestCtl.loadDanmaku(
                            player = player ?: return@post,
                            list = danmakuList ?: return@post,
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
            view.playbackSpeed = playbackSpeed
            view.timeOffsetMs = config.timeOffsetMs.toLong()
            view.trackSpacingPx = (config.trackSpacingDp * density).toInt()
            view.targetFps = config.targetFps
            view.bottomMargin = (config.bottomSafeAreaPx * density).toInt()
            view.maxActiveDanmaku = config.maxDanmakuCount
            view.displayDensity = density
            view.danmakuDensity = config.density
            view.blockKeywords = config.blockKeywords
            view.displayMode = config.displayMode
            view.baseFontSizePx = config.fontSize * density

            // 根据 enabled 控制 visibility
            view.visibility = if (enabled && danmakuList != null) View.VISIBLE else View.GONE

            // 配置变化后重新计算轨道
            view.post { view.reconfigure() }

            // 从 View tag 取 Controller（避免 Compose state 时序问题）
            val latestCtl = view.getTag() as? DanmakuController ?: controller
            if (latestCtl != null && player != null && enabled && danmakuList != null) {
                Log.d(TAG, "update: calling loadDanmaku (list=${danmakuList.size}, player=$player)")
                latestCtl.loadDanmaku(player, danmakuList)
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose {
            Log.d(TAG, "Disposing DanmakuOverlay permanently")
            controller?.suppressTransitions = true  // 先抑制回调，防止退出时 ExoPlayer 触发切集
            controller?.release()
            controller = null
            danmakuView = null
        }
    }
}
