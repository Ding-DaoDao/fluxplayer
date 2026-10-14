package com.fluxplayer.app.feature.player

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.fluxplayer.app.core.ui.R as coreUiR
import com.fluxplayer.app.core.ui.cache.rememberBookCoverImageLoader
import com.fluxplayer.app.core.ui.components.ChapterDragScrollbar
import com.fluxplayer.app.core.ui.components.FluxNotificationBanner
import com.fluxplayer.app.core.ui.components.FluxNotificationState
import com.fluxplayer.app.core.ui.components.MediaLoadingIndicator
import com.fluxplayer.app.core.ui.theme.FluxTheme
import com.fluxplayer.app.feature.player.service.AudioSleepTimer
import com.fluxplayer.app.feature.player.state.rememberMediaPresentationState
import com.fluxplayer.app.feature.player.state.rememberMetadataState
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// region ── 播放器主题颜色（如 FluxTheme.colorScheme 获取，自动适配 MD3 / MIUIX 双引擎） ──
// 播放器始终使用暗色模式（NextPlayerTheme(darkTheme = true)），但具体暗色值由引擎决定

/** 播放器背景色 如 从主题引擎获取暗如 background */
@Composable
private fun playerBg() = FluxTheme.colorScheme.background

/** 主文字色（暗色背景上的白�?/浅色文字如 */
@Composable
private fun playerOnSurface() = FluxTheme.colorScheme.onSurface

/** 次要文字如 */
@Composable
private fun playerOnSurfaceVariant() = FluxTheme.colorScheme.onSurfaceVariant

/** 进度条轨道色 如 主文字色低透明 */
@Composable
private fun playerTrackColor() = FluxTheme.colorScheme.onSurface.copy(alpha = 0.25f)

/** 弹窗/卡片表面如 */
@Composable
private fun playerSurface() = FluxTheme.colorScheme.surface

/** 弹窗表面容器如 */
@Composable
private fun playerSurfaceContainer() = FluxTheme.colorScheme.surfaceContainer

/** 输入�?/次要表面如 */
@Composable
private fun playerSurfaceVariant() = FluxTheme.colorScheme.surfaceVariant

/** 主色如 */
@Composable
private fun playerPrimary() = FluxTheme.colorScheme.primary

/** 主色上的文字如 */
@Composable
private fun playerOnPrimary() = FluxTheme.colorScheme.onPrimary

/** 边框如 */
@Composable
private fun playerOutline() = FluxTheme.colorScheme.outline

// endregion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlaybackScreen(
    player: Player,
    onBackClick: () -> Unit,
    coverArtworkUri: Uri? = null,
    bookPath: String? = null,
    bookName: String? = null,
    chapterNames: List<String> = emptyList(),
    chapterPaths: List<String> = emptyList(),
    introSkipSeconds: Int = 0,
    outroSkipSeconds: Int = 0,
    onSkipSettingsChanged: (Int, Int) -> Unit = { _, _ -> },
    onSaveResume: (Int, Long, Long) -> Unit = { _, _, _ -> },
    onSpeedChanged: (Float) -> Unit = {},
    chapterProgress: Map<Int, Pair<Long, Long>> = emptyMap(),
    currentChapterIndex: Int? = null,
    onSelectChapter: ((Int) -> Unit)? = null,
    sleepState: AudioSleepTimer.State? = null,
    onSleepChange: ((Int, Int) -> Unit)? = null,
    coverModel: Any? = null,
    loading: Boolean = false,
    loadingLabel: String? = null,
    downloadAction: (@Composable () -> Unit)? = null,
    onRefreshDirectory: (() -> Unit)? = null,
    directoryRefreshing: Boolean = false,
    directoryRefreshEnabled: Boolean = true,
    directoryMessage: String? = null,
    playbackError: String? = null,
    playbackErrorContent: (@Composable () -> Unit)? = null,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val lightBackground = FluxTheme.colorScheme.background.luminance() > 0.5f
    SideEffect {
        val activity = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()
        activity?.let {
            androidx.core.view.WindowCompat.getInsetsController(it.window, view).apply {
                isAppearanceLightStatusBars = lightBackground
                isAppearanceLightNavigationBars = lightBackground
            }
        }
    }
    val metadataState = rememberMetadataState(player)
    val mediaState = rememberMediaPresentationState(player)

    val title = metadataState.title ?: chapterNames.getOrNull(currentChapterIndex ?: 0).orEmpty()
    val selectedIndex = currentChapterIndex ?: player.currentMediaItemIndex.coerceAtLeast(0)
    fun selectChapter(index: Int) {
        if (index !in chapterNames.indices) return
        if (onSelectChapter != null) {
            onSelectChapter(index)
        } else {
            player.seekToDefaultPosition(index)
            player.playWhenReady = true
        }
    }

    val artworkUri: Uri? = coverArtworkUri
        ?: player.currentMediaItem?.mediaMetadata?.artworkUri

    // ── 拖拽状如 ──
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }
    val displayPosition = if (isScrubbing) scrubPosition else mediaState.position.toFloat()

    // ── 速度菜单 ──
    var showSpeedSheet by remember { mutableStateOf(false) }
    var currentSpeed by remember { mutableStateOf(player.playbackParameters.speed) }
    LaunchedEffect(player.playbackParameters) { currentSpeed = player.playbackParameters.speed }

    // ── 定时关闭（状态由 AudioSleepTimer 单例持有、Service 驱动倒计时，Activity 重建不丢） ──
    val localSleepState by AudioSleepTimer.state.collectAsState()
    val sleepTimerState = sleepState ?: localSleepState
    val sleepRemaining = sleepTimerState.remainingSeconds
    val sleepEpisodes = sleepTimerState.remainingEpisodes
    fun changeSleep(seconds: Int = 0, episodes: Int = 0) {
        if (onSleepChange != null) {
            onSleepChange(seconds, episodes)
        } else {
            when {
                episodes > 0 -> AudioSleepTimer.startEpisodes(episodes, selectedIndex)
                seconds > 0 -> AudioSleepTimer.startMinutes(seconds)
                else -> AudioSleepTimer.cancel()
            }
        }
    }
    var showSleepSheet by remember { mutableStateOf(false) }
    var sleepModeMinutes by remember { mutableStateOf(true) } // 定时弹窗：true=按分钟，false=按集数
    var sleepHourIdx by remember { mutableIntStateOf(0) } // 定时弹窗：小时滚轮索引
    var sleepMinIdx by remember { mutableIntStateOf(0) } // 定时弹窗：分钟滚轮索引
    var sleepEpisodeIdx by remember { mutableIntStateOf(0) } // 定时弹窗：集数滚轮索引
    LaunchedEffect(showSleepSheet) {
        if (showSleepSheet) {
            // 打开弹窗时回显：按当前激活状态设置模式与滚轮位置
            when {
                sleepRemaining > 0 && sleepEpisodes == 0 -> {
                    sleepModeMinutes = true
                    sleepHourIdx = (sleepRemaining / 3600).coerceIn(0, 23)
                    sleepMinIdx = ((sleepRemaining % 3600) / 60).coerceIn(0, 59)
                }
                sleepEpisodes > 0 -> {
                    sleepModeMinutes = false
                    sleepEpisodeIdx = sleepEpisodes.coerceIn(0, 10)
                }
                else -> {
                    sleepModeMinutes = true
                    sleepHourIdx = 0
                    sleepMinIdx = 0
                }
            }
        }
    }

    // ── 定时保存播放进度（本地进度每 5 秒刷新，落盘节流到每 20 秒） ──
    // 落盘写的是全量 preferences map，高频写放大明显；切章与退出另有即时保存兜底
    var localProgress by remember { mutableStateOf(chapterProgress) }
    LaunchedEffect(mediaState.isPlaying, currentChapterIndex) {
        if (onSelectChapter != null) return@LaunchedEffect
        if (!mediaState.isPlaying) return@LaunchedEffect
        var tick = 0
        while (true) {
            delay(5000)
            val mediaId = player.currentMediaItem?.mediaId
            // 如 mediaId 如 chapterPaths 中反查真实章节索引，兜底 currentMediaItemIndex
            val idx = if (mediaId != null && chapterPaths.isNotEmpty()) {
                chapterPaths.indexOf(mediaId).coerceAtLeast(0)
            } else {
                player.currentMediaItemIndex.coerceAtLeast(0)
            }
            val pos = player.currentPosition
            val dur = player.duration
            // duration 未就绪（C.TIME_UNSET）时跳过，避免存入无效数据
            if (dur > 0) {
                localProgress = localProgress + (idx to (pos to dur))
                if (++tick % 4 == 0) {
                    onSaveResume(idx, pos, dur)
                }
            }
        }
    }

    LaunchedEffect(chapterProgress) { localProgress = chapterProgress }

    // ── 切章时立即保存上一章进度 ──
    // 心跳保存每 5 秒一次，快速连点下一章时上一章进度会丢；自然播完的章节记为已播完
    DisposableEffect(player, chapterPaths) {
        var lastIndex = player.currentMediaItemIndex
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (onSelectChapter != null) return
                val prevIndex = lastIndex
                lastIndex = player.currentMediaItemIndex
                if (prevIndex == player.currentMediaItemIndex) return
                val prev = localProgress[prevIndex] ?: return
                val (_, dur) = prev
                if (dur > 0) {
                    val pos = if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                        dur // 自然播完：整章已听完
                    } else {
                        prev.first.coerceIn(0, dur) // 手动切走：用最近一次心跳的位置
                    }
                    onSaveResume(prevIndex, pos, dur)
                    localProgress = localProgress + (prevIndex to (pos to dur))
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // ── 播放列表弹窗 ──
    var showPlaylistSheet by remember { mutableStateOf(false) }

    // 倍速弹窗（居中 Dialog：预设 + 大数字 + 滑块 0.1 粒度，滑动即生效）
    if (showSpeedSheet) {
        Dialog(onDismissRequest = { showSpeedSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(playerSurfaceContainer())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                // 标题栏
                SheetHeader(
                    title = stringResource(R.string.audio_playback_speed),
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_player_speed_btn),
                            contentDescription = null,
                            tint = playerPrimary(),
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onDismiss = { showSpeedSheet = false },
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 预设倍速（点选即生效）—— 含听书常用档位
                val speedPresets = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f)
                PresetChips(
                    options = speedPresets.map { formatSpeed(it) + "x" },
                    selected = speedPresets
                        .firstOrNull { kotlin.math.abs(it - currentSpeed) < 0.01f }
                        ?.let { formatSpeed(it) + "x" },
                    onSelect = { label ->
                        val speed = label.removeSuffix("x").toFloatOrNull() ?: 1.0f
                        currentSpeed = speed
                        player.setPlaybackSpeed(speed)
                        onSpeedChanged(speed)
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 支持每次微调 0.05 倍，预设和滑块使用相同精度。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(enabled = currentSpeed > 0.5f, onClick = {
                        currentSpeed = ((currentSpeed * 20).roundToInt() - 1).coerceAtLeast(10) / 20f
                        player.setPlaybackSpeed(currentSpeed)
                        onSpeedChanged(currentSpeed)
                    }) { Text("−", fontSize = 24.sp) }
                    Text(
                        text = formatSpeed(currentSpeed),
                        color = playerPrimary(),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "x",
                        color = playerOnSurfaceVariant(),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                    TextButton(enabled = currentSpeed < 4f, onClick = {
                        currentSpeed = ((currentSpeed * 20).roundToInt() + 1).coerceAtMost(80) / 20f
                        player.setPlaybackSpeed(currentSpeed)
                        onSpeedChanged(currentSpeed)
                    }) { Text("＋", fontSize = 24.sp) }
                }
                Text("调整立即试听，松手后保存。", color = playerOnSurfaceVariant(), fontSize = 12.sp)

                Spacer(modifier = Modifier.height(4.dp))

                // 滑块与微调按钮均使用 0.05 倍精度，拖动立即试听。
                CompactSlider(
                    value = currentSpeed,
                    onValueChange = { speed ->
                        currentSpeed = speed
                        player.setPlaybackSpeed(speed)
                    },
                    onValueChangeFinished = { onSpeedChanged(currentSpeed) },
                    valueRange = 0.5f..4.0f,
                    steps = 69,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 两端刻度
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(text = "0.5x", fontSize = 11.sp, color = playerOnSurfaceVariant())
                    Text(text = "4.0x", fontSize = 11.sp, color = playerOnSurfaceVariant())
                }
            }
        }
    }

    // 鈹€鈹€ 閫熷害鑿滃崟 鈹€鈹€

    // 每次打开都读取当前书籍设置，草稿在确认后才生效。
    var showSkipSheet by remember(bookPath) { mutableStateOf(false) }
    var editIntro by remember(bookPath) { mutableIntStateOf(introSkipSeconds) }
    var editOutro by remember(bookPath) { mutableIntStateOf(outroSkipSeconds) }
    var introText by remember(bookPath) { mutableStateOf("") }
    var outroText by remember(bookPath) { mutableStateOf("") }
    LaunchedEffect(showSkipSheet, bookPath, introSkipSeconds, outroSkipSeconds) {
        if (showSkipSheet) {
            editIntro = introSkipSeconds.coerceIn(0, 300)
            editOutro = outroSkipSeconds.coerceIn(0, 300)
            introText = editIntro.toString()
            outroText = editOutro.toString()
        }
    }
    if (showSkipSheet) {
        Dialog(onDismissRequest = { showSkipSheet = false }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(playerSurfaceContainer())
                    .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                SheetHeader(title = stringResource(R.string.audio_skip_title), icon = { Icon(painterResource(R.drawable.ic_player_skip_btn), null, tint = playerPrimary()) }, onDismiss = { showSkipSheet = false })
                Text("应用于本书所有章节，保存后生效。", color = playerOnSurfaceVariant(), fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = introText,
                    onValueChange = {
                        introText = it.filter(Char::isDigit).take(3)
                        editIntro = introText.toIntOrNull()?.coerceIn(0, 300) ?: 0
                    },
                    label = { Text("片头秒数（0–300）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                SliderBlock(
                    title = "跳过片头", valueText = "$editIntro 秒", value = editIntro.toFloat(),
                    onValueChange = {
                        editIntro = it.roundToInt()
                        introText = editIntro.toString()
                    },
                    onValueChangeFinished = {}, valueRange = 0f..300f, steps = 59, startLabel = "0秒", endLabel = "300秒",
                )
                PresetChips(
                    options = listOf("0秒", "30秒", "60秒", "120秒"),
                    selected = "${editIntro}秒",
                    onSelect = {
                        editIntro = it.removeSuffix("秒").toInt()
                        introText = editIntro.toString()
                    },
                )
                TextButton(onClick = {
                    editIntro = (player.currentPosition / 1000).toInt().coerceIn(0, 300)
                    introText = editIntro.toString()
                }) { Text("当前位置设为片头结束（最多300秒）") }
                OutlinedTextField(
                    value = outroText,
                    onValueChange = {
                        outroText = it.filter(Char::isDigit).take(3)
                        editOutro = outroText.toIntOrNull()?.coerceIn(0, 300) ?: 0
                    },
                    label = { Text("片尾秒数（0–300）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                SliderBlock(
                    title = "跳过片尾", valueText = "$editOutro 秒", value = editOutro.toFloat(),
                    onValueChange = {
                        editOutro = it.roundToInt()
                        outroText = editOutro.toString()
                    },
                    onValueChangeFinished = {}, valueRange = 0f..300f, steps = 59, startLabel = "0秒", endLabel = "300秒",
                )
                PresetChips(
                    options = listOf("0秒", "30秒", "60秒", "120秒"),
                    selected = "${editOutro}秒",
                    onSelect = {
                        editOutro = it.removeSuffix("秒").toInt()
                        outroText = editOutro.toString()
                    },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = {
                        editIntro = 0
                        editOutro = 0
                        introText = "0"
                        outroText = "0"
                    }) { Text("全部归零") }
                    TextButton(
                        enabled = (introText.toIntOrNull() ?: -1) in 0..300 && (outroText.toIntOrNull() ?: -1) in 0..300,
                        onClick = {
                            onSkipSettingsChanged(editIntro, editOutro)
                            showSkipSheet = false
                        },
                    ) { Text("保存设置") }
                }
            }
        }
    }

    // 自定义定时先编辑，确认后启动；打开弹窗不会重置已有倒计时。
    if (showSleepSheet) {
        Dialog(onDismissRequest = { showSleepSheet = false }) {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(playerSurfaceContainer())
                    .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                SheetHeader(title = stringResource(R.string.audio_sleep_timer), icon = { Icon(painterResource(R.drawable.ic_player_timing_btn), null, tint = playerPrimary()) }, onDismiss = { showSleepSheet = false })
                if (sleepRemaining > 0) Text("当前剩余 ${sleepRemaining / 60}分${sleepRemaining % 60}秒", color = playerPrimary())
                if (sleepEpisodes > 0) Text(if (sleepEpisodes == 1) "当前设置：本章结束后停止" else "当前设置：再听 $sleepEpisodes 集", color = playerPrimary())
                TextButton(onClick = {
                    changeSleep(episodes = 1)
                    showSleepSheet = false
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("播完本章后停止")
                }
                PresetChips(
                    options = listOf("15分钟", "30分钟", "45分钟", "60分钟"),
                    selected = null,
                    onSelect = {
                        changeSleep(seconds = it.removeSuffix("分钟").toInt() * 60)
                        showSleepSheet = false
                    },
                )
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SleepModeTab(text = "按时长", selected = sleepModeMinutes, onClick = { sleepModeMinutes = true }, modifier = Modifier.weight(1f))
                    SleepModeTab(text = "按集数", selected = !sleepModeMinutes, onClick = { sleepModeMinutes = false }, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                if (sleepModeMinutes) {
                    Text("按实际收听时长计时，暂停和缓冲期间不计时。", color = playerOnSurfaceVariant(), fontSize = 12.sp)
                    SliderBlock(
                        title = "小时", valueText = "$sleepHourIdx 小时", value = sleepHourIdx.toFloat(),
                        onValueChange = { sleepHourIdx = it.roundToInt() }, onValueChangeFinished = {},
                        valueRange = 0f..23f, steps = 22, startLabel = "0小时", endLabel = "23小时",
                    )
                    SliderBlock(
                        title = "分钟", valueText = "$sleepMinIdx 分钟", value = sleepMinIdx.toFloat(),
                        onValueChange = { sleepMinIdx = it.roundToInt() }, onValueChangeFinished = {},
                        valueRange = 0f..59f, steps = 58, startLabel = "0分钟", endLabel = "59分钟",
                    )
                } else {
                    Text("包含当前章节；手动切章不消耗集数。", color = playerOnSurfaceVariant(), fontSize = 12.sp)
                    PresetChips(
                        options = listOf("2集", "5集", "10集"),
                        selected = "${sleepEpisodeIdx}集",
                        onSelect = { sleepEpisodeIdx = it.removeSuffix("集").toInt() },
                    )
                    SliderBlock(
                        title = "集数", valueText = "$sleepEpisodeIdx 集", value = sleepEpisodeIdx.toFloat(),
                        onValueChange = { sleepEpisodeIdx = it.roundToInt() }, onValueChangeFinished = {},
                        valueRange = 0f..10f, steps = 9, startLabel = "0集", endLabel = "10集",
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = sleepRemaining > 0 || sleepEpisodes > 0, onClick = {
                        changeSleep()
                        showSleepSheet = false
                    }) { Text("取消定时") }
                    TextButton(
                        enabled = if (sleepModeMinutes) sleepHourIdx * 3600 + sleepMinIdx * 60 > 0 else sleepEpisodeIdx > 0,
                        onClick = {
                            if (sleepModeMinutes) changeSleep(seconds = sleepHourIdx * 3600 + sleepMinIdx * 60) else changeSleep(episodes = sleepEpisodeIdx)
                            showSleepSheet = false
                        },
                    ) { Text("开始定时") }
                }
            }
        }
    }

    // ── 播放列表弹窗 ──
    if (showPlaylistSheet && chapterNames.isNotEmpty()) {
        var query by remember(bookPath) { mutableStateOf("") }
        var chapterNumber by remember(bookPath) { mutableStateOf("") }
        var locatedIndex by remember(bookPath) { mutableStateOf<Int?>(null) }
        val matching = remember(chapterNames, query) { matchingListeningChapters(chapterNames, query) }
        // 播放列表滚动状态 + 已播章节（进度打点）
        val playlistListState = rememberLazyListState()
        val playedChapterIndexes = remember(localProgress) {
            localProgress.filterValues { (pos, dur) -> dur > 0 && pos > 0 }.keys
        }
        val playlistScope = rememberCoroutineScope()
        val playlistCurrentIndex = selectedIndex

        // 打开弹窗自动滚动定位到当前播放集（等全屏展开动画进入尾声再滚，避免动画+跳转叠加掉帧）
        LaunchedEffect(showPlaylistSheet) {
            if (showPlaylistSheet) {
                delay(300)
                playlistListState.scrollToItem(playlistCurrentIndex.coerceIn(chapterNames.indices))
            }
        }

        ModalBottomSheet(
            onDismissRequest = { showPlaylistSheet = false },
            // 直接全屏展开，跳过半屏停靠
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = playerSurfaceContainer(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 统一标题栏（含总集数）
                SheetHeader(
                    title = stringResource(R.string.audio_playlist),
                    subtitle = stringResource(R.string.audio_playlist_count, chapterNames.size),
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_player_list_btn),
                            contentDescription = null,
                            tint = playerPrimary(),
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onDismiss = { showPlaylistSheet = false },
                    trailingAction = onRefreshDirectory?.let { refresh ->
                        {
                            TextButton(onClick = refresh, enabled = directoryRefreshEnabled && !directoryRefreshing) {
                                Text(if (directoryRefreshing) "更新中…" else "更新目录")
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                directoryMessage?.let { Text(it, color = playerOnSurfaceVariant(), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) }
                Spacer(modifier = Modifier.height(16.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜索章节标题") },
                    singleLine = true,
                    trailingIcon = if (query.isNotEmpty()) {
                        { TextButton(onClick = { query = "" }) { Text("清空") } }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.OutlinedTextField(
                        value = chapterNumber,
                        onValueChange = { chapterNumber = it.filter(Char::isDigit).take(8) },
                        label = { Text("输入章数（1–${chapterNames.size}）") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.TextButton(
                        enabled = listeningChapterNumber(chapterNumber, chapterNames.size) != null,
                        onClick = {
                            val index = listeningChapterNumber(chapterNumber, chapterNames.size) ?: return@TextButton
                            query = ""
                            playlistScope.launch {
                                while (playlistListState.layoutInfo.totalItemsCount != chapterNames.size) withFrameNanos { }
                                playlistListState.scrollToItem(index)
                                locatedIndex = index
                                delay(1800)
                                if (locatedIndex == index) locatedIndex = null
                            }
                        },
                    ) { Text("定位章节") }
                }
                if (query.isNotBlank()) Text("找到 ${matching.size} 章", color = playerOnSurfaceVariant(), modifier = Modifier.padding(horizontal = 24.dp))
                if (matching.isEmpty()) Text("没有匹配的章节", modifier = Modifier.padding(24.dp))
                Box(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        state = playlistListState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 24.dp, end = 70.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        itemsIndexed(matching, key = { _, index -> index }) { _, index ->
                            val name = chapterNames[index]
                            val isActive = index == selectedIndex
                            val progress = localProgress[index]
                            val progressText = if (progress != null && progress.second > 0) {
                                val pct = (progress.first * 100 / progress.second).coerceIn(0, 100)
                                if (pct >= 95) "已听完" else if (pct > 0) "听了 $pct%" else "未听"
                            } else {
                                "未听"
                            }

                            Surface(
                                onClick = {
                                    selectChapter(index)
                                    showPlaylistSheet = false
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = if (isActive) playerPrimary().copy(alpha = 0.3f) else if (locatedIndex == index) playerPrimary().copy(alpha = 0.12f) else Color.Transparent,
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    // 当前集：主色播放小圆标
                                    if (isActive) {
                                        Box(
                                            modifier = Modifier
                                                .size(16.dp)
                                                .clip(CircleShape)
                                                .background(playerPrimary()),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                painter = painterResource(if (mediaState.isPlaying) coreUiR.drawable.ic_play else coreUiR.drawable.ic_pause),
                                                contentDescription = null,
                                                tint = playerOnPrimary(),
                                                modifier = Modifier.size(9.dp),
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                    }
                                    Text(
                                        text = "${index + 1}",
                                        color = if (isActive) playerPrimary() else playerOnSurfaceVariant(),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(end = 10.dp),
                                    )
                                    Text(
                                        text = name,
                                        color = if (isActive) playerOnSurface() else playerOnSurfaceVariant(),
                                        fontSize = 15.sp,
                                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (progressText != null) {
                                        Text(
                                            text = progressText,
                                            color = if (isActive) playerPrimary() else playerOnSurfaceVariant(),
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(start = 8.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // 右侧可拖拽滚动条（共享组件：胶囊 + 已播打点）
                    if (query.isBlank()) {
                        ChapterDragScrollbar(
                            listState = playlistListState,
                            totalCount = chapterNames.size,
                            playedChapters = playedChapterIndexes,
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 2.dp),
                        )
                    }
                    // 圆形导航按钮组：顶部 / 当前 / 底部（右下角，避开滚动条）
                    if (query.isBlank()) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 12.dp, bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircleNavButton(
                                text = stringResource(R.string.audio_nav_top),
                                onClick = { playlistScope.launch { playlistListState.scrollToItem(0) } },
                            )
                            CircleNavButton(
                                text = stringResource(R.string.audio_nav_current),
                                selected = true,
                                onClick = {
                                    playlistScope.launch { playlistListState.scrollToItem(playlistCurrentIndex) }
                                },
                            )
                            CircleNavButton(
                                text = stringResource(R.string.audio_nav_bottom),
                                onClick = {
                                    playlistScope.launch {
                                        playlistListState.scrollToItem(chapterNames.lastIndex)
                                    }
                                },
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    val notificationState = remember { FluxNotificationState() }
    val playbackArtwork = coverModel ?: artworkUri
    val displayBookTitle = bookName?.takeIf { it.isNotBlank() } ?: title
    var artworkTint by remember(playbackArtwork) { mutableStateOf(Color.Transparent) }
    var artworkBackdrop by remember(playbackArtwork) { mutableStateOf<ImageBitmap?>(null) }
    val artworkBreath = rememberArtworkBreath(mediaState.isPlaying)

    // 进入页面时轻微上移并淡入；切章、缓冲和播放状态变化不会重播动画。
    val entranceProgress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entranceProgress.animateTo(1f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
    }

    // ── UI ──
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(playerBg()),
    ) {
        PlaybackArtworkBackground(artworkTint, artworkBackdrop, artworkBreath, Modifier.matchParentSize())
        // 当前章节索引
        val currentIndex = selectedIndex
        val chapterTitle = chapterNames.getOrElse(currentIndex) { title }
        val hasChapters = chapterNames.isNotEmpty()
        // 书名和章节名相同时只显示一次，避免重复
        val showChapterName = hasChapters && chapterTitle.isNotEmpty() && chapterTitle != displayBookTitle

        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = entranceProgress.value
                    translationY = 16.dp.toPx() * (1f - entranceProgress.value)
                }
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp),
        ) {
            TopBar(onBackClick = onBackClick)

            // 封面占用剩余空间，文字随字号撑开，避免遮住集数。
            PlaybackAlbumCover(
                artwork = playbackArtwork,
                title = displayBookTitle,
                onColor = { artworkTint = it },
                onBackdrop = { artworkBackdrop = it },
                breath = artworkBreath,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 保留最小高度，同时允许三行文字按系统字号自然扩展。
            Column(
                Modifier.fillMaxWidth().heightIn(min = 88.dp).padding(vertical = 4.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = displayBookTitle,
                    color = playerOnSurface(),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().basicMarquee(initialDelayMillis = 2500),
                )
                if (showChapterName) {
                    Text(
                        text = chapterTitle,
                        color = playerOnSurfaceVariant(),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 6.dp).fillMaxWidth().basicMarquee(initialDelayMillis = 4500),
                    )
                }
                if (hasChapters) {
                    Text(
                        text = "第 ${currentIndex + 1} / ${chapterNames.size} 集",
                        modifier = Modifier.padding(top = 6.dp),
                        color = playerOnSurfaceVariant().copy(alpha = 0.7f),
                        fontSize = 11.sp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── 功能按钮行（对齐小梨：进度条上方，marginTop 32） ──
            BottomFunctionRow(
                currentSpeed = currentSpeed,
                sleepRemaining = sleepRemaining,
                sleepEpisodes = sleepEpisodes,
                introSkipSeconds = introSkipSeconds,
                outroSkipSeconds = outroSkipSeconds,
                onSleepClick = { showSleepSheet = true },
                onSkipClick = { showSkipSheet = true },
                onSpeedClick = { showSpeedSheet = true },
                onPlaylistClick = { showPlaylistSheet = true },
                downloadAction = downloadAction,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── 进度条 ──
            AudioSeekbar(
                position = displayPosition,
                duration = mediaState.duration.toFloat(),
                onSeek = {
                    scrubPosition = it
                    isScrubbing = true
                },
                onSeekFinished = {
                    player.seekTo(scrubPosition.toLong())
                    isScrubbing = false
                },
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── 控制条（最底部） ──
            TransportRow(player = player, isPlaying = mediaState.isPlaying, currentIndex = selectedIndex, chapterCount = chapterNames.size, onSelectChapter = ::selectChapter)

            Spacer(modifier = Modifier.height(32.dp))
        }

        if (mediaState.isBuffering || loading) {
            loadingLabel?.let { Text(it, modifier = Modifier.align(Alignment.Center).padding(top = 80.dp), color = playerOnSurface()) }
            MediaLoadingIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
                color = playerOnSurface(),
                strokeWidth = 3.dp,
            )
        }

        if (playbackError != null) {
            Box(modifier = Modifier.align(Alignment.BottomCenter).padding(20.dp)) {
                if (playbackErrorContent != null) {
                    playbackErrorContent()
                } else {
                    Surface(shape = RoundedCornerShape(16.dp), color = playerSurfaceContainer()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(playbackError, color = FluxTheme.colorScheme.error)
                            androidx.compose.material3.TextButton(onClick = onRetry) { Text("重试本章") }
                        }
                    }
                }
            }
        }
        FluxNotificationBanner(
            event = notificationState.currentEvent,
            onDismiss = { notificationState.dismiss() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// region ── 子组如 ──

/**
 * 统一弹窗标题栏：图标圆 + 标题 + 副标题 + 右上角关闭。
 */
@Composable
private fun SheetHeader(
    title: String,
    icon: @Composable () -> Unit,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    trailingAction: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = playerPrimary().copy(alpha = 0.12f),
            modifier = Modifier.size(36.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                icon()
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                color = playerOnSurface(),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = playerOnSurfaceVariant(),
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        trailingAction?.invoke()
        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.audio_close),
                tint = playerOnSurfaceVariant(),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SleepModeTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) playerSurface() else Color.Transparent,
        border = if (selected) BorderStroke(0.5.dp, playerOutline()) else null,
        modifier = modifier,
    ) {
        Text(
            text = text,
            color = if (selected) playerPrimary() else playerOnSurfaceVariant(),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 9.dp),
        )
    }
}

@Composable
private fun CircleNavButton(
    text: String,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) playerPrimary() else playerSurface(),
        border = if (selected) null else BorderStroke(0.5.dp, playerOutline()),
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = text,
                color = if (selected) playerOnPrimary() else playerOnSurfaceVariant(),
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun CompactSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    modifier: Modifier = Modifier,
) {
    val primary = playerPrimary()
    val onSurface = playerOnSurface()
    val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
    val stepSize = if (steps > 0) (valueRange.endInclusive - valueRange.start) / (steps + 1) else 0f

    fun valueFromX(x: Float): Float {
        val raw = valueRange.start + (valueRange.endInclusive - valueRange.start) * (x.coerceIn(0f, 1f))
        if (steps <= 0) return raw.coerceIn(valueRange.start, valueRange.endInclusive)
        val snapped = ((raw - valueRange.start) / stepSize).roundToInt()
        return (valueRange.start + snapped * stepSize).coerceIn(valueRange.start, valueRange.endInclusive)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            // 点击轨道任意位置直接跳转
            .pointerInput(valueRange, steps) {
                detectTapGestures(
                    onTap = { offset ->
                        onValueChange(valueFromX(offset.x / size.width))
                        onValueChangeFinished()
                    },
                )
            }
            // 拖动
            .pointerInput(valueRange, steps) {
                detectDragGestures(
                    onDragStart = { offset -> onValueChange(valueFromX(offset.x / size.width)) },
                    onDrag = { change, _ ->
                        change.consume()
                        onValueChange(valueFromX(change.position.x / size.width))
                    },
                    onDragEnd = { onValueChangeFinished() },
                    onDragCancel = { onValueChangeFinished() },
                )
            },
    ) {
        val trackHeight = 8.dp
        val thumbSize = 18.dp
        val thumbTravel = maxWidth - thumbSize

        // 轨道底（加宽）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .align(Alignment.Center)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(onSurface.copy(alpha = 0.15f)),
        )
        // 已播段（主色）
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(trackHeight)
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(primary),
        )
        // thumb（白底 + 主色描边）
        Box(
            modifier = Modifier
                .size(thumbSize)
                .offset(x = thumbTravel * fraction)
                .align(Alignment.CenterStart)
                .background(FluxTheme.colorScheme.surface, CircleShape)
                .border(2.dp, primary, CircleShape),
        )
    }
}

/**
 * 滑块块：标签行（标题 + 右侧实时值）+ 自绘滑块 + 两端刻度。
 */
@Composable
private fun SliderBlock(
    title: String,
    valueText: String?,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    startLabel: String,
    endLabel: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                color = playerOnSurface(),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (valueText != null) {
                Text(
                    text = valueText,
                    color = playerPrimary(),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        CompactSlider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = startLabel, fontSize = 11.sp, color = playerOnSurfaceVariant())
            Text(text = endLabel, fontSize = 11.sp, color = playerOnSurfaceVariant())
        }
    }
}

/**
 * 预设值胶囊行：横向均分，选中主色高亮，点选回调。
 */
@Composable
private fun PresetChips(
    options: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { opt ->
            val isSelected = opt == selected
            Surface(
                onClick = { onSelect(opt) },
                shape = RoundedCornerShape(50),
                color = if (isSelected) playerPrimary() else playerSurfaceVariant(),
                border = if (isSelected) null else BorderStroke(0.5.dp, playerOutline()),
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = opt,
                    color = if (isSelected) playerOnPrimary() else playerOnSurfaceVariant(),
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun TopBar(onBackClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左：返回箭头
        IconButton(onClick = onBackClick, modifier = Modifier.size(32.dp)) {
            Icon(
                painter = painterResource(coreUiR.drawable.ic_arrow_left),
                contentDescription = stringResource(R.string.audio_back),
                tint = FluxTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun InfoTag(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = FluxTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    ) {
        Text(
            text = text,
            color = FluxTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun AudioSeekbar(
    position: Float,
    duration: Float,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
) {
    val primary = FluxTheme.colorScheme.primary
    val onSurface = FluxTheme.colorScheme.onSurface
    val fraction = if (duration > 0f) (position / duration).coerceIn(0f, 1f) else 0f

    Column(modifier = Modifier.fillMaxWidth()) {
        // 自定义进度条（加宽轨道 + 圆点 thumb + 点击/拖动均可定位）
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                // 点击轨道任意位置直接跳转
                .pointerInput(duration) {
                    detectTapGestures(
                        onTap = { offset ->
                            onSeek(offset.x.coerceIn(0f, size.width.toFloat()) / size.width * duration)
                            onSeekFinished()
                        },
                    )
                }
                // 拖动
                .pointerInput(duration) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            onSeek(offset.x.coerceIn(0f, size.width.toFloat()) / size.width * duration)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            onSeek(change.position.x.coerceIn(0f, size.width.toFloat()) / size.width * duration)
                        },
                        onDragEnd = { onSeekFinished() },
                        onDragCancel = { onSeekFinished() },
                    )
                },
        ) {
            val trackHeight = 8.dp
            val thumbSize = 18.dp
            val thumbTravel = maxWidth - thumbSize

            // 轨道底（浅色，加宽）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(trackHeight / 2))
                    .background(onSurface.copy(alpha = 0.15f)),
            )
            // 已播部分（主色）
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(trackHeight)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(trackHeight / 2))
                    .background(primary),
            )
            // thumb 圆点（白底 + 主色描边）
            Box(
                modifier = Modifier
                    .size(thumbSize)
                    .offset(x = thumbTravel * fraction)
                    .align(Alignment.CenterStart)
                    .background(FluxTheme.colorScheme.surface, CircleShape)
                    .border(1.dp, primary, CircleShape),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatTime(position.toLong()),
                fontSize = 12.sp,
                color = FluxTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
            Text(
                text = formatTime(duration.toLong()),
                fontSize = 12.sp,
                color = FluxTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun TransportRow(player: Player, isPlaying: Boolean, currentIndex: Int, chapterCount: Int, onSelectChapter: (Int) -> Unit) {
    val playPauseState = androidx.media3.ui.compose.state.rememberPlayPauseButtonState(player)
    val onSurface = FluxTheme.colorScheme.onSurface
    val primary = FluxTheme.colorScheme.primary

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 快退（对齐小梨 ic_player_backward）
        IconButton(onClick = {
            player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
        }) {
            Icon(
                painter = painterResource(R.drawable.ic_player_backward),
                contentDescription = stringResource(R.string.audio_rewind_10s),
                tint = onSurface.copy(alpha = 0.55f),
                modifier = Modifier.size(28.dp),
            )
        }

        // 上一集（对齐小梨 ic_player_seek_to_previous）
        IconButton(onClick = {
            if (currentIndex > 0) {
                onSelectChapter(currentIndex - 1)
            } else {
                player.seekTo(0)
            }
        }) {
            Icon(
                painter = painterResource(R.drawable.ic_player_seek_to_previous),
                contentDescription = stringResource(R.string.audio_previous_episode),
                tint = onSurface.copy(alpha = 0.55f),
                modifier = Modifier.size(28.dp),
            )
        }

        // 播放 / 暂停（透明背景 + 满尺寸大图标，三角圆润）
        Box(
            modifier = Modifier.size(80.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = { playPauseState.onClick() },
                modifier = Modifier.size(80.dp),
            ) {
                Icon(
                    painter = painterResource(
                        if (playPauseState.showPlay) {
                            R.drawable.ic_player_play
                        } else {
                            R.drawable.ic_player_pause
                        },
                    ),
                    contentDescription = if (playPauseState.showPlay) {
                        stringResource(R.string.audio_play)
                    } else {
                        stringResource(R.string.audio_pause)
                    },
                    tint = onSurface.copy(alpha = 0.9f),
                    modifier = Modifier.size(72.dp),
                )
            }
        }

        // 下一集（对齐小梨 ic_player_seek_to_next）
        IconButton(onClick = {
            if (currentIndex < chapterCount - 1) onSelectChapter(currentIndex + 1)
        }) {
            Icon(
                painter = painterResource(R.drawable.ic_player_seek_to_next),
                contentDescription = stringResource(R.string.audio_next_episode),
                tint = onSurface.copy(alpha = 0.55f),
                modifier = Modifier.size(28.dp),
            )
        }

        // 快进（对齐小梨 ic_player_forward）
        IconButton(onClick = {
            player.seekTo((player.currentPosition + 10_000L).coerceAtMost(player.duration.coerceAtLeast(0)))
        }) {
            Icon(
                painter = painterResource(R.drawable.ic_player_forward),
                contentDescription = stringResource(R.string.audio_forward_10s),
                tint = onSurface.copy(alpha = 0.55f),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun BottomFunctionRow(
    currentSpeed: Float,
    sleepRemaining: Int,
    sleepEpisodes: Int,
    introSkipSeconds: Int,
    outroSkipSeconds: Int,
    onSleepClick: () -> Unit,
    onSkipClick: () -> Unit,
    onSpeedClick: () -> Unit,
    onPlaylistClick: () -> Unit,
    downloadAction: (@Composable () -> Unit)? = null,
) {
    val sleepLabel = if (sleepRemaining > 0) {
        val mins = sleepRemaining / 60
        val secs = sleepRemaining % 60
        "$mins:%02d".format(secs)
    } else if (sleepEpisodes == 1) {
        "本章结束"
    } else if (sleepEpisodes > 1) {
        "再听${sleepEpisodes}集"
    } else {
        stringResource(R.string.audio_sleep_label)
    }
    val onSurfaceAlpha = FluxTheme.colorScheme.onSurface.copy(alpha = 0.8f)

    // 下载入口位于目录右侧，与已有功能按钮共用同一行。
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 定时（对齐小梨 ic_player_timing_btn）
        FunctionButton(
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_player_timing_btn),
                    contentDescription = null,
                    tint = if (sleepRemaining > 0 || sleepEpisodes > 0) FluxTheme.colorScheme.primary else onSurfaceAlpha,
                    modifier = Modifier.size(28.dp),
                )
            },
            label = sleepLabel,
            onClick = onSleepClick,
            modifier = Modifier.weight(1f),
        )

        // 跳过头尾（对齐小梨 ic_player_skip_btn）
        FunctionButton(
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_player_skip_btn),
                    contentDescription = null,
                    tint = if (introSkipSeconds > 0 || outroSkipSeconds > 0) FluxTheme.colorScheme.primary else onSurfaceAlpha,
                    modifier = Modifier.size(28.dp),
                )
            },
            label = if (introSkipSeconds > 0 || outroSkipSeconds > 0) "头${introSkipSeconds}s/尾${outroSkipSeconds}s" else stringResource(R.string.audio_skip_intro_outro),
            onClick = onSkipClick,
            modifier = Modifier.weight(1f),
        )

        // 倍速（对齐小梨 ic_player_speed_btn）
        FunctionButton(
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_player_speed_btn),
                    contentDescription = null,
                    tint = onSurfaceAlpha,
                    modifier = Modifier.size(28.dp),
                )
            },
            label = "${formatSpeed(currentSpeed)}x",
            onClick = onSpeedClick,
            modifier = Modifier.weight(1f),
        )

        // 目录（对齐小梨 ic_player_list_btn）
        FunctionButton(
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_player_list_btn),
                    contentDescription = null,
                    tint = onSurfaceAlpha,
                    modifier = Modifier.size(28.dp),
                )
            },
            label = stringResource(R.string.audio_list),
            onClick = onPlaylistClick,
            modifier = Modifier.weight(1f),
        )
        downloadAction?.invoke()
    }
}

@Composable
fun AudioDownloadButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 56.dp)) { Text("下载") }
}

@Composable
private fun FunctionButton(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 68.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(FluxTheme.colorScheme.onSurface.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) { icon() }
        Text(text = label, fontSize = 10.sp, color = FluxTheme.colorScheme.onSurfaceVariant, maxLines = 2, textAlign = TextAlign.Center, modifier = Modifier.height(28.dp))
    }
}

// endregion

// region ── 工具函数 ──

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d:%02d".format(hours, minutes, seconds)
}

/** 倍速格式化：1.0 → "1"，1.25 → "1.25"，1.4 → "1.4"（先四舍五入到百分位，防浮点误差）。 */
private fun formatSpeed(speed: Float): String {
    val rounded = (speed * 100f).roundToInt() / 100f
    if (rounded % 1f == 0f) return rounded.toInt().toString()
    return "%.2f".format(rounded).trimEnd('0').trimEnd('.')
}

// endregion

// region ── Loading 界面 ──

/**
 * player 未就绪时如 loading 界面，避免黑屏�?
 * 显示模糊封面背景 + 居中进度指示器�?
 */
@Composable
fun AudioLoadingScreen(coverArtworkUri: Uri? = null, title: String? = null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FluxTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (coverArtworkUri != null) {
                Box(
                    modifier = Modifier
                        .size(width = 150.dp, height = 200.dp)
                        .shadow(20.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(FluxTheme.colorScheme.surface),
                ) {
                    AsyncImage(
                        imageLoader = rememberBookCoverImageLoader(),
                        model = coverArtworkUri,
                        contentDescription = stringResource(R.string.audio_album_cover),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(modifier = Modifier.height(28.dp))
            }
            if (title != null) {
                Text(
                    text = title,
                    color = FluxTheme.colorScheme.onSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
            MediaLoadingIndicator(
                modifier = Modifier.size(40.dp),
                color = FluxTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
        }
    }
}

// endregion
