package com.fluxplayer.app.core.model

import kotlinx.serialization.Serializable

@Serializable
data class PlayerPreferences(
    val resume: Resume = Resume.YES,
    val rememberPlayerBrightness: Boolean = false,
    val playerBrightness: Float = 0.5f,
    val minDurationForFastSeek: Long = 120000L,
    val rememberSelections: Boolean = true,
    val playerScreenOrientation: ScreenOrientation = ScreenOrientation.VIDEO_ORIENTATION,
    val playerVideoZoom: VideoContentScale = VideoContentScale.BEST_FIT,
    val defaultPlaybackSpeed: Float = 1.0f,
    val autoplay: Boolean = true,
    val autoPip: Boolean = true,
    val autoBackgroundPlay: Boolean = false,
    val loopMode: LoopMode = LoopMode.OFF,

    // Controls (Gestures)
    @Deprecated(message = "Use individual enableVolumeSwipeGesture and enableBrightnessSwipeGesture instead")
    val useSwipeControls: Boolean = true,
    val enableVolumeSwipeGesture: Boolean = true,
    val enableBrightnessSwipeGesture: Boolean = true,
    val useSeekControls: Boolean = true,
    val useZoomControls: Boolean = true,
    val enablePanGesture: Boolean = false,
    val doubleTapGesture: DoubleTapGesture = DoubleTapGesture.BOTH,
    val useLongPressControls: Boolean = true,
    val longPressControlsSpeed: Float = 2.0f,
    val seekIncrement: Int = DEFAULT_SEEK_INCREMENT,
    val seekSensitivity: Float = DEFAULT_SEEK_SENSITIVITY,
    val volumeGestureSensitivity: Float = DEFAULT_VOLUME_GESTURE_SENSITIVITY,
    val brightnessGestureSensitivity: Float = DEFAULT_BRIGHTNESS_GESTURE_SENSITIVITY,
    val hapticFeedbackStrength: Float = DEFAULT_HAPTIC_STRENGTH,

    // Player Interface
    val controllerAutoHideTimeout: Int = DEFAULT_CONTROLLER_AUTO_HIDE_TIMEOUT,
    val controlButtonsPosition: ControlButtonsPosition = ControlButtonsPosition.LEFT,
    val hidePlayerButtonsBackground: Boolean = false,
    val useMaterialYouControls: Boolean = false,

    // Audio Preferences
    val preferredAudioLanguage: String = "",
    val pauseOnHeadsetDisconnect: Boolean = true,
    val requireAudioFocus: Boolean = true,
    val showSystemVolumePanel: Boolean = true,
    val enableVolumeBoost: Boolean = false,

    // Subtitle Preferences
    val useSystemCaptionStyle: Boolean = false,
    val preferredSubtitleLanguage: String = "",
    val subtitleTextEncoding: String = "",
    val subtitleTextSize: Int = DEFAULT_SUBTITLE_TEXT_SIZE,
    val subtitleBackground: Boolean = false,
    val subtitleFont: Font = Font.DEFAULT,
    val subtitleTextBold: Boolean = true,
    val applyEmbeddedStyles: Boolean = true,

    // Decoder Preferences
    val decoderPriority: DecoderPriority = DecoderPriority.PREFER_DEVICE,

    // Danmaku Preferences
    val danmakuConfig: DanmakuConfig = DanmakuConfig(),
    val danmakuSources: List<DanmakuSource> = listOf(
        DanmakuSource.DANDANPLAY,
        DanmakuSource.BILIBILI,
        DanmakuSource.TENCENT,
        DanmakuSource.MGTV,
        DanmakuSource.YOUKU,
        DanmakuSource.QIYI,
    ),
    /** 已缓存的弹幕映射 episodeId -> 本地文件路径 */
    val danmakuCacheMap: Map<String, String> = emptyMap(),

    // 本地弹幕路径
    val localDanmakuPath: String = "/storage/emulated/0/Video",

    // 播放缓存
    val playbackCacheEnabled: Boolean = false,
    val playbackCacheMaxSize: CacheMaxSize = CacheMaxSize.GB_5,

    // 动态长按速度
    val useDynamicLongPressSpeed: Boolean = false,
    val dynamicLongPressMultiplier: Float = 2.0f,
) {

    companion object {
        const val DEFAULT_SEEK_INCREMENT = 10
        const val DEFAULT_SEEK_SENSITIVITY = 0.50f
        const val DEFAULT_VOLUME_GESTURE_SENSITIVITY = 0.50f
        const val DEFAULT_BRIGHTNESS_GESTURE_SENSITIVITY = 0.50f
        const val DEFAULT_HAPTIC_STRENGTH = 0.8f
        const val DEFAULT_SUBTITLE_TEXT_SIZE = 20
        const val DEFAULT_CONTROLLER_AUTO_HIDE_TIMEOUT = 4
    }
}
