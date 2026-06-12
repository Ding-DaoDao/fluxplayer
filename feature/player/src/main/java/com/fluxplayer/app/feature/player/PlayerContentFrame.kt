package com.fluxplayer.app.feature.player

import android.graphics.Rect
import android.view.TextureView
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
    onTextureView: ((TextureView?) -> Unit)? = null,
) {
    val context = LocalContext.current
    val presentationState = rememberPresentationState(player)

    // 使用 remember + DisposableEffect 管理 TextureView，
    // 避免 rememberSaveable 机制尝试序列化不可序列化的 TextureView/回调
    val textureView = remember { TextureView(context) }

    DisposableEffect(textureView) {
        onTextureView?.invoke(textureView)
        onDispose {
            onTextureView?.invoke(null)
        }
    }

    AndroidView(
        factory = { ctx ->
            player.setVideoTextureView(textureView)
            textureView
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
        onRelease = { player.setVideoTextureView(null) },
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
