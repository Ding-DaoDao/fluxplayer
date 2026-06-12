package com.fluxplayer.app.feature.player.ui

import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.media3.common.Player
import com.fluxplayer.app.core.model.DanmakuConfig
import com.fluxplayer.app.core.model.VideoContentScale
import com.fluxplayer.app.feature.player.extensions.noRippleClickable
import com.fluxplayer.app.feature.player.state.SubtitleOptionsEvent

@Composable
fun BoxScope.OverlayShowView(
    player: Player,
    overlayView: OverlayView?,
    videoContentScale: VideoContentScale,
    danmakuConfig: DanmakuConfig = DanmakuConfig(),
    onDanmakuConfigChange: (DanmakuConfig) -> Unit = {},
    onDismiss: () -> Unit = {},
    onSelectSubtitleClick: () -> Unit = {},
    onSubtitleOptionEvent: (SubtitleOptionsEvent) -> Unit = {},
    onVideoContentScaleChanged: (VideoContentScale) -> Unit = {},
) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .then(
                if (overlayView != null) {
                    Modifier.noRippleClickable(onClick = onDismiss)
                } else {
                    Modifier
                },
            ),
    )

    AudioTrackSelectorView(
        show = overlayView == OverlayView.AUDIO_SELECTOR,
        player = player,
        onDismiss = onDismiss,
    )

    SubtitleSelectorView(
        show = overlayView == OverlayView.SUBTITLE_SELECTOR,
        player = player,
        onSelectSubtitleClick = onSelectSubtitleClick,
        onEvent = onSubtitleOptionEvent,
        onDismiss = onDismiss,
    )

    VideoContentScaleSelectorView(
        show = overlayView == OverlayView.VIDEO_CONTENT_SCALE,
        videoContentScale = videoContentScale,
        onVideoContentScaleChanged = onVideoContentScaleChanged,
        onDismiss = onDismiss,
    )

    PlaylistView(
        show = overlayView == OverlayView.PLAYLIST,
        player = player,
    )

    DanmakuSettingsSheet(
        show = overlayView == OverlayView.DANMAKU_SETTINGS,
        config = danmakuConfig,
        onConfigChange = onDanmakuConfigChange,
    )
}

val Configuration.isPortrait: Boolean
    get() = orientation == Configuration.ORIENTATION_PORTRAIT

enum class OverlayView {
    AUDIO_SELECTOR,
    SUBTITLE_SELECTOR,
    PLAYBACK_SPEED,
    VIDEO_CONTENT_SCALE,
    PLAYLIST,
    QUALITY_SELECTOR,
    DANMAKU_MANAGER,
    DANMAKU_SETTINGS,
    DANMAKU_SEARCH,
    DANMAKU_SOURCE_SETTINGS,
}
