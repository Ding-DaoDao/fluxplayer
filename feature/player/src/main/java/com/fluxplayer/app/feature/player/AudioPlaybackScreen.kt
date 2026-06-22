package com.fluxplayer.app.feature.player

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

// region ── 颜色常量 ──

private val PlayerBg = Color(0xFF111111)
private val TagBg = Color(0x2DFFFFFF)
private val TrackBg = Color(0x40FFFFFF)
private val SubtleWhite = Color(0xCCFFFFFF)
private val BlueAccent = Color(0xFF3B82F6)
private val White = Color.White

// endregion

@Composable
fun AudioPlaybackScreen(
    player: Player,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val metadataState = rememberMetadataState(player)
    val mediaState = rememberMediaPresentationState(player)

    val title = metadataState.title ?: ""

    // 封面图 URI（从 MediaMetadata 或 extras 中提取）
    val artworkUri: Uri? = remember(player.currentMediaItem) {
        player.currentMediaItem?.mediaMetadata?.artworkUri
    }

    // 作者（从 MediaMetadata.artist 中提取）
    val author: String? = remember(player.currentMediaItem) {
        player.currentMediaItem?.mediaMetadata?.artist?.toString()
    }

    // 平台名（从 extras 中取，暂时留空）
    val platform: String? = remember(player.currentMediaItem) {
        player.currentMediaItem?.mediaMetadata?.extras?.getString("platform")
    }

    // 集数标题（从 extras 中取，暂时留空）
    val episodeTitle: String? = remember(player.currentMediaItem) {
        player.currentMediaItem?.mediaMetadata?.extras?.getString("episode_title")
    }

    // ── 拖拽状态 ──
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }
    val displayPosition = if (isScrubbing) scrubPosition else mediaState.position.toFloat()

    // ── 速度菜单 ──
    var showSpeedMenu by remember { mutableStateOf(false) }
    var currentSpeed by remember { mutableStateOf(player.playbackParameters.speed) }
    LaunchedEffect(player.playbackParameters) { currentSpeed = player.playbackParameters.speed }

    val notificationState = remember { FluxNotificationState() }

    // ── UI ──
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PlayerBg),
    ) {
        // 背景层：模糊封面图
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

        // 渐变遮罩：顶部浅暗、底部深暗
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

        // 内容层
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 24.dp),
        ) {
            // ── 顶部导航 ──
            TopBar(onBackClick = onBackClick)

            Spacer(modifier = Modifier.height(24.dp))

            // ── 专辑封面 ──
            AlbumCover(
                artworkUri = artworkUri,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Spacer(modifier = Modifier.height(20.dp))

            // ── AL听书按钮 ──
            AiListenButton(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                onClick = {
                    notificationState.show(FluxMessageEvent.Info("AL听书功能开发中"))
                },
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── 书名 ──
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

            // ── 作者 / 平台标签 ──
            if (author != null || platform != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (author != null) {
                        InfoTag(text = author)
                    }
                    if (author != null && platform != null) {
                        Spacer(modifier = Modifier.width(16.dp))
                    }
                    if (platform != null) {
                        InfoTag(text = platform)
                    }
                }
            }

            // ── 集数标题 ──
            if (!episodeTitle.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = episodeTitle,
                    color = SubtleWhite,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // ── 进度条 ──
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

            // ── 播放控制行 ──
            TransportRow(player = player, isPlaying = mediaState.isPlaying)

            Spacer(modifier = Modifier.height(20.dp))

            // ── 底部功能栏 ──
            BottomFunctionRow(
                currentSpeed = currentSpeed,
                showSpeedMenu = showSpeedMenu,
                onSpeedMenuChange = { showSpeedMenu = it },
                onSpeedSelected = {
                    player.setPlaybackSpeed(it)
                    currentSpeed = it
                },
                notificationState = notificationState,
            )

            Spacer(modifier = Modifier.height(32.dp))
        }

        // 缓冲指示器
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
            // 无封面时显示音频图标占位
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
private fun AiListenButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = BlueAccent,
        modifier = modifier,
    ) {
        Text(
            text = "AL听书",
            color = White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
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
        IconButton(onClick = { player.seekToPrevious() }) {
            Icon(
                painter = painterResource(coreUiR.drawable.ic_skip_prev),
                contentDescription = "上一集",
                tint = SubtleWhite,
                modifier = Modifier.size(24.dp),
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
                modifier = Modifier.size(24.dp),
            )
        }

        // 播放 / 暂停（大圆按钮）
        Box(
            modifier = Modifier
                .size(80.dp)
                .shadow(6.dp, CircleShape)
                .background(White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = { playPauseState.onClick() },
                modifier = Modifier.size(80.dp),
            ) {
                Icon(
                    painter = painterResource(
                        if (playPauseState.showPlay) coreUiR.drawable.ic_play
                        else coreUiR.drawable.ic_pause,
                    ),
                    contentDescription = if (playPauseState.showPlay) "播放" else "暂停",
                    tint = Color(0xFF111111),
                    modifier = Modifier.size(32.dp),
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
                modifier = Modifier.size(24.dp),
            )
        }

        // 下一集
        IconButton(onClick = { player.seekToNext() }) {
            Icon(
                painter = painterResource(coreUiR.drawable.ic_skip_next),
                contentDescription = "下一集",
                tint = SubtleWhite,
                modifier = Modifier.size(24.dp),
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
    notificationState: FluxNotificationState,
) {
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
            onClick = { notificationState.show(FluxMessageEvent.Info("开发中")) },
        )

        // 定时关闭
        FunctionButton(
            icon = {
                Icon(
                    imageVector = Icons.Outlined.AccessTime,
                    contentDescription = null,
                    tint = SubtleWhite,
                    modifier = Modifier.size(24.dp),
                )
            },
            label = "定时",
            onClick = { notificationState.show(FluxMessageEvent.Info("定时关闭开发中")) },
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
                listOf(0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f).forEach { speed ->
                    DropdownMenuItem(
                        text = { Text(String.format("%.1gx", speed)) },
                        onClick = { onSpeedSelected(speed); onSpeedMenuChange(false) },
                    )
                }
            }
        }

        // 设置
        FunctionButton(
            icon = {
                Icon(
                    painter = painterResource(coreUiR.drawable.ic_settings),
                    contentDescription = null,
                    tint = SubtleWhite,
                    modifier = Modifier.size(24.dp),
                )
            },
            label = "设置",
            onClick = { notificationState.show(FluxMessageEvent.Info("设置开发中")) },
        )

        // 收藏
        FunctionButton(
            icon = {
                Icon(
                    imageVector = Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    tint = SubtleWhite,
                    modifier = Modifier.size(24.dp),
                )
            },
            label = "收藏",
            onClick = { notificationState.show(FluxMessageEvent.Info("收藏开发中")) },
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
