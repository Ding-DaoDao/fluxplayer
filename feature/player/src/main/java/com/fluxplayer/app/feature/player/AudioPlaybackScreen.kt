package com.fluxplayer.app.feature.player

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.material.icons.Icons.AutoMirrored
import androidx.compose.material.icons.automirrored.filled.List
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// region ── 颜色常量 ──

private val PlayerBg = Color(0xFF111111)
private val TagBg = Color(0x2DFFFFFF)
private val TrackBg = Color(0x40FFFFFF)
private val SubtleWhite = Color(0xCCFFFFFF)
private val BlueAccent = Color(0xFF3B82F6)
private val White = Color.White

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

    // ── 拖拽状态 ──
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }
    val displayPosition = if (isScrubbing) scrubPosition else mediaState.position.toFloat()

    // ── 速度菜单 ──
    var showSpeedMenu by remember { mutableStateOf(false) }
    var currentSpeed by remember { mutableStateOf(player.playbackParameters.speed) }
    LaunchedEffect(player.playbackParameters) { currentSpeed = player.playbackParameters.speed }

    // ── 自定义倍速 ──
    var showCustomSpeed by remember { mutableStateOf(false) }
    var customSpeedText by remember { mutableStateOf("") }

    // ── 定时关闭 ──
    var sleepRemaining by remember { mutableIntStateOf(0) }  // 剩余秒数，0=未激活
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
            // 用 mediaId 在 chapterPaths 中反查真实章节索引，兜底 currentMediaItemIndex
            val idx = if (mediaId != null && chapterPaths.isNotEmpty()) {
                chapterPaths.indexOf(mediaId).coerceAtLeast(0)
            } else {
                player.currentMediaItemIndex.coerceAtLeast(0)
            }
            val pos = player.currentPosition
            val dur = player.duration
            // duration 未就绪（C.TIME_UNSET）时跳过，避免存入无效数据
            if (dur > 0) {
                onSaveResume(idx, pos, dur)
                // 立即更新本地进度（不等 DataStore 回流）
                localProgress = localProgress + (idx to (pos to dur))
            }
        }
    }

    // ── 播放列表弹窗 ──
    var showPlaylistSheet by remember { mutableStateOf(false) }

    // ── 片头跳过 ──
    var introApplied by remember { mutableStateOf(false) }
    LaunchedEffect(mediaState.isPlaying) {
        if (!introApplied && mediaState.isPlaying && introSkipSeconds > 0) {
            delay(400) // 等 ExoPlayer 缓冲就绪（含续播 seek 完成）
            // 当前已在片头之后（续播/拖拽等场景）→ 不跳
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
            containerColor = Color(0xFF1C1C1E),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 40.dp),
            ) {
                Text(
                    text = "片头片尾跳过",
                    color = White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(20.dp))

                Text("片头跳过（秒）", color = Color(0xFFBBBBBB), fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = if (editIntro == 0) "" else editIntro.toString(),
                    onValueChange = { value ->
                        val digits = value.filter(Char::isDigit).take(5)
                        editIntro = digits.toIntOrNull() ?: 0
                    },
                    placeholder = { Text("跳过片头 N 秒", color = Color(0xFF666666)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = White,
                        unfocusedTextColor = White,
                        focusedContainerColor = Color(0xFF2A2A2A),
                        unfocusedContainerColor = Color(0xFF2A2A2A),
                        focusedBorderColor = BlueAccent,
                        unfocusedBorderColor = Color(0xFF555555),
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text("片尾跳过（秒）", color = Color(0xFFBBBBBB), fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = if (editOutro == 0) "" else editOutro.toString(),
                    onValueChange = { value ->
                        val digits = value.filter(Char::isDigit).take(5)
                        editOutro = digits.toIntOrNull() ?: 0
                    },
                    placeholder = { Text("跳过片尾 N 秒", color = Color(0xFF666666)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = White,
                        unfocusedTextColor = White,
                        focusedContainerColor = Color(0xFF2A2A2A),
                        unfocusedContainerColor = Color(0xFF2A2A2A),
                        focusedBorderColor = BlueAccent,
                        unfocusedBorderColor = Color(0xFF555555),
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = {
                        onSkipSettingsChanged(editIntro, editOutro)
                        showSkipSheet = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("保存", color = White, fontSize = 16.sp)
                }
            }
        }
    }

    // ── 定时关闭弹窗 ──
    if (showSleepSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSleepSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            containerColor = Color(0xFF1C1C1E),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 40.dp),
            ) {
                Text("定时关闭", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(20.dp))
                listOf(15 to "15 分钟", 30 to "30 分钟", 45 to "45 分钟", 60 to "60 分钟").forEach { (mins, label) ->
                    Surface(
                        onClick = {
                            sleepRemaining = mins * 60
                            showSleepSheet = false
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF2A2A2A),
                    ) {
                        Text(
                            text = label,
                            color = White,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(vertical = 14.dp, horizontal = 16.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text("自定义（分钟）", color = Color(0xFFBBBBBB), fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = if (sleepCustomMins == 0) "" else sleepCustomMins.toString(),
                        onValueChange = { value ->
                            sleepCustomMins = value.filter(Char::isDigit).take(4).toIntOrNull() ?: 0
                        },
                        placeholder = { Text("输入分钟数", color = Color(0xFF666666)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = White,
                            unfocusedTextColor = White,
                            focusedContainerColor = Color(0xFF2A2A2A),
                            unfocusedContainerColor = Color(0xFF2A2A2A),
                            focusedBorderColor = BlueAccent,
                            unfocusedBorderColor = Color(0xFF555555),
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
                        colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("开始", color = White, fontSize = 14.sp)
                    }
                }
                if (sleepRemaining > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        onClick = { sleepRemaining = 0; showSleepSheet = false },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF661111),
                    ) {
                        Text(
                            text = "取消定时",
                            color = White,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(vertical = 14.dp, horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }

    // ── 自定义倍速弹窗 ──
    if (showCustomSpeed) {
        AlertDialog(
            onDismissRequest = { showCustomSpeed = false },
            title = { Text("自定义倍速", color = White) },
            text = {
                OutlinedTextField(
                    value = customSpeedText,
                    onValueChange = { value ->
                        val filtered = value.filter { it.isDigit() || it == '.' }
                        if (filtered.count { it == '.' } <= 1) {
                            customSpeedText = filtered
                        }
                    },
                    placeholder = { Text("输入倍速，如 1.75", color = Color(0xFF666666)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = White,
                        unfocusedTextColor = White,
                        focusedBorderColor = BlueAccent,
                        unfocusedBorderColor = Color(0xFF555555),
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
                    colors = ButtonDefaults.buttonColors(containerColor = BlueAccent),
                    enabled = customSpeedText.toFloatOrNull()?.let { it in 0.25f..16f } == true,
                ) {
                    Text("确定", color = White)
                }
            },
            dismissButton = {
                Button(
                    onClick = { showCustomSpeed = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF555555)),
                ) {
                    Text("取消", color = White)
                }
            },
            containerColor = Color(0xFF1C1C1E),
        )
    }

    // ── 播放列表弹窗 ──
    if (showPlaylistSheet && chapterNames.isNotEmpty()) {
        ModalBottomSheet(
            onDismissRequest = { showPlaylistSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            containerColor = Color(0xFF1C1C1E),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "播放列表", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
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
                            color = if (isActive) BlueAccent.copy(alpha = 0.3f) else Color.Transparent,
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    color = if (isActive) BlueAccent else Color(0xFF888888),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.width(28.dp),
                                )
                                Text(
                                    text = name,
                                    color = if (isActive) White else Color(0xFFCCCCCC),
                                    fontSize = 15.sp,
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (progressText != null) {
                                    Text(
                                        text = progressText,
                                        color = if (isActive) BlueAccent else Color(0xFF888888),
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
            .background(PlayerBg),
    ) {
        if (artworkUri != null) {
            AsyncImage(
                model = artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(30.dp)
                    .graphicsLayer { alpha = 0.85f },
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.2f),
                            Color.Black.copy(alpha = 0.6f),
                            Color.Black.copy(alpha = 0.85f),
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 24.dp),
        ) {
            TopBar(onBackClick = onBackClick)

            Spacer(modifier = Modifier.height(24.dp))

            AlbumCover(
                artworkUri = artworkUri,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = title,
                color = White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(),
            )

            Spacer(modifier = Modifier.weight(1f))

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
                showSpeedMenu = showSpeedMenu,
                onSpeedMenuChange = { showSpeedMenu = it },
                onSpeedSelected = {
                    player.setPlaybackSpeed(it)
                    currentSpeed = it
                    onSpeedChanged(it)
                },
                onCustomSpeedClick = {
                    showSpeedMenu = false
                    customSpeedText = "${currentSpeed}"
                    showCustomSpeed = true
                },
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
                color = White,
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

// region ── 子组件 ──

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
                tint = White,
            )
        }
        Text(
            text = "正在播放",
            color = White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun AlbumCover(artworkUri: Uri?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(300.dp)
            .shadow(12.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF2A2A2A)),
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
                tint = Color(0xFF666666),
                modifier = Modifier.size(64.dp),
            )
        }
    }
}

@Composable
private fun InfoTag(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = TagBg,
    ) {
        Text(
            text = text,
            color = White,
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
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = if (duration > 0f) (position / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { fraction -> onSeek(fraction * duration) },
            onValueChangeFinished = onSeekFinished,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = White,
                activeTrackColor = White,
                inactiveTrackColor = TrackBg,
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
                color = SubtleWhite,
            )
            Text(
                text = formatTime(duration.toLong()),
                fontSize = 12.sp,
                color = SubtleWhite,
            )
        }
    }
}

@Composable
private fun TransportRow(player: Player, isPlaying: Boolean) {
    val playPauseState = androidx.media3.ui.compose.state.rememberPlayPauseButtonState(player)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 上一集
        IconButton(onClick = {
            val idx = player.currentMediaItemIndex
            if (idx > 0) player.seekToDefaultPosition(idx - 1)
            else player.seekTo(0)
        }) {
            Icon(
                painter = painterResource(coreUiR.drawable.ic_skip_prev),
                contentDescription = "上一集",
                tint = SubtleWhite,
                modifier = Modifier.size(36.dp),
            )
        }

        // 快退 15s
        IconButton(onClick = {
            player.seekTo((player.currentPosition - 15_000L).coerceAtLeast(0L))
        }) {
            Icon(
                imageVector = Icons.Filled.Replay,
                contentDescription = "快退15秒",
                tint = SubtleWhite,
                modifier = Modifier.size(36.dp),
            )
        }

        // 播放 / 暂停
        Box(
            modifier = Modifier
                .size(96.dp)
                .shadow(6.dp, CircleShape)
                .background(White, CircleShape),
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
                    tint = Color(0xFF111111),
                    modifier = Modifier.size(56.dp),
                )
            }
        }

        // 快进 15s
        IconButton(onClick = {
            player.seekTo((player.currentPosition + 15_000L).coerceAtMost(player.duration))
        }) {
            Icon(
                imageVector = Icons.Filled.Forward10,
                contentDescription = "快进15秒",
                tint = SubtleWhite,
                modifier = Modifier.size(36.dp),
            )
        }

        // 下一集
        IconButton(onClick = {
            val idx = player.currentMediaItemIndex
            if (idx < player.mediaItemCount - 1) player.seekToDefaultPosition(idx + 1)
        }) {
            Icon(
                painter = painterResource(coreUiR.drawable.ic_skip_next),
                contentDescription = "下一集",
                tint = SubtleWhite,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
private fun BottomFunctionRow(
    currentSpeed: Float,
    showSpeedMenu: Boolean,
    onSpeedMenuChange: (Boolean) -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onCustomSpeedClick: () -> Unit,
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
                    tint = SubtleWhite,
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
                    tint = if (sleepRemaining > 0) BlueAccent else SubtleWhite,
                    modifier = Modifier.size(24.dp),
                )
            },
            label = sleepLabel,
            onClick = onSleepClick,
        )

        // 倍速
        Box {
            FunctionButton(
                icon = {
                    Icon(
                        painter = painterResource(coreUiR.drawable.ic_speed),
                        contentDescription = null,
                        tint = SubtleWhite,
                        modifier = Modifier.size(24.dp),
                    )
                },
                label = "${currentSpeed}x",
                onClick = { onSpeedMenuChange(true) },
            )
            DropdownMenu(
                expanded = showSpeedMenu,
                onDismissRequest = { onSpeedMenuChange(false) },
            ) {
                listOf(0.5f to "0.5x", 1.0f to "1x", 1.5f to "1.5x", 2.0f to "2x", 2.5f to "2.5x", 3.0f to "3x").forEach { (speed, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { onSpeedSelected(speed); onSpeedMenuChange(false) },
                    )
                }
                DropdownMenuItem(
                    text = { Text("自定义") },
                    onClick = { onCustomSpeedClick() },
                )
            }
        }

        // 片头片尾
        FunctionButton(
            icon = {
                Icon(
                    painter = painterResource(coreUiR.drawable.ic_settings),
                    contentDescription = null,
                    tint = SubtleWhite,
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
            color = SubtleWhite,
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
 * player 未就绪时的 loading 界面，避免黑屏。
 * 显示模糊封面背景 + 居中进度指示器。
 */
@Composable
fun AudioLoadingScreen(coverArtworkUri: Uri? = null, title: String? = null) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PlayerBg),
    ) {
        if (coverArtworkUri != null) {
            AsyncImage(
                model = coverArtworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(30.dp)
                    .graphicsLayer { alpha = 0.6f },
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.3f),
                            Color.Black.copy(alpha = 0.7f),
                            Color.Black.copy(alpha = 0.9f),
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (coverArtworkUri != null) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .shadow(12.dp, RoundedCornerShape(24.dp))
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF2A2A2A)),
                ) {
                    AsyncImage(
                        model = coverArtworkUri,
                        contentDescription = "专辑封面",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
            // 书名
            if (title != null) {
                Text(
                    text = title,
                    color = Color.White,
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
                color = White,
                strokeWidth = 3.dp,
            )
        }
    }
}

// endregion
