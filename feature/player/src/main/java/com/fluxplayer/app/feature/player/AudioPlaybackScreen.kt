package com.fluxplayer.app.feature.player

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material3.ripple
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import com.fluxplayer.app.core.model.FluxMessageEvent
import com.fluxplayer.app.core.ui.components.FluxNotificationBanner
import com.fluxplayer.app.core.ui.components.FluxNotificationState
import com.fluxplayer.app.feature.player.state.rememberMediaPresentationState
import com.fluxplayer.app.feature.player.state.rememberMetadataState
import com.fluxplayer.app.core.ui.R as coreUiR
import com.fluxplayer.app.core.ui.theme.FluxTheme
import androidx.compose.material.icons.Icons.AutoMirrored
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
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
    chapterNames: List<String> = emptyList(),
    chapterPaths: List<String> = emptyList(),
    introSkipSeconds: Int = 0,
    outroSkipSeconds: Int = 0,
    onSkipSettingsChanged: (Int, Int) -> Unit = { _, _ -> },
    onSaveResume: (Int, Long, Long) -> Unit = { _, _, _ -> },
    onSpeedChanged: (Float) -> Unit = {},
    chapterProgress: Map<Int, Pair<Long, Long>> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    val metadataState = rememberMetadataState(player)
    val mediaState = rememberMediaPresentationState(player)

    val title = metadataState.title ?: ""

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

    // ── 自定义倍速 ──
    var showCustomSpeed by remember { mutableStateOf(false) }
    var customSpeedText by remember { mutableStateOf("") }

    // ── 定时关闭 ──
    var sleepRemaining by remember { mutableIntStateOf(0) }  // 剩余秒数�?0=未激�?
    var sleepCustomMins by remember { mutableIntStateOf(0) }  // 自定义分钟数
    var showSleepSheet by remember { mutableStateOf(false) }
    LaunchedEffect(sleepRemaining) {
        if (sleepRemaining > 0) {
            delay(1000)
            sleepRemaining -= 1
            if (sleepRemaining == 0) {
                player.pause()
            }
        }
    }

    // ── 定时保存播放进度（每 5 秒） ──
    var localProgress by remember { mutableStateOf(chapterProgress) }
    LaunchedEffect(mediaState.isPlaying) {
        if (!mediaState.isPlaying) return@LaunchedEffect
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
            // duration 未就绪（C.TIME_UNSET）时跳过，避免存入无效数�?
            if (dur > 0) {
                onSaveResume(idx, pos, dur)
                // 立即更新本地进度（不如 DataStore 回流�?
                localProgress = localProgress + (idx to (pos to dur))
            }
        }
    }

    // ── 播放列表弹窗 ──
    var showPlaylistSheet by remember { mutableStateOf(false) }

    // 倍速弹窗
    if (showSpeedSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSpeedSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            containerColor = playerSurfaceContainer(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 40.dp),
            ) {
                Text("播放速度", color = playerOnSurface(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(20.dp))
                // Pill 按钮网格
                val allSpeeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 两行网格
                    allSpeeds.chunked(4).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            row.forEach { speed ->
                                val isActive = (currentSpeed - speed) in -0.05f..0.05f
                                Surface(
                                    onClick = {
                                        player.setPlaybackSpeed(speed)
                                        currentSpeed = speed
                                        onSpeedChanged(speed)
                                        showSpeedSheet = false
                                    },
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (isActive) playerPrimary() else playerSurface(),
                                    border = if (isActive) null else BorderStroke(0.5.dp, playerOutline()),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        text = "${speed}x",
                                        color = if (isActive) playerOnPrimary() else playerOnSurface(),
                                        fontSize = 14.sp,
                                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                                        modifier = Modifier.padding(vertical = 10.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                // 自定义倍速
                Surface(
                    onClick = {
                        customSpeedText = "${currentSpeed}"
                        showSpeedSheet = false
                        showCustomSpeed = true
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = playerSurface(),
                    border = BorderStroke(0.5.dp, playerOutline()),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("自定义倍速", color = playerOnSurface(), fontSize = 14.sp)
                        Spacer(modifier = Modifier.weight(1f))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = playerOnSurfaceVariant(),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }

    // 鈹€鈹€ 閫熷害鑿滃崟 鈹€鈹€

    // ── 片头跳过 ──
    var introApplied by remember { mutableStateOf(false) }
    LaunchedEffect(mediaState.isPlaying) {
        if (!introApplied && mediaState.isPlaying && introSkipSeconds > 0) {
            delay(400) // 如 ExoPlayer 缓冲就绪（含续播 seek 完成�?
            // 当前已在片头之后（续�?/拖拽等场景）如 不跳
            if (player.currentPosition < introSkipSeconds * 1000L) {
                player.seekTo(introSkipSeconds * 1000L)
            }
            introApplied = true
        }
    }

    // ── 片尾跳过 ──
    LaunchedEffect(mediaState.position, mediaState.duration) {
        if (outroSkipSeconds > 0 && mediaState.duration > 0) {
            val outroMs = outroSkipSeconds * 1000L
            val threshold = (mediaState.duration - outroMs).coerceAtLeast(0)
            if (mediaState.position >= threshold && mediaState.position > 0) {
                player.seekToNext()
            }
        }
    }

    // ── 片头片尾设置弹窗 ──
    var showSkipSheet by remember { mutableStateOf(false) }
    var editIntro by remember { mutableIntStateOf(introSkipSeconds) }
    var editOutro by remember { mutableIntStateOf(outroSkipSeconds) }
        if (showSkipSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSkipSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            containerColor = playerSurfaceContainer(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 40.dp),
            ) {
                Text(
                    text = "片头片尾跳过",
                    color = playerOnSurface(),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(16.dp))
                // 内容区用 weight 撑开，确保保存按钮在底部
                Column(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // 片头
                    Column {
                        Text("片头跳过", color = playerOnSurfaceVariant(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(3, 5, 10, 15, 30).forEach { sec ->
                                Surface(
                                    onClick = { editIntro = sec },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (editIntro == sec) playerPrimary() else playerSurface(),
                                    border = if (editIntro == sec) null else BorderStroke(0.5.dp, playerOutline()),
                                ) {
                                    Text(
                                        text = "${sec}s",
                                        color = if (editIntro == sec) playerOnPrimary() else playerOnSurfaceVariant(),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = if (editIntro == 0) "" else editIntro.toString(),
                            onValueChange = { v -> editIntro = v.filter(Char::isDigit).take(4).toIntOrNull() ?: 0 },
                            placeholder = { Text("自定义秒数", color = playerOnSurfaceVariant().copy(alpha = 0.5f)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = playerOnSurface(),
                                unfocusedTextColor = playerOnSurface(),
                                focusedContainerColor = playerSurfaceVariant(),
                                unfocusedContainerColor = playerSurfaceVariant(),
                                focusedBorderColor = playerPrimary(),
                                unfocusedBorderColor = playerOutline(),
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // 片尾
                    Column {
                        Text("片尾跳过", color = playerOnSurfaceVariant(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(3, 5, 10, 15, 30).forEach { sec ->
                                Surface(
                                    onClick = { editOutro = sec },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (editOutro == sec) playerPrimary() else playerSurface(),
                                    border = if (editOutro == sec) null else BorderStroke(0.5.dp, playerOutline()),
                                ) {
                                    Text(
                                        text = "${sec}s",
                                        color = if (editOutro == sec) playerOnPrimary() else playerOnSurfaceVariant(),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = if (editOutro == 0) "" else editOutro.toString(),
                            onValueChange = { v -> editOutro = v.filter(Char::isDigit).take(4).toIntOrNull() ?: 0 },
                            placeholder = { Text("自定义秒数", color = playerOnSurfaceVariant().copy(alpha = 0.5f)) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = playerOnSurface(),
                                unfocusedTextColor = playerOnSurface(),
                                focusedContainerColor = playerSurfaceVariant(),
                                unfocusedContainerColor = playerSurfaceVariant(),
                                focusedBorderColor = playerPrimary(),
                                unfocusedBorderColor = playerOutline(),
                            ),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        onSkipSettingsChanged(editIntro, editOutro)
                        showSkipSheet = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = playerPrimary()),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("保存", color = playerOnPrimary(), fontSize = 16.sp)
                }
            }
        }
    }

    // ── 定时关闭弹窗 ──
        if (showSleepSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSleepSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            containerColor = playerSurfaceContainer(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 40.dp),
            ) {
                Text("定时关闭", color = playerOnSurface(), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Radio 样式选项
                    val sleepOption = when {
                        sleepCustomMins > 0 -> -1
                        sleepRemaining == 15 * 60 -> 15
                        sleepRemaining == 30 * 60 -> 30
                        sleepRemaining == 45 * 60 -> 45
                        sleepRemaining == 60 * 60 -> 60
                        else -> -1
                    }
                    val timerOptions = listOf(15 to "15 分钟", 30 to "30 分钟", 45 to "45 分钟", 60 to "60 分钟")
                    timerOptions.forEach { (mins, label) ->
                        val selected = sleepOption == mins
                        Surface(
                            onClick = {
                                sleepRemaining = mins * 60
                                sleepCustomMins = 0
                                showSleepSheet = false
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) playerPrimary().copy(alpha = 0.08f) else playerSurface(),
                            border = BorderStroke(
                                if (selected) 1.dp else 0.5.dp,
                                if (selected) playerPrimary() else playerOutline(),
                            ),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = label,
                                    color = if (selected) playerPrimary() else playerOnSurface(),
                                    fontSize = 15.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                // Radio circle
                                Surface(
                                    shape = CircleShape,
                                    color = if (selected) playerPrimary() else Color.Transparent,
                                    border = if (!selected) BorderStroke(1.5.dp, playerOutline()) else null,
                                    modifier = Modifier.size(20.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        if (selected) {
                                            Surface(
                                                shape = CircleShape,
                                                color = playerOnPrimary(),
                                                modifier = Modifier.size(8.dp),
                                            ) {}
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text("自定义（分钟）", color = playerOnSurfaceVariant(), fontSize = 13.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = if (sleepCustomMins == 0) "" else sleepCustomMins.toString(),
                        onValueChange = { value ->
                            sleepCustomMins = value.filter(Char::isDigit).take(4).toIntOrNull() ?: 0
                        },
                        placeholder = { Text("输入分钟数", color = playerOnSurfaceVariant().copy(alpha = 0.5f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = playerOnSurface(),
                            unfocusedTextColor = playerOnSurface(),
                            focusedContainerColor = playerSurface(),
                            unfocusedContainerColor = playerSurface(),
                            focusedBorderColor = playerPrimary(),
                            unfocusedBorderColor = playerOutline(),
                        ),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = {
                            if (sleepCustomMins > 0) {
                                sleepRemaining = sleepCustomMins * 60
                                showSleepSheet = false
                            }
                        },
                        enabled = sleepCustomMins > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = playerPrimary()),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("开始", color = playerOnPrimary(), fontSize = 14.sp)
                    }
                }
                if (sleepRemaining > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        onClick = { sleepRemaining = 0; showSleepSheet = false },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = FluxTheme.colorScheme.error.copy(alpha = 0.15f),
                        border = BorderStroke(0.5.dp, FluxTheme.colorScheme.error.copy(alpha = 0.3f)),
                    ) {
                        Text(
                            text = "取消定时",
                            color = FluxTheme.colorScheme.error,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }

    // ── 自定义倍速弹如 ──
    if (showCustomSpeed) {
        AlertDialog(
            onDismissRequest = { showCustomSpeed = false },
            title = { Text("自定义倍速", color = playerOnSurface()) },
            text = {
                OutlinedTextField(
                    value = customSpeedText,
                    onValueChange = { value ->
                        val filtered = value.filter { it.isDigit() || it == '.' }
                        if (filtered.count { it == '.' } <= 1) {
                            customSpeedText = filtered
                        }
                    },
                    placeholder = { Text("输入倍速，如 1.75", color = playerOnSurfaceVariant().copy(alpha = 0.4f)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = playerOnSurface(),
                        unfocusedTextColor = playerOnSurface(),
                        focusedBorderColor = playerPrimary(),
                        unfocusedBorderColor = playerOutline(),
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val speed = customSpeedText.toFloatOrNull()
                        if (speed != null && speed in 0.25f..16f) {
                            player.setPlaybackSpeed(speed)
                            currentSpeed = speed
                            onSpeedChanged(speed)
                            showCustomSpeed = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = playerPrimary()),
                    enabled = customSpeedText.toFloatOrNull()?.let { it in 0.25f..16f } == true,
                ) {
                    Text("确定", color = playerOnPrimary())
                }
            },
            dismissButton = {
                Button(
                    onClick = { showCustomSpeed = false },
                    colors = ButtonDefaults.buttonColors(containerColor = playerSurfaceVariant()),
                ) {
                    Text("取消", color = playerOnSurface())
                }
            },
            containerColor = playerSurfaceContainer(),
        )
    }

    // ── 播放列表弹窗 ──
    if (showPlaylistSheet && chapterNames.isNotEmpty()) {
        ModalBottomSheet(
            onDismissRequest = { showPlaylistSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            containerColor = playerSurfaceContainer(),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "播放列表", color = playerOnSurface(), fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(modifier = Modifier.height(20.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                ) {
                    itemsIndexed(chapterNames) { index, name ->
                        val isActive = index == player.currentMediaItemIndex
                        val progress = localProgress[index]
                        val progressText = if (progress != null && progress.second > 0) {
                            val pct = (progress.first * 100 / progress.second).coerceIn(0, 100)
                            "·已播 $pct%"
                        } else null

                        Surface(
                            onClick = {
                                player.seekToDefaultPosition(index)
                                player.playWhenReady = true
                                showPlaylistSheet = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = if (isActive) playerPrimary().copy(alpha = 0.3f) else Color.Transparent,
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    color = if (isActive) playerPrimary() else playerOnSurfaceVariant(),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.width(28.dp),
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
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }

    val notificationState = remember { FluxNotificationState() }

    // ── UI ──
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(playerBg()),
    ) {
        // 当前章节索引
        val currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val chapterTitle = chapterNames.getOrElse(currentIndex) { "" }
        val hasChapters = chapterNames.isNotEmpty()
        // 书名和章节名相同时只显示一次，避免重复
        val showChapterName = hasChapters && chapterTitle.isNotEmpty() && chapterTitle != title

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 24.dp),
        ) {
            TopBar(onBackClick = onBackClick)

            Spacer(modifier = Modifier.weight(0.2f))

            // ── 封面（缩如 + 加强阴影，形成悬浮感如 ──
            AlbumCover(
                artworkUri = artworkUri,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── 书名 ──
            Text(
                text = title,
                color = playerOnSurface(),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(6.dp))

            // ── 章节如 ──
            if (showChapterName) {
                Text(
                    text = chapterTitle,
                    color = playerOnSurfaceVariant(),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                )
            }

            Spacer(modifier = Modifier.weight(0.25f))

            // ── 底部控件如 ──
            AudioSeekbar(
                position = displayPosition,
                duration = mediaState.duration.toFloat(),
                onSeek = { scrubPosition = it; isScrubbing = true },
                onSeekFinished = {
                    player.seekTo(scrubPosition.toLong())
                    isScrubbing = false
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            TransportRow(player = player, isPlaying = mediaState.isPlaying)

            Spacer(modifier = Modifier.height(20.dp))

            BottomFunctionRow(
                currentSpeed = currentSpeed,
                onSpeedClick = { showSpeedSheet = true },
                onSkipClick = { showSkipSheet = true },
                onSleepClick = { showSleepSheet = true },
                onPlaylistClick = { showPlaylistSheet = true },
                sleepRemaining = sleepRemaining,
            )

            Spacer(modifier = Modifier.height(32.dp))
        }

        if (mediaState.isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
                color = playerOnSurface(),
                strokeWidth = 3.dp,
            )
        }

        FluxNotificationBanner(
            event = notificationState.currentEvent,
            onDismiss = { notificationState.dismiss() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// region ── 子组如 ──

@Composable
private fun TopBar(onBackClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBackClick) {
            Icon(
                painter = painterResource(coreUiR.drawable.ic_arrow_left),
                contentDescription = "返回",
                tint = FluxTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun AlbumCover(artworkUri: Uri?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(280.dp)
            .shadow(20.dp, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .background(FluxTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (artworkUri != null) {
            AsyncImage(
                model = artworkUri,
                contentDescription = "专辑封面",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(coreUiR.drawable.ic_file_audio),
                contentDescription = null,
                tint = FluxTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(56.dp),
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = if (duration > 0f) (position / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { fraction -> onSeek(fraction * duration) },
            onValueChangeFinished = onSeekFinished,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = primary,
                activeTrackColor = primary,
                inactiveTrackColor = onSurface.copy(alpha = 0.15f),
            ),
        )
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
private fun TransportRow(player: Player, isPlaying: Boolean) {
    val playPauseState = androidx.media3.ui.compose.state.rememberPlayPauseButtonState(player)
    val onSurface = FluxTheme.colorScheme.onSurface
    val primary = FluxTheme.colorScheme.primary

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 上一集（轻量级：细边框小圆）
        IconButton(onClick = {
            val idx = player.currentMediaItemIndex
            if (idx > 0) player.seekToDefaultPosition(idx - 1)
            else player.seekTo(0)
        }) {
            Icon(
                painter = painterResource(coreUiR.drawable.ic_skip_prev),
                contentDescription = "上一集?",
                tint = onSurface.copy(alpha = 0.55f),
                modifier = Modifier.size(28.dp),
            )
        }

        // 快退 10s（中级强调：主题色浅如 + 数字标签�?
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(primary.copy(alpha = 0.1f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true),
                ) {
                    player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Replay10,
                contentDescription = "快退10秒?",
                tint = primary,
                modifier = Modifier.size(18.dp),
            )
        }

        // 播放 / 暂停（最高优先级：白色圆形按钮，视觉锚点�?
        Box(
            modifier = Modifier
                .size(96.dp)
                .shadow(6.dp, CircleShape)
                .background(FluxTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = { playPauseState.onClick() },
                modifier = Modifier.size(96.dp),
            ) {
                Icon(
                    painter = painterResource(
                        if (playPauseState.showPlay) coreUiR.drawable.ic_play
                        else coreUiR.drawable.ic_pause,
                    ),
                    contentDescription = if (playPauseState.showPlay) "播放" else "暂停",
                    tint = FluxTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(56.dp),
                )
            }
        }

        // 快进 10s（中级强调：主题色浅如 + 数字标签�?
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(primary.copy(alpha = 0.1f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true),
                ) {
                    player.seekTo((player.currentPosition + 10_000L).coerceAtMost(player.duration))
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Forward10,
                contentDescription = "快进10秒?",
                tint = primary,
                modifier = Modifier.size(18.dp),
            )
        }

        // 下一集（轻量级：细边框小圆）
        IconButton(onClick = {
            val idx = player.currentMediaItemIndex
            if (idx < player.mediaItemCount - 1) player.seekToDefaultPosition(idx + 1)
        }) {
            Icon(
                painter = painterResource(coreUiR.drawable.ic_skip_next),
                contentDescription = "下一集?",
                tint = onSurface.copy(alpha = 0.55f),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun BottomFunctionRow(
    currentSpeed: Float,
    onSpeedClick: () -> Unit,
    onSkipClick: () -> Unit,
    onSleepClick: () -> Unit,
    onPlaylistClick: () -> Unit,
    sleepRemaining: Int,
) {
    val sleepLabel = if (sleepRemaining > 0) {
        val mins = sleepRemaining / 60
        val secs = sleepRemaining % 60
        "${mins}:%02d".format(secs)
    } else "定时"
    val onSurfaceAlpha = FluxTheme.colorScheme.onSurface.copy(alpha = 0.8f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 播放列表
        FunctionButton(
            icon = {
                Icon(
                    imageVector = AutoMirrored.Filled.List,
                    contentDescription = null,
                    tint = onSurfaceAlpha,
                    modifier = Modifier.size(24.dp),
                )
            },
            label = "列表",
            onClick = onPlaylistClick,
        )

        // 定时关闭
        FunctionButton(
            icon = {
                Icon(
                    imageVector = Icons.Outlined.AccessTime,
                    contentDescription = null,
                    tint = if (sleepRemaining > 0) FluxTheme.colorScheme.primary else onSurfaceAlpha,
                    modifier = Modifier.size(24.dp),
                )
            },
            label = sleepLabel,
            onClick = onSleepClick,
        )

        // 倍�?
        FunctionButton(
            icon = {
                Icon(
                    painter = painterResource(coreUiR.drawable.ic_speed),
                    contentDescription = null,
                    tint = onSurfaceAlpha,
                    modifier = Modifier.size(24.dp),
                )
            },
            label = "${currentSpeed}x",
            onClick = onSpeedClick,
        )

        // 片头片尾
        FunctionButton(
            icon = {
                Icon(
                    painter = painterResource(coreUiR.drawable.ic_settings),
                    contentDescription = null,
                    tint = onSurfaceAlpha,
                    modifier = Modifier.size(24.dp),
                )
            },
            label = "跳片头尾",
            onClick = onSkipClick,
        )
    }
}

@Composable
private fun FunctionButton(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            icon()
        }
        Text(
            text = label,
            fontSize = 12.sp,
            color = FluxTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// endregion

// region ── 工具函数 ──

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
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
                        model = coverArtworkUri,
                        contentDescription = "专辑封面",
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
            CircularProgressIndicator(
                modifier = Modifier.size(40.dp),
                color = FluxTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
        }
    }
}

// endregion
