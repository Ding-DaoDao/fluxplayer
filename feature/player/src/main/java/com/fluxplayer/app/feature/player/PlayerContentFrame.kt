package com.fluxplayer.app.feature.player

import android.graphics.Rect
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPresentationState
import com.fluxplayer.app.feature.player.extensions.toContentScale
import com.fluxplayer.app.feature.player.state.ControlsVisibilityState
import com.fluxplayer.app.feature.player.state.PictureInPictureState
import com.fluxplayer.app.feature.player.state.SeekGestureState
import com.fluxplayer.app.feature.player.state.TapGestureState
import com.fluxplayer.app.feature.player.state.VideoZoomAndContentScaleState
import com.fluxplayer.app.feature.player.state.VolumeAndBrightnessGestureState
import com.fluxplayer.app.feature.player.ui.PlayerGestures
import com.fluxplayer.app.feature.player.ui.ShutterView
import com.fluxplayer.app.feature.player.ui.SubtitleConfiguration
import com.fluxplayer.app.feature.player.ui.SubtitleView

@OptIn(UnstableApi::class)
@Composable
fun PlayerContentFrame(
    modifier: Modifier = Modifier,
    player: Player,
    pictureInPictureState: PictureInPictureState,
    controlsVisibilityState: ControlsVisibilityState,
    tapGestureState: TapGestureState,
    seekGestureState: SeekGestureState,
    videoZoomAndContentScaleState: VideoZoomAndContentScaleState,
    volumeAndBrightnessGestureState: VolumeAndBrightnessGestureState,
    subtitleConfiguration: SubtitleConfiguration,
    onSurfaceView: ((SurfaceView?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val presentationState = rememberPresentationState(player)

    // 使用 SurfaceView 替代 TextureView：
    // TextureView 在 Android 13+ 上误报 HDR 能力导致 Media3 跳过 tone mapping，
    // SurfaceView 能正确处理 HDR 输出 / SDR 降级，是 Google 官方推荐的 HDR 播放方案。
    val surfaceView = remember {
        SurfaceView(context).apply {
            setZOrderMediaOverlay(false) // SurfaceView 置于 Compose UI 下层
        }
    }

    DisposableEffect(surfaceView) {
        onSurfaceView?.invoke(surfaceView)
        onDispose {
            onSurfaceView?.invoke(null)
        }
    }

    AndroidView(
        factory = { ctx ->
            player.setVideoSurfaceView(surfaceView)
            surfaceView
        },
        modifier = modifier
            .resizeWithContentScale(
                contentScale = videoZoomAndContentScaleState.videoContentScale.toContentScale(),
                sourceSizeDp = presentationState.videoSizeDp?.let { size ->
                    size.copy(
                        width = with(LocalDensity.current) { size.width.toDp().value },
                        height = with(LocalDensity.current) { size.height.toDp().value },
                    )
                },
            )
            .onGloballyPositioned {
                val bounds = it.boundsInWindow()
                val rect = Rect(
                    bounds.left.toInt(),
                    bounds.top.toInt(),
                    bounds.right.toInt(),
                    bounds.bottom.toInt(),
                )
                pictureInPictureState.setVideoViewRect(rect)
            }
            .graphicsLayer {
                scaleX = videoZoomAndContentScaleState.zoom
                scaleY = videoZoomAndContentScaleState.zoom
                translationX = videoZoomAndContentScaleState.offset.x
                translationY = videoZoomAndContentScaleState.offset.y
            },
        onRelease = { player.clearVideoSurfaceView(surfaceView) },
    )

    PlayerGestures(
        controlsVisibilityState = controlsVisibilityState,
        tapGestureState = tapGestureState,
        pictureInPictureState = pictureInPictureState,
        seekGestureState = seekGestureState,
        videoZoomAndContentScaleState = videoZoomAndContentScaleState,
        volumeAndBrightnessGestureState = volumeAndBrightnessGestureState,
    )

    SubtitleView(
        player = player,
        isInPictureInPictureMode = pictureInPictureState.isInPictureInPictureMode,
        configuration = subtitleConfiguration,
    )

    if (presentationState.coverSurface) {
        ShutterView()
    }
}
