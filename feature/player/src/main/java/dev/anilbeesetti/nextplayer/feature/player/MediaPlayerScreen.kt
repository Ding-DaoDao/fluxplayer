package dev.anilbeesetti.nextplayer.feature.player

import android.net.Uri
import android.view.TextureView
import android.widget.Toast
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import dev.anilbeesetti.nextplayer.core.model.ControlButtonsPosition
import dev.anilbeesetti.nextplayer.core.model.DanmakuDownloadState
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.core.ui.R as coreUiR
import dev.anilbeesetti.nextplayer.core.ui.extensions.copy
import dev.anilbeesetti.nextplayer.feature.player.danmaku.Danmaku
import dev.anilbeesetti.nextplayer.feature.player.danmaku.DanmakuController
import dev.anilbeesetti.nextplayer.feature.player.danmaku.DanmakuOverlay
import dev.anilbeesetti.nextplayer.feature.player.danmaku.DanmakuSourceSearchSheet
import dev.anilbeesetti.nextplayer.feature.player.danmaku.DanmakuSourceSelectorSheet
import dev.anilbeesetti.nextplayer.feature.player.danmaku.LocalDanmakuFileBrowser
import dev.anilbeesetti.nextplayer.feature.player.buttons.NextButton
import dev.anilbeesetti.nextplayer.feature.player.buttons.PlayPauseButton
import dev.anilbeesetti.nextplayer.feature.player.buttons.PlayerButton
import dev.anilbeesetti.nextplayer.feature.player.buttons.PreviousButton
import dev.anilbeesetti.nextplayer.feature.player.state.ControlsVisibilityState
import dev.anilbeesetti.nextplayer.feature.player.state.IntroOutroState
import dev.anilbeesetti.nextplayer.feature.player.state.IntroOutroTimestamps
import dev.anilbeesetti.nextplayer.feature.player.state.MediaPresentationState
import dev.anilbeesetti.nextplayer.feature.player.state.VerticalGesture
import dev.anilbeesetti.nextplayer.feature.player.state.rememberBrightnessState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberControlsVisibilityState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberErrorState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberIntroOutroState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberMediaPresentationState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberMetadataState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberPictureInPictureState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberRotationState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberSeekGestureState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberTapGestureState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberVideoZoomAndContentScaleState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberVolumeAndBrightnessGestureState
import dev.anilbeesetti.nextplayer.feature.player.state.rememberVolumeState
import dev.anilbeesetti.nextplayer.feature.player.extensions.nameRes
import dev.anilbeesetti.nextplayer.feature.player.extensions.formatted
import dev.anilbeesetti.nextplayer.feature.player.extensions.noRippleClickable
import dev.anilbeesetti.nextplayer.feature.player.state.seekAmountFormatted
import dev.anilbeesetti.nextplayer.feature.player.state.seekToPositionFormated
import dev.anilbeesetti.nextplayer.feature.player.ui.DoubleTapIndicator
import dev.anilbeesetti.nextplayer.feature.player.ui.OverlayShowView
import dev.anilbeesetti.nextplayer.feature.player.ui.OverlayView
import dev.anilbeesetti.nextplayer.feature.player.ui.QualityOption
import dev.anilbeesetti.nextplayer.feature.player.ui.SubtitleConfiguration
import dev.anilbeesetti.nextplayer.feature.player.ui.VerticalProgressView
import dev.anilbeesetti.nextplayer.feature.player.ui.controls.ControlsBottomView
import dev.anilbeesetti.nextplayer.feature.player.ui.controls.ControlsTopView
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

val LocalControlsVisibilityState = compositionLocalOf<ControlsVisibilityState?> { null }

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
        useLongPressGesture = playerPreferences.useLongPressControls,
        longPressSpeed = playerPreferences.longPressControlsSpeed,
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

    // 从 VideoQualityCache 预加载清晰度选项（云盘视频）
    LaunchedEffect(player.currentMediaItem?.mediaId) {
        try {
            val uri = player.currentMediaItem?.localConfiguration?.uri ?: return@LaunchedEffect
            val uriStr = uri.toString()
            // pan123 云盘视频 URL 格式: ...#pan123Play=true#
            if (uriStr.contains("#pan123Play=true#")) {
                val videoQualityCache = dev.anilbeesetti.nextplayer.core.common.VideoQualityCache(context)
                // 提取实际播放 URL（去掉 fragment）
                val playUrl = uriStr.substringBefore("#")
                val cachedOptions = videoQualityCache.getQualityOptionsByUrl("pan123", playUrl)
                if (cachedOptions != null) {
                    qualityOptions = cachedOptions.map { opt ->
                        QualityOption(label = opt.label, uri = android.net.Uri.parse(opt.url))
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // 当前清晰度标签：有 qualityOptions 时取第一个的 label，否则根据视频分辨率计算
    val currentQualityLabel: String = qualityOptions.firstOrNull()?.label
        ?: if (player.videoSize.width > 0 && player.videoSize.height > 0) {
            formatResolution(player.videoSize.width, player.videoSize.height)
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

    var overlayView by remember { mutableStateOf<OverlayView?>(null) }
    var showDanmakuSourceSelector by remember { mutableStateOf(false) }
    var showLocalDanmakuBrowser by remember { mutableStateOf(false) }
    var localBrowserDir by remember { mutableStateOf(File(playerPreferences.localDanmakuPath)) }

    val danmakuSources by viewModel.danmakuSources.collectAsStateWithLifecycle(emptyList())
    val danmakuDownloadState by viewModel.danmakuDownloadState.collectAsStateWithLifecycle(DanmakuDownloadState.Idle)

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
                    onMediaItemTransitioned = { viewModel.clearDanmaku() },
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
                                text = stringResource(coreUiR.string.fast_playback_speed, tapGestureState.longPressSpeed),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }

                if (controlsVisibilityState.controlsVisible && controlsVisibilityState.controlsLocked) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .padding(top = 24.dp),
                    ) {
                        PlayerButton(
                            containerColor = Color.Black.copy(0.5f),
                            onClick = { controlsVisibilityState.unlockControls() }
                        ) {
                            Icon(
                                painter = painterResource(coreUiR.drawable.ic_lock),
                                contentDescription = stringResource(coreUiR.string.controls_unlock),
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
                                        if (danmakuList.isNullOrEmpty()) {
                                            showDanmakuSourceSelector = true
                                        } else {
                                            // 已下载过弹幕，打开搜索时回到剧集列表
                                            if (danmakuDownloadState is DanmakuDownloadState.Ready) {
                                                viewModel.restoreLastSearchState()
                                            }
                                            overlayView = OverlayView.DANMAKU_SEARCH
                                        }
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
                                controlsVisibilityState.controlsVisible -> ControlsMiddleView(player = player)
                                else -> Unit
                            }
                        },
                        bottomView = {
                            AnimatedVisibility(
                                visible = controlsVisibilityState.controlsVisible && !controlsVisibilityState.controlsLocked,
                                enter = fadeIn(),
                                exit = fadeOut(),
                            ) {
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
                                        onPlaybackSpeedClick = {
                                            controlsVisibilityState.hideControls()
                                            overlayView = OverlayView.PLAYBACK_SPEED
                                        },
                                        onPlaylistClick = {
                                            controlsVisibilityState.hideControls()
                                            overlayView = OverlayView.PLAYLIST
                                        },
                                        onRotateClick = rotationState::rotate,
                                        onLockControlsClick = {
                                            controlsVisibilityState.showControls()
                                            controlsVisibilityState.lockControls()
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
                                                Toast.makeText(context, coreUiR.string.enable_pip_from_settings, Toast.LENGTH_SHORT).show()
                                                pictureInPictureState.openPictureInPictureSettings()
                                            } else {
                                                pictureInPictureState.enterPictureInPictureMode()
                                            }
                                        },
                                        qualityOptions = qualityOptions,
                                        currentQualityLabel = currentQualityLabel,
                                        onQualitySelected = { option ->
                                            viewModel.onQualitySelected(option)
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

            // ── 弹幕来源选择 Sheet（无弹幕时弹出）──
            DanmakuSourceSelectorSheet(
                show = showDanmakuSourceSelector,
                onPickLocalFile = {
                    showDanmakuSourceSelector = false
                    showLocalDanmakuBrowser = true
                },
                onSearchOnline = {
                    showDanmakuSourceSelector = false
                    overlayView = OverlayView.DANMAKU_SEARCH
                },
                onDismiss = {
                    showDanmakuSourceSelector = false
                },
            )

            // ── 弹幕在线搜索 Sheet ──
            DanmakuSourceSearchSheet(
                show = overlayView == OverlayView.DANMAKU_SEARCH,
                sources = danmakuSources,
                downloadState = danmakuDownloadState,
                onSearch = { source, keyword ->
                    if (keyword.isNotBlank()) {
                        viewModel.searchDanmaku(source, keyword)
                    } else {
                        // Show search UI for keyword input
                    }
                },
                onSelectAnime = { anime ->
                    viewModel.selectAnime(anime)
                },
                onSelectEpisode = { episode ->
                    viewModel.selectEpisode(context, episode)
                },
                onResetSearch = {
                    viewModel.resetDanmakuSearch()
                },
                onDismiss = {
                    overlayView = null
                    // 不重置搜索状态，保留搜索结果以便切集后复用
                },
            )

            // ── 本地弹幕文件浏览器 ──
            LocalDanmakuFileBrowser(
                show = showLocalDanmakuBrowser,
                currentDir = localBrowserDir,
                startDir = File(playerPreferences.localDanmakuPath),
                onDirChange = { localBrowserDir = it },
                onFileSelected = { file ->
                    showLocalDanmakuBrowser = false
                    localBrowserDir = File(playerPreferences.localDanmakuPath)
                    onDanmakuLocalFileSelected?.invoke(Uri.fromFile(file))
                },
                onDismiss = {
                    showLocalDanmakuBrowser = false
                    localBrowserDir = File(playerPreferences.localDanmakuPath)
                },
            )
        }
    }

    errorState.error?.let { error ->
        // 自动重试一次
        if (!hasAutoRetried) {
            LaunchedEffect(error) {
                hasAutoRetried = true
                player.prepare()
                player.play()
            }
        }
        AlertDialog(
            onDismissRequest = { },
            title = {
                Text(text = stringResource(coreUiR.string.error_playing_video))
            },
            text = {
                Text(text = error.message ?: stringResource(coreUiR.string.unknown_error))
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
 * 片头片尾设置行。
 * 点击设置当前位置为片头/片尾，长按清除。
 */
@Composable
private fun IntroOutroRow(
    introOutroState: IntroOutroState,
    player: Player,
    mediaPresentationState: MediaPresentationState,
) {
    val dirKey = getDirectoryKey(player)
    if (dirKey == null) return
    val timestamps = introOutroState.getTimestamps(dirKey)

    // 片头
    val introLabel = if (timestamps.introMs < 0) {
        "片头"
    } else {
        "片头 ${timestamps.introMs.milliseconds.formatted()}"
    }
    Text(
        text = introLabel,
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .noRippleClickable {
                // 长按清除
            }
            .padding(horizontal = 8.dp, vertical = 12.dp),
    )

    // 片尾
    val outroLabel = if (timestamps.outroMs < 0) {
        "片尾"
    } else {
        "片尾 ${timestamps.outroMs.milliseconds.formatted()}"
    }
    Text(
        text = outroLabel,
        color = Color.White,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .noRippleClickable {
                // 长按清除
            }
            .padding(horizontal = 8.dp, vertical = 12.dp),
    )
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

/**
 * 获取当前播放文件的目录作为片头片尾存储 key。
 */
private fun getDirectoryKey(player: Player): String? {
    // 尝试从媒体描述获取文件路径
    val mediaMetadata = player.currentMediaItem?.mediaMetadata
    val filePath = mediaMetadata?.description?.toString()
    if (filePath != null) {
        val parent = File(filePath).parent
        if (parent != null) return parent
    }
    // 回退到 mediaId
    return player.currentMediaItem?.mediaId
}
