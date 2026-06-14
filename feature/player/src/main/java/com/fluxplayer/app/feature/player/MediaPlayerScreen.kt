package com.fluxplayer.app.feature.player

import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.fluxplayer.app.core.model.FluxMessageEvent
import com.fluxplayer.app.core.ui.components.FluxNotificationBanner
import com.fluxplayer.app.core.ui.components.FluxNotificationState
import com.fluxplayer.app.core.ui.components.NextDialog
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.fluxplayer.app.core.model.ControlButtonsPosition
import com.fluxplayer.app.core.model.DanmakuDownloadState
import com.fluxplayer.app.core.model.PlayerPreferences
import com.fluxplayer.app.core.ui.R as coreUiR
import com.fluxplayer.app.core.ui.extensions.copy
import com.fluxplayer.app.feature.player.danmaku.Danmaku
import com.fluxplayer.app.feature.player.danmaku.DanmakuController
import com.fluxplayer.app.feature.player.danmaku.DanmakuOverlay
import com.fluxplayer.app.feature.player.danmaku.DanmakuSearchSheet
import com.fluxplayer.app.feature.player.buttons.NextButton
import com.fluxplayer.app.feature.player.buttons.PlayPauseButton
import com.fluxplayer.app.feature.player.buttons.PlayerButton
import com.fluxplayer.app.feature.player.buttons.PreviousButton
import com.fluxplayer.app.feature.player.state.ControlsVisibilityState
import com.fluxplayer.app.feature.player.state.IntroOutroState
import com.fluxplayer.app.feature.player.state.IntroOutroTimestamps
import com.fluxplayer.app.feature.player.state.MediaPresentationState
import com.fluxplayer.app.feature.player.state.VerticalGesture
import com.fluxplayer.app.feature.player.state.rememberBrightnessState
import com.fluxplayer.app.feature.player.state.rememberControlsVisibilityState
import com.fluxplayer.app.feature.player.state.rememberErrorState
import com.fluxplayer.app.feature.player.state.rememberIntroOutroState
import com.fluxplayer.app.feature.player.state.rememberMediaPresentationState
import com.fluxplayer.app.feature.player.state.rememberMetadataState
import com.fluxplayer.app.feature.player.state.rememberPictureInPictureState
import com.fluxplayer.app.feature.player.state.rememberRotationState
import com.fluxplayer.app.feature.player.state.rememberSeekGestureState
import com.fluxplayer.app.feature.player.state.rememberTapGestureState
import com.fluxplayer.app.feature.player.state.rememberVideoZoomAndContentScaleState
import com.fluxplayer.app.feature.player.state.rememberVolumeAndBrightnessGestureState
import com.fluxplayer.app.feature.player.state.rememberVolumeState
import com.fluxplayer.app.feature.player.extensions.nameRes
import com.fluxplayer.app.feature.player.extensions.formatted
import com.fluxplayer.app.feature.player.extensions.noRippleClickable
import com.fluxplayer.app.feature.player.state.seekAmountFormatted
import com.fluxplayer.app.feature.player.state.seekToPositionFormated
import com.fluxplayer.app.feature.player.ui.DoubleTapIndicator
import com.fluxplayer.app.feature.player.ui.OverlayShowView
import com.fluxplayer.app.feature.player.ui.OverlayView
import com.fluxplayer.app.feature.player.ui.QualityOption
import com.fluxplayer.app.feature.player.ui.SubtitleConfiguration
import com.fluxplayer.app.feature.player.ui.VerticalProgressView
import com.fluxplayer.app.feature.player.ui.controls.ControlsBottomView
import com.fluxplayer.app.feature.player.ui.controls.ControlsTopView
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.DurationUnit

val LocalControlsVisibilityState = compositionLocalOf<ControlsVisibilityState?> { null }

private const val INTRO_OUTRO_KEY = "current_session"

@OptIn(UnstableApi::class)
@Composable
fun MediaPlayerScreen(
    player: Player?,
    viewModel: PlayerViewModel,
    playerPreferences: PlayerPreferences,
    danmakuList: List<Danmaku>?,
    danmakuFileUri: Uri?,
    danmakuEnabled: Boolean,
    danmakuForCurrentEpisode: Boolean = false,
    modifier: Modifier = Modifier,
    onTextureView: ((TextureView?) -> Unit)? = null,
    onSelectSubtitleClick: () -> Unit,
    onDanmakuPickFile: () -> Unit,
    onDanmakuLocalFileSelected: ((Uri) -> Unit)? = null,
    onBackClick: () -> Unit,
) {
    val context = LocalContext.current
    val vibrator = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(VibratorManager::class.java)
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java) as? Vibrator
        }
    }
    val notificationState = remember { FluxNotificationState() }
    LaunchedEffect(Unit) {
        viewModel.messageEvents.collect { notificationState.show(it) }
    }
    val volumeState = rememberVolumeState(
        player = player,
        showVolumePanelIfHeadsetIsOn = playerPreferences.showSystemVolumePanel,
    )
    player ?: return
    val metadataState = rememberMetadataState(player)
    val mediaPresentationState = rememberMediaPresentationState(player)
    val controlsVisibilityState = rememberControlsVisibilityState(
        player = player,
        hideAfter = playerPreferences.controllerAutoHideTimeout.seconds,
    )
    val tapGestureState = rememberTapGestureState(
        player = player,
        doubleTapGesture = playerPreferences.doubleTapGesture,
        seekIncrementMillis = playerPreferences.seekIncrement.seconds.inWholeMilliseconds,
        longPressSpeed = playerPreferences.longPressControlsSpeed,
        useDynamicLongPressSpeed = playerPreferences.useDynamicLongPressSpeed,
        dynamicLongPressMultiplier = playerPreferences.dynamicLongPressMultiplier,
    )
    val seekGestureState = rememberSeekGestureState(
        player = player,
        sensitivity = playerPreferences.seekSensitivity,
        enableSeekGesture = playerPreferences.useSeekControls,
    )
    val pictureInPictureState = rememberPictureInPictureState(
        player = player,
        autoEnter = playerPreferences.autoPip,
    )
    val videoZoomAndContentScaleState = rememberVideoZoomAndContentScaleState(
        player = player,
        initialContentScale = playerPreferences.playerVideoZoom,
        enableZoomGesture = playerPreferences.useZoomControls,
        enablePanGesture = playerPreferences.enablePanGesture,
        onEvent = viewModel::onVideoZoomEvent,
    )
    val brightnessState = rememberBrightnessState()
    val volumeAndBrightnessGestureState = rememberVolumeAndBrightnessGestureState(
        volumeState = volumeState,
        brightnessState = brightnessState,
        enableVolumeGesture = playerPreferences.enableVolumeSwipeGesture,
        enableBrightnessGesture = playerPreferences.enableBrightnessSwipeGesture,
        volumeGestureSensitivity = playerPreferences.volumeGestureSensitivity,
        brightnessGestureSensitivity = playerPreferences.brightnessGestureSensitivity,
    )
    val rotationState = rememberRotationState(
        player = player,
        screenOrientation = playerPreferences.playerScreenOrientation,
    )
    val errorState = rememberErrorState(player = player)

    // Intro/Outro 状态
    val introOutroState = rememberIntroOutroState()

    // 自动重试
    var hasAutoRetried by remember { mutableStateOf(false) }

    // 清晰度选项和标签
    var qualityOptions by remember { mutableStateOf<List<QualityOption>>(emptyList()) }
    var videoResolution by remember { mutableStateOf(Pair(0, 0)) }
    var selectedQualityLabel by remember { mutableStateOf<String?>(null) }
    var isSwitchingQuality by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // 当前播放速度追踪
    var currentSpeed by remember { mutableStateOf(player.playbackParameters.speed) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                currentSpeed = playbackParameters.speed
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 从 VideoQualityCache 预加载清晰度选项（云盘视频）
    LaunchedEffect(player.currentMediaItem?.mediaId) {
        try {
            val uri = player.currentMediaItem?.localConfiguration?.uri ?: return@LaunchedEffect
            val uriStr = uri.toString()
            val playUrl = uriStr.substringBefore("#")
            val videoQualityCache = com.fluxplayer.app.core.common.VideoQualityCache(context.applicationContext)
            var cachedOptions = when {
                uriStr.contains("#pan123Play=true#") ->
                    videoQualityCache.getQualityOptionsByUrl("pan123", playUrl)
                uriStr.contains("#ucPlay=true#") ->
                    videoQualityCache.getQualityOptionsByUrl("uc", playUrl)
                uriStr.contains("#quarkPlay=true#") ->
                    videoQualityCache.getQualityOptionsByUrl("quark", playUrl)
                uriStr.contains("#alipanPlay=true#") ->
                    videoQualityCache.getQualityOptionsByUrl("alipan", playUrl)
                else -> null
            }
            // 反向索引查找失败时，直接扫描 SharedPreferences 匹配 URL
            if (cachedOptions == null) {
                val provider = when {
                    uriStr.contains("#pan123Play=true#") -> "pan123"
                    uriStr.contains("#ucPlay=true#") -> "uc"
                    uriStr.contains("#quarkPlay=true#") -> "quark"
                    uriStr.contains("#alipanPlay=true#") -> "alipan"
                    else -> null
                }
                if (provider != null) {
                    val prefs = context.applicationContext.getSharedPreferences("video_quality_cache", android.content.Context.MODE_PRIVATE)
                    cachedOptions = prefs.all.entries
                        .filter { it.key.startsWith("${provider}_") && !it.key.startsWith("url_index_") }
                        .firstNotNullOfOrNull { (_, value) ->
                            try {
                                val json = org.json.JSONObject(value as String)
                                val arr = json.getJSONArray("options")
                                val found = (0 until arr.length()).any { i ->
                                    arr.getJSONObject(i).getString("url") == playUrl
                                }
                                if (found) {
                                    (0 until arr.length()).map { i ->
                                        val opt = arr.getJSONObject(i)
                                        com.fluxplayer.app.core.common.VideoQualityCache.QualityOption(
                                            label = opt.getString("label"),
                                            url = opt.getString("url")
                                        )
                                    }
                                } else null
                            } catch (_: Exception) { null }
                        }
                }
            }
            if (cachedOptions != null) {
                qualityOptions = cachedOptions.map { opt ->
                    QualityOption(label = opt.label, uri = android.net.Uri.parse(opt.url))
                }
            }
        } catch (_: Exception) {}
    }

    // 监听视频尺寸变化，确保分辨率信息实时更新
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                videoResolution = Pair(videoSize.width, videoSize.height)
            }
        }
        // 同步当前已解码的视频尺寸
        val initialSize = player.videoSize
        if (initialSize.width > 0 && initialSize.height > 0) {
            videoResolution = Pair(initialSize.width, initialSize.height)
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 当前清晰度标签：优先 qualityOptions，其次分辨率兜底
    val currentQualityLabel: String = selectedQualityLabel
        ?: qualityOptions.firstOrNull()?.label
        ?: if (videoResolution.first > 0 && videoResolution.second > 0) {
            formatResolution(videoResolution.first, videoResolution.second)
        } else ""

    // 顶部像素分辨率行：格式 "1920x1080"
    val videoInfoLine = if (videoResolution.first > 0 && videoResolution.second > 0) {
        "${videoResolution.first}x${videoResolution.second}"
    } else ""

    // DanmakuController 引用
    var danmakuController by remember { mutableStateOf<DanmakuController?>(null) }

    LaunchedEffect(pictureInPictureState.isInPictureInPictureMode) {
        if (pictureInPictureState.isInPictureInPictureMode) {
            controlsVisibilityState.hideControls()
        }
    }

    LaunchedEffect(tapGestureState.isLongPressGestureInAction) {
        if (tapGestureState.isLongPressGestureInAction) {
            if (playerPreferences.hapticFeedbackStrength > 0f) {
                val amplitude = (playerPreferences.hapticFeedbackStrength * 255).toInt().coerceIn(1, 255)
                vibrator?.vibrate(VibrationEffect.createOneShot(50, amplitude))
            }
            controlsVisibilityState.hideControls()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (playerPreferences.rememberPlayerBrightness) {
            brightnessState.setBrightness(playerPreferences.playerBrightness)
        }
    }

    LaunchedEffect(brightnessState.currentBrightness) {
        if (playerPreferences.rememberPlayerBrightness) {
            viewModel.updatePlayerBrightness(brightnessState.currentBrightness)
        }
    }

    // 剧集切换时自动 seek 到片头（使用 Player.Listener 直接监听，不依赖 Compose State）
    var isFirstItem by remember { mutableStateOf(true) }
    var skipNextTransitionSeek by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (skipNextTransitionSeek) {
                    skipNextTransitionSeek = false
                    return
                }
                if (isFirstItem) {
                    isFirstItem = false
                    return
                }
                val ts = introOutroState.getTimestamps(INTRO_OUTRO_KEY)
                if (ts.introMs > 0) {
                    player.seekTo(ts.introMs)
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 播放过程中检测是否到达片尾，自动跳过（保留设置跨剧集生效）
    var lastSkippedOutroIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(Unit) {
        while (isActive) {
            delay(500)
            val ts = introOutroState.getTimestamps(INTRO_OUTRO_KEY)
            val currentIndex = player.currentMediaItemIndex
            if (ts.outroMs > 0 && currentIndex != lastSkippedOutroIndex
                && player.isPlaying && player.currentPosition >= ts.outroMs
            ) {
                lastSkippedOutroIndex = currentIndex
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                }
            }
        }
    }

    var overlayView by remember { mutableStateOf<OverlayView?>(null) }

    val danmakuSources by viewModel.danmakuSources.collectAsStateWithLifecycle(emptyList())
    val danmakuDownloadState by viewModel.danmakuDownloadState.collectAsStateWithLifecycle(DanmakuDownloadState.Idle)
    val danmakuSearchViewMode by viewModel.danmakuSearchViewMode.collectAsStateWithLifecycle()
    val danmakuSearchKeyword by viewModel.danmakuSearchKeyword.collectAsStateWithLifecycle()
    val danmakuLocalBrowserDir by viewModel.danmakuLocalBrowserDir.collectAsStateWithLifecycle()

    // 下载成功后自动关闭搜索 Sheet
    LaunchedEffect(danmakuDownloadState) {
        if (danmakuDownloadState is DanmakuDownloadState.Ready) {
            overlayView = null
        }
    }

    CompositionLocalProvider(LocalControlsVisibilityState provides controlsVisibilityState) {
        Box {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                PlayerContentFrame(
                    player = player,
                    pictureInPictureState = pictureInPictureState,
                    controlsVisibilityState = controlsVisibilityState,
                    tapGestureState = tapGestureState,
                    seekGestureState = seekGestureState,
                    videoZoomAndContentScaleState = videoZoomAndContentScaleState,
                    volumeAndBrightnessGestureState = volumeAndBrightnessGestureState,
                    subtitleConfiguration = SubtitleConfiguration(
                        useSystemCaptionStyle = playerPreferences.useSystemCaptionStyle,
                        showBackground = playerPreferences.subtitleBackground,
                        font = playerPreferences.subtitleFont,
                        textSize = playerPreferences.subtitleTextSize,
                        textBold = playerPreferences.subtitleTextBold,
                        applyEmbeddedStyles = playerPreferences.applyEmbeddedStyles,
                    ),
                    onTextureView = onTextureView,
                )

                // ── 弹幕叠加层 ──
                DanmakuOverlay(
                        player = player,
                        danmakuList = danmakuList,
                        config = playerPreferences.danmakuConfig,
                        enabled = danmakuEnabled,
                        modifier = Modifier.fillMaxSize(),
                        onController = { ctrl ->
                            danmakuController = ctrl
                        },
                        onMediaItemTransitioned = { indexStep -> viewModel.onMediaItemTransition(indexStep, context) },
                    )

                AnimatedVisibility(
                    visible = controlsVisibilityState.controlsVisible && !controlsVisibilityState.controlsLocked,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Box(
                        modifier = modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.3f)),
                    )
                }

                if (mediaPresentationState.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(72.dp),
                    )
                }

                DoubleTapIndicator(tapGestureState = tapGestureState)

                AnimatedVisibility(
                    modifier = Modifier
                        .padding(top = 24.dp)
                        .align(Alignment.TopCenter),
                    visible = tapGestureState.isLongPressGestureInAction,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Surface(shape = CircleShape) {
                        Row(
                            modifier = Modifier.padding(
                                horizontal = 16.dp,
                                vertical = 8.dp,
                            ),
                        ) {
                            Text(
                                text = stringResource(coreUiR.string.fast_playback_speed, player.playbackParameters.speed),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }

                if (controlsVisibilityState.controlsVisible && controlsVisibilityState.controlsLocked) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        PlayerButton(
                            modifier = Modifier
                                .padding(start = 20.dp)
                                .size(48.dp),
                            containerColor = Color.Black.copy(0.5f),
                            onClick = { controlsVisibilityState.unlockControls() },
                        ) {
                            Icon(
                                painter = painterResource(coreUiR.drawable.ic_lock),
                                contentDescription = stringResource(coreUiR.string.controls_unlock),
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                } else {
                    PlayerControlsView(
                        topView = {
                            AnimatedVisibility(
                                visible = controlsVisibilityState.controlsVisible,
                                enter = fadeIn(),
                                exit = fadeOut(),
                            ) {
                                ControlsTopView(
                                    title = metadataState.title ?: "",
                                    videoInfoLine = videoInfoLine,
                                    danmakuEnabled = danmakuEnabled,
                                    danmakuHasData = danmakuList != null && danmakuForCurrentEpisode,
                                    onAudioClick = {
                                        controlsVisibilityState.hideControls()
                                        overlayView = OverlayView.AUDIO_SELECTOR
                                    },
                                    onSubtitleClick = {
                                        controlsVisibilityState.hideControls()
                                        overlayView = OverlayView.SUBTITLE_SELECTOR
                                    },

                                    onDanmakuToggleClick = {
                                        controlsVisibilityState.hideControls()
                                        viewModel.toggleDanmaku()
                                    },
                                    onDanmakuSearchClick = {
                                        controlsVisibilityState.hideControls()
                                        // 已下载过弹幕，打开搜索时回到剧集列表
                                        if (danmakuDownloadState is DanmakuDownloadState.Ready) {
                                            viewModel.restoreLastSearchState()
                                        }
                                        overlayView = OverlayView.DANMAKU_SEARCH
                                    },
                                    onDanmakuSettingsClick = {
                                        controlsVisibilityState.hideControls()
                                        overlayView = OverlayView.DANMAKU_SETTINGS
                                    },

                                    onBackClick = onBackClick,
                                )
                            }
                        },
                        middleView = {
                            when {
                                seekGestureState.seekAmount != null -> InfoView(info = "${seekGestureState.seekAmountFormatted}\n[${seekGestureState.seekToPositionFormated}]")
                                videoZoomAndContentScaleState.isZooming -> InfoView(info = "${(videoZoomAndContentScaleState.zoom * 100).toInt()}%")
                                videoZoomAndContentScaleState.showContentScaleIndicator -> InfoView(info = stringResource(videoZoomAndContentScaleState.videoContentScale.nameRes()))
                                controlsVisibilityState.controlsVisible -> {
                                    ControlsMiddleView(player = player)
                                    // 锁按钮 - 屏幕中间左侧
                                    PlayerButton(
                                        modifier = Modifier
                                            .align(Alignment.CenterStart)
                                            .padding(start = 20.dp)
                                            .size(48.dp),
                                        containerColor = Color.Black.copy(0.5f),
                                        onClick = {
                                            controlsVisibilityState.showControls()
                                            controlsVisibilityState.lockControls()
                                        },
                                    ) {
                                        Icon(
                                            painter = painterResource(coreUiR.drawable.ic_lock_open),
                                            contentDescription = stringResource(coreUiR.string.controls_lock),
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                    // 旋转按钮 - 屏幕中间右侧
                                    PlayerButton(
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .padding(end = 20.dp)
                                            .size(48.dp),
                                        containerColor = Color.Black.copy(0.5f),
                                        onClick = rotationState::rotate,
                                    ) {
                                        Icon(
                                            painter = painterResource(coreUiR.drawable.ic_screen_rotation),
                                            contentDescription = null,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                                else -> Unit
                            }
                        },
                        bottomView = {
                            AnimatedVisibility(
                                visible = controlsVisibilityState.controlsVisible && !controlsVisibilityState.controlsLocked,
                                enter = fadeIn(),
                                exit = fadeOut(),
                            ) {
                                val timestamps = introOutroState.getTimestamps(INTRO_OUTRO_KEY)
                                val introLabel = if (timestamps.introMs < 0) "片头"
                                    else "片头 ${timestamps.introMs.milliseconds.formatted()}"
                                val outroLabel = if (timestamps.outroMs < 0) "片尾"
                                    else "片尾 ${timestamps.outroMs.milliseconds.formatted()}"

                                Column {
                                    ControlsBottomView(
                                        player = player,
                                        mediaPresentationState = mediaPresentationState,
                                        controlsAlignment = when (playerPreferences.controlButtonsPosition) {
                                            ControlButtonsPosition.LEFT -> Alignment.Start
                                            ControlButtonsPosition.RIGHT -> Alignment.End
                                        },
                                        videoContentScale = videoZoomAndContentScaleState.videoContentScale,
                                        isPipSupported = pictureInPictureState.isPipSupported,
                                        onSeek = seekGestureState::onSeek,
                                        onSeekEnd = seekGestureState::onSeekEnd,
                                        onPlaylistClick = {
                                            controlsVisibilityState.hideControls()
                                            overlayView = OverlayView.PLAYLIST
                                        },
                                        onVideoContentScaleClick = {
                                            controlsVisibilityState.showControls()
                                            videoZoomAndContentScaleState.switchToNextVideoContentScale()
                                        },
                                        onVideoContentScaleLongClick = {
                                            controlsVisibilityState.hideControls()
                                            overlayView = OverlayView.VIDEO_CONTENT_SCALE
                                        },
                                        onPictureInPictureClick = {
                                            if (!pictureInPictureState.hasPipPermission) {
                                                notificationState.show(
                                                    FluxMessageEvent.Info(context.getString(coreUiR.string.enable_pip_from_settings)),
                                                )
                                                pictureInPictureState.openPictureInPictureSettings()
                                            } else {
                                                pictureInPictureState.enterPictureInPictureMode()
                                            }
                                        },
                                        qualityOptions = qualityOptions,
                                        currentQualityLabel = currentQualityLabel,
                                        onQualitySelected = { option ->
                                            val currentItem = player.currentMediaItem ?: return@ControlsBottomView
                                            val currentIndex = player.currentMediaItemIndex
                                            val currentPosition = player.currentPosition
                                            val playWhenReady = player.playWhenReady
                                            val previousLabel = selectedQualityLabel
                                            isSwitchingQuality = true
                                            errorState.dismiss()
                                            selectedQualityLabel = option.label
                                            skipNextTransitionSeek = true
                                            val originalFragment = currentItem.localConfiguration?.uri?.fragment
                                            val newUri = if (!originalFragment.isNullOrEmpty()) {
                                                android.net.Uri.parse("${option.uri}#${originalFragment}")
                                            } else {
                                                option.uri
                                            }
                                            Log.d("FluxQuality", "=== 切换清晰度 ===")
                                            Log.d("FluxQuality", "原始 URL: ${currentItem.localConfiguration?.uri}")
                                            Log.d("FluxQuality", "原始 fragment: $originalFragment")
                                            Log.d("FluxQuality", "选择清晰度: ${option.label}")
                                            Log.d("FluxQuality", "质量 URL: ${option.uri}")
                                            Log.d("FluxQuality", "新 URL: $newUri")
                                            Log.d("FluxQuality", "播放位置: $currentPosition, playWhenReady: $playWhenReady")
                                            val newMediaItem = currentItem.buildUpon()
                                                .setUri(newUri)
                                                .build()
                                            val totalItems = player.mediaItemCount
                                            val mediaItems = if (totalItems > 1) {
                                                (0 until totalItems).map { i ->
                                                    if (i == currentIndex) newMediaItem else player.getMediaItemAt(i)
                                                }
                                            } else {
                                                listOf(newMediaItem)
                                            }
                                            player.setMediaItems(mediaItems, currentIndex, currentPosition)
                                            player.playWhenReady = playWhenReady
                                            // 切换失败时静默回退到原清晰度
                                            coroutineScope.launch {
                                                delay(5000)
                                                val error = player.playerError
                                                Log.d("FluxQuality", "5s 后检查: playerError=$error")
                                                if (error != null) {
                                                    Log.e("FluxQuality", "切换失败，回退到 $previousLabel, error=${error.message}")
                                                    selectedQualityLabel = previousLabel
                                                    val revertItems = (0 until player.mediaItemCount).map { i ->
                                                        if (i == currentIndex) currentItem else player.getMediaItemAt(i)
                                                    }
                                                    player.setMediaItems(revertItems, currentIndex, currentPosition)
                                                    player.playWhenReady = true
                                                } else {
                                                    Log.d("FluxQuality", "切换成功")
                                                }
                                                isSwitchingQuality = false
                                                errorState.dismiss()
                                            }
                                        },
                                        introLabel = introLabel,
                                        onIntroClick = { introOutroState.setIntro(INTRO_OUTRO_KEY, player.currentPosition) },
                                        onIntroLongClick = { introOutroState.setIntro(INTRO_OUTRO_KEY, -1L) },
                                        outroLabel = outroLabel,
                                        onOutroClick = { introOutroState.setOutro(INTRO_OUTRO_KEY, player.currentPosition) },
                                        onOutroLongClick = { introOutroState.setOutro(INTRO_OUTRO_KEY, -1L) },
                                        currentSpeed = currentSpeed,
                                        onSpeedSelected = { speed ->
                                            player.setPlaybackSpeed(speed)
                                        },
                                        onSpeedMenuOpenChanged = { isOpen ->
                                            if (isOpen) {
                                                controlsVisibilityState.suppressAutoHide = true
                                                controlsVisibilityState.showControls()
                                            } else {
                                                controlsVisibilityState.suppressAutoHide = false
                                            }
                                        },
                                    )
                                }
                            }
                        },
                    )
                }

                val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .displayCutoutPadding()
                        .padding(systemBarsPadding.copy(top = 0.dp, bottom = 0.dp))
                        .padding(24.dp),
                ) {
                    AnimatedVisibility(
                        modifier = Modifier.align(Alignment.CenterStart),
                        visible = volumeAndBrightnessGestureState.activeGesture == VerticalGesture.VOLUME,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        VerticalProgressView(
                            value = volumeState.volumePercentage,
                            maxValue = volumeState.maxVolumePercentage,
                            icon = painterResource(coreUiR.drawable.ic_volume),
                        )
                    }

                    AnimatedVisibility(
                        modifier = Modifier.align(Alignment.CenterEnd),
                        visible = volumeAndBrightnessGestureState.activeGesture == VerticalGesture.BRIGHTNESS,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        VerticalProgressView(
                            value = brightnessState.brightnessPercentage,
                            icon = painterResource(coreUiR.drawable.ic_brightness),
                        )
                    }
                }
            }

            OverlayShowView(
                player = player,
                overlayView = overlayView,
                videoContentScale = videoZoomAndContentScaleState.videoContentScale,
                danmakuConfig = playerPreferences.danmakuConfig,
                onDanmakuConfigChange = { viewModel.updateDanmakuConfig(it) },
                onDismiss = { overlayView = null },
                onSelectSubtitleClick = onSelectSubtitleClick,
                onSubtitleOptionEvent = viewModel::onSubtitleOptionEvent,
                onVideoContentScaleChanged = { videoZoomAndContentScaleState.onVideoContentScaleChanged(it) },
            )

            // ── 弹幕搜索弹窗（整合搜索 + 本地文件） ──
            DanmakuSearchSheet(
                show = overlayView == OverlayView.DANMAKU_SEARCH,
                sources = danmakuSources,
                downloadState = danmakuDownloadState,
                currentViewMode = danmakuSearchViewMode,
                onViewModeChange = { viewModel.setDanmakuSearchViewMode(it) },
                currentKeyword = danmakuSearchKeyword,
                onKeywordChange = { viewModel.setDanmakuSearchKeyword(it) },
                currentLocalDir = File(danmakuLocalBrowserDir ?: playerPreferences.localDanmakuPath),
                onLocalDirChange = { viewModel.setDanmakuLocalBrowserDir(it.absolutePath) },
                onSearch = { source, keyword ->
                    if (keyword.isNotBlank()) {
                        viewModel.searchDanmaku(source, keyword)
                    }
                },
                onSelectAnime = { viewModel.selectAnime(it) },
                onSelectEpisode = { viewModel.selectEpisode(context, it) },
                onResetSearch = { viewModel.resetDanmakuSearch() },
                onNavigateBack = { viewModel.navigateDanmakuBack() },
                onFileSelected = { file ->
                    onDanmakuLocalFileSelected?.invoke(Uri.fromFile(file))
                },
                onDismiss = { overlayView = null },
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            FluxNotificationBanner(
                event = notificationState.currentEvent,
                onDismiss = { notificationState.dismiss() },
                modifier = Modifier,
            )
        }
    }

    errorState.error?.let { error ->
        // 正在切换清晰度时不弹出错误对话框，由 LaunchedEffect 静默回退
        if (isSwitchingQuality) return@let
        // 自动重试一次
        if (!hasAutoRetried) {
            LaunchedEffect(error) {
                hasAutoRetried = true
                player.prepare()
                player.play()
            }
        }
        NextDialog(
            onDismissRequest = { },
            title = {
                Text(text = stringResource(coreUiR.string.error_playing_video))
            },
            confirmButton = {
                if (player.hasNextMediaItem()) {
                    TextButton(
                        onClick = {
                            errorState.dismiss()
                            player.seekToNext()
                            player.play()
                        },
                    ) {
                        Text(text = stringResource(coreUiR.string.play_next_video))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        errorState.dismiss()
                        onBackClick()
                    },
                ) {
                    Text(text = stringResource(coreUiR.string.exit))
                }
            },
            content = {
                Text(text = error.message ?: stringResource(coreUiR.string.unknown_error))
            },
        )
    }

    BackHandler {
        if (overlayView != null) {
            overlayView = null
        } else {
            onBackClick()
        }
    }
}

@Composable
fun InfoView(
    modifier: Modifier = Modifier,
    info: String,
    textStyle: TextStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = info,
            style = textStyle,
            color = Color.White,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ControlsMiddleView(modifier: Modifier = Modifier, player: Player) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(40.dp, alignment = Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviousButton(player = player)
        PlayPauseButton(player = player)
        NextButton(player = player)
    }
}

@Composable
fun PlayerControlsView(
    modifier: Modifier = Modifier,
    topView: @Composable () -> Unit,
    middleView: @Composable BoxScope.() -> Unit,
    bottomView: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column {
            topView()
            Spacer(modifier = Modifier.weight(1f))
            bottomView()
        }

        middleView()
    }
}

/**
 * 格式化视频分辨率显示。
 */
private fun formatResolution(width: Int, height: Int): String {
    return when {
        width >= 3840 || height >= 2160 -> "4K"
        width >= 2560 || height >= 1440 -> "1440p"
        width >= 1920 || height >= 1080 -> "1080p"
        width >= 1280 || height >= 720 -> "720p"
        width >= 854 || height >= 480 -> "480p"
        width >= 640 || height >= 360 -> "360p"
        else -> "${width}×${height}"
    }
}


