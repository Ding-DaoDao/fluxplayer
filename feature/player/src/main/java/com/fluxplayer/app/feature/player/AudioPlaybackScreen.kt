package com.fluxplayer.app.feature.player

import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import com.fluxplayer.app.core.ui.R as coreUiR
import com.fluxplayer.app.feature.player.state.MediaPresentationState
import com.fluxplayer.app.feature.player.state.rememberMediaPresentationState
import com.fluxplayer.app.feature.player.state.rememberMetadataState

private val BgTop = Color(0xFFFFFFFF)
private val BgBottom = Color(0xFFF0F4F8)
private val DarkText = Color(0xFF1A1A1A)
private val SubtleText = Color(0xFF666666)
private val TrackBg = Color(0xFFE0E0E0)
private val VinylBlack = Color(0xFF1A1A1A)
private val VinylGroove = Color(0xFF2A2A2A)
private val LabelBlue1 = Color(0xFF4A90D9)
private val LabelBlue2 = Color(0xFF1E5AA8)
private val ArmSilver = Color(0xFFB0B0B0)
private val PivotSilver = Color(0xFF8A8A8A)

@Composable
fun AudioPlaybackScreen(
    player: Player,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val metadataState = rememberMetadataState(player)
    val mediaState = rememberMediaPresentationState(player)

    val title = metadataState.title ?: ""
    val fileSizeBytes = remember(player.currentMediaItem) {
        player.currentMediaItem?.localConfiguration?.uri
            ?.encodedFragment
            ?.substringAfter('|')
            ?.toLongOrNull() ?: 0L
    }

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }
    val displayPosition = if (isScrubbing) scrubPosition else mediaState.position.toFloat()

    // 速度菜单
    val speeds = listOf(0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f)
    var showSpeedMenu by remember { mutableStateOf(false) }
    var currentSpeed by remember { mutableStateOf(player.playbackParameters.speed) }
    LaunchedEffect(player.playbackParameters) { currentSpeed = player.playbackParameters.speed }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom))),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 20.dp),
        ) {
            // ── TopBar ──
            TopBar(onBackClick = onBackClick)

            Spacer(Modifier.weight(0.8f))

            // ── 唱片 + 唱臂 ──
            VinylSection(
                isPlaying = mediaState.isPlaying,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Spacer(Modifier.height(36.dp))

            // ── 歌曲名 ──
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = DarkText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(),
            )

            // ── 文件大小 ──
            if (fileSizeBytes > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = formatFileSize(fileSizeBytes),
                    fontSize = 13.sp,
                    color = SubtleText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.weight(1f))

            // ── 功能行 ──
            FunctionRow(
                currentSpeed = currentSpeed,
                showSpeedMenu = showSpeedMenu,
                onSpeedMenuChange = { showSpeedMenu = it },
                onSpeedSelected = {
                    player.setPlaybackSpeed(it)
                    currentSpeed = it
                },
            )

            Spacer(Modifier.height(20.dp))

            // ── 进度条 ──
            AudioSeekbar(
                position = displayPosition,
                duration = mediaState.duration.toFloat(),
                onSeek = { scrubPosition = it; isScrubbing = true },
                onSeekFinished = {
                    player.seekTo(scrubPosition.toLong())
                    isScrubbing = false
                },
                onSkipForward = {
                    player.seekTo((player.currentPosition + 15_000L).coerceAtMost(player.duration))
                },
            )

            Spacer(Modifier.height(20.dp))

            // ── 控制行 ──
            TransportRow(player = player)

            Spacer(Modifier.height(32.dp))
        }

        // 缓冲指示器
        if (mediaState.isBuffering) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp),
                color = DarkText,
                strokeWidth = 3.dp,
            )
        }
    }
}

@Composable
private fun TopBar(onBackClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onBackClick) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "返回", tint = DarkText)
        }
        IconButton(onClick = { }) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = DarkText)
        }
    }
}

@Composable
private fun VinylSection(isPlaying: Boolean, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "vinyl")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "rotation",
    )
    val tonearmAngle by animateFloatAsState(
        targetValue = if (isPlaying) 25f else 45f,
        animationSpec = tween(600),
        label = "tonearm",
    )

    Box(modifier = modifier.size(320.dp)) {
        // 唱片
        Canvas(
            modifier = Modifier
                .size(280.dp)
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
                .graphicsLayer { rotationZ = if (isPlaying) rotation else 0f },
        ) {
            val r = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            drawCircle(color = VinylBlack, radius = r, center = center)

            for (i in 1..6) {
                drawCircle(
                    color = VinylGroove,
                    radius = r * (0.55f + i * 0.06f),
                    center = center,
                    style = Stroke(width = 0.8f.dp.toPx()),
                )
            }

            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color(0x40FFFFFF), Color.Transparent),
                    center = center, radius = r * 0.8f,
                ),
                radius = r * 0.8f, center = center,
            )

            val labelR = r * 0.48f
            drawCircle(
                brush = Brush.radialGradient(listOf(LabelBlue1, LabelBlue2), center, labelR),
                radius = labelR, center = center,
            )
            drawCircle(
                color = Color.White.copy(0.15f), radius = labelR, center = center,
                style = Stroke(width = 1f.dp.toPx()),
            )
            drawCircle(color = Color(0xFFCCCCCC), radius = r * 0.04f, center = center)
        }

        // 唱臂
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pivotX = size.width * 0.92f
            val pivotY = size.height * 0.05f
            val armLength = size.width * 0.55f

            drawCircle(color = PivotSilver, radius = 12f.dp.toPx(), center = Offset(pivotX, pivotY))
            drawCircle(color = Color(0xFF666666), radius = 5f.dp.toPx(), center = Offset(pivotX, pivotY))

            val angleRad = Math.toRadians(tonearmAngle.toDouble())
            val endX = pivotX - armLength * kotlin.math.sin(angleRad).toFloat()
            val endY = pivotY + armLength * kotlin.math.cos(angleRad).toFloat()

            drawLine(
                color = ArmSilver, start = Offset(pivotX, pivotY), end = Offset(endX, endY),
                strokeWidth = 4f.dp.toPx(), cap = StrokeCap.Round,
            )

            val headLen = 24f.dp.toPx()
            val headAngle = angleRad
            val hx = endX - headLen * kotlin.math.sin(headAngle).toFloat() * 0.5f
            val hy = endY + headLen * kotlin.math.cos(headAngle).toFloat() * 0.5f
            drawLine(
                color = Color(0xFF999999), start = Offset(endX, endY), end = Offset(hx, hy),
                strokeWidth = 7f.dp.toPx(), cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun FunctionRow(
    currentSpeed: Float,
    showSpeedMenu: Boolean,
    onSpeedMenuChange: (Boolean) -> Unit,
    onSpeedSelected: (Float) -> Unit,
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        // 倍速
        Box {
            FunctionButton(
                icon = { Icon(Icons.Default.Speed, contentDescription = null, tint = DarkText, modifier = Modifier.size(24.dp)) },
                label = String.format("%.1gx", currentSpeed),
                onClick = { onSpeedMenuChange(true) },
            )
            DropdownMenu(expanded = showSpeedMenu, onDismissRequest = { onSpeedMenuChange(false) }) {
                listOf(0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f).forEach { speed ->
                    DropdownMenuItem(
                        text = { Text(String.format("%.1gx", speed)) },
                        onClick = { onSpeedSelected(speed); onSpeedMenuChange(false) },
                    )
                }
            }
        }

        // 定时
        FunctionButton(
            icon = { Icon(painterResource(coreUiR.drawable.ic_loop_all), contentDescription = null, tint = DarkText, modifier = Modifier.size(22.dp)) },
            label = "定时",
            onClick = { Toast.makeText(context, "开发中", Toast.LENGTH_SHORT).show() },
        )

        // 下载
        FunctionButton(
            icon = { Icon(painterResource(coreUiR.drawable.ic_playlist), contentDescription = null, tint = DarkText, modifier = Modifier.size(22.dp)) },
            label = "下载",
            onClick = { Toast.makeText(context, "开发中", Toast.LENGTH_SHORT).show() },
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
        modifier = Modifier.let { mod ->
            mod.background(Color.Transparent, shape = CircleShape)
        },
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) { icon() }
        Text(text = label, fontSize = 12.sp, color = DarkText)
    }
}

@Composable
private fun AudioSeekbar(
    position: Float,
    duration: Float,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    onSkipForward: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = if (duration > 0f) (position / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { fraction -> onSeek(fraction * duration) },
            onValueChangeFinished = onSeekFinished,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = DarkText,
                activeTrackColor = DarkText,
                inactiveTrackColor = TrackBg,
            ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = formatTime(position.toLong()), fontSize = 12.sp, color = SubtleText)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = formatTime(duration.toLong()), fontSize = 12.sp, color = SubtleText)
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onSkipForward, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Forward10, contentDescription = "快进15s", tint = SubtleText, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun TransportRow(player: Player) {
    val playPauseState = androidx.media3.ui.compose.state.rememberPlayPauseButtonState(player)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { }) {
            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "播放列表", tint = DarkText, modifier = Modifier.size(24.dp))
        }
        IconButton(onClick = { player.seekToPrevious() }) {
            Icon(painterResource(coreUiR.drawable.ic_skip_prev), contentDescription = "上一首", tint = DarkText, modifier = Modifier.size(28.dp))
        }
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(DarkText, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = { playPauseState.onClick() }, modifier = Modifier.size(64.dp)) {
                Icon(
                    painter = painterResource(
                        if (playPauseState.showPlay) coreUiR.drawable.ic_play else coreUiR.drawable.ic_pause,
                    ),
                    contentDescription = "播放/暂停",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        IconButton(onClick = { player.seekToNext() }) {
            Icon(painterResource(coreUiR.drawable.ic_skip_next), contentDescription = "下一首", tint = DarkText, modifier = Modifier.size(28.dp))
        }
        IconButton(onClick = { }) {
            Icon(Icons.Default.MoreVert, contentDescription = "更多", tint = DarkText, modifier = Modifier.size(24.dp))
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
}
