package com.fluxplayer.app.feature.player.ui.controls

import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.fluxplayer.app.core.model.VideoContentScale
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.extensions.copy
import com.fluxplayer.app.feature.player.LocalUseMaterialYouControls
import com.fluxplayer.app.feature.player.buttons.LoopButton
import com.fluxplayer.app.feature.player.buttons.PlayerButton
import com.fluxplayer.app.feature.player.buttons.ShuffleButton
import com.fluxplayer.app.feature.player.extensions.drawableRes
import com.fluxplayer.app.feature.player.extensions.noRippleClickable
import com.fluxplayer.app.feature.player.state.MediaPresentationState
import com.fluxplayer.app.feature.player.state.bufferedFraction
import com.fluxplayer.app.feature.player.state.durationFormatted
import com.fluxplayer.app.feature.player.state.pendingPositionFormatted
import com.fluxplayer.app.feature.player.state.positionFormatted
import com.fluxplayer.app.feature.player.ui.QualityOption

@OptIn(UnstableApi::class)
@Composable
fun ControlsBottomView(
    modifier: Modifier = Modifier,
    player: Player,
    mediaPresentationState: MediaPresentationState,
    controlsAlignment: Alignment.Horizontal,
    videoContentScale: VideoContentScale,
    isPipSupported: Boolean,
    onVideoContentScaleClick: () -> Unit,
    onVideoContentScaleLongClick: () -> Unit,
    onLockControlsClick: () -> Unit = {},
    onPictureInPictureClick: () -> Unit,
    onRotateClick: () -> Unit = {},
    onPlaylistClick: () -> Unit = {},
    onSeek: (Long) -> Unit,
    onSeekEnd: () -> Unit,
    qualityOptions: List<QualityOption> = emptyList(),
    currentQualityLabel: String = "",
    onQualitySelected: (QualityOption) -> Unit = {},
    introLabel: String = "",
    onIntroClick: (() -> Unit)? = null,
    onIntroLongClick: (() -> Unit)? = null,
    outroLabel: String = "",
    onOutroClick: (() -> Unit)? = null,
    onOutroLongClick: (() -> Unit)? = null,
    currentSpeed: Float = 1.0f,
    onSpeedSelected: (Float) -> Unit = {},
    onSpeedMenuOpenChanged: (Boolean) -> Unit = {},
) {
    val systemBarsPadding = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
    Column(
        modifier = modifier
            .padding(systemBarsPadding.copy(top = 0.dp))
            .padding(horizontal = 8.dp)
            .padding(top = 16.dp)
            .padding(bottom = 16.dp.takeIf { systemBarsPadding.calculateBottomPadding() == 0.dp } ?: 0.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            var showPendingPosition by rememberSaveable { mutableStateOf(false) }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.noRippleClickable {
                    showPendingPosition = !showPendingPosition
                },
            ) {
                Text(
                    text = when (showPendingPosition) {
                        true -> "-${mediaPresentationState.pendingPositionFormatted}"
                        false -> mediaPresentationState.positionFormatted
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                Text(
                    text = " / ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                Text(
                    text = mediaPresentationState.durationFormatted,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            }
        }
        PlayerSeekbar(
            position = mediaPresentationState.position.toFloat(),
            duration = mediaPresentationState.duration.toFloat(),
            bufferedFraction = mediaPresentationState.bufferedFraction,
            onSeek = { onSeek(it.toLong()) },
            onSeekFinished = { onSeekEnd() },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.Start),
        ) {
            PlayerButton(
                onClick = onVideoContentScaleClick,
                onLongClick = onVideoContentScaleLongClick,
            ) {
                Icon(
                    painter = painterResource(videoContentScale.drawableRes()),
                    contentDescription = null,
                )
            }
            if (isPipSupported) {
                PlayerButton(onClick = onPictureInPictureClick) {
                    Icon(
                        painter = painterResource(R.drawable.ic_pip),
                        contentDescription = null,
                    )
                }
            }
            LoopButton(player = player)
            if (introLabel.isNotEmpty()) {
                IntroOutroButton(
                    label = introLabel,
                    onClick = onIntroClick,
                    onLongClick = onIntroLongClick,
                )
            }
            if (outroLabel.isNotEmpty()) {
                IntroOutroButton(
                    label = outroLabel,
                    onClick = onOutroClick,
                    onLongClick = onOutroLongClick,
                )
            }
            // 播放速度按钮 — 文字标签样式 + DropdownMenu
            var showSpeedMenu by remember { mutableStateOf(false) }
            var showFineTuneDialog by remember { mutableStateOf(false) }
            val presetSpeeds = listOf(0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f)
            LaunchedEffect(showSpeedMenu) {
                onSpeedMenuOpenChanged(showSpeedMenu)
            }
            Box {
                Box(
                    modifier = Modifier
                        .defaultMinSize(minHeight = 48.dp)
                        .noRippleClickable { showSpeedMenu = true }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "x${String.format("%.1f", currentSpeed).trimEnd('0').trimEnd('.')}",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                DropdownMenu(
                    expanded = showSpeedMenu,
                    onDismissRequest = { showSpeedMenu = false },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp)),
                ) {
                    presetSpeeds.forEach { speed ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "x${String.format("%.1f", speed).trimEnd('0').trimEnd('.')}",
                                    color = Color.White,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            onClick = {
                                showSpeedMenu = false
                                onSpeedSelected(speed)
                            },
                        )
                    }
                    // 自定义按钮
                    val isCustomSpeed = currentSpeed !in presetSpeeds
                    val customLabel = if (isCustomSpeed) {
                        "自定义|x${String.format("%.1f", currentSpeed).trimEnd('0').trimEnd('.')}"
                    } else {
                        "自定义"
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = customLabel,
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        onClick = {
                            showSpeedMenu = false
                            showFineTuneDialog = true
                        },
                    )
                }
            }

            // 精细调速弹窗
            if (showFineTuneDialog) {
                FineTuneSpeedDialog(
                    initialSpeed = currentSpeed,
                    onSpeedChanged = { newSpeed ->
                        onSpeedSelected(newSpeed)
                    },
                    onDismiss = { showFineTuneDialog = false },
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            // 清晰度按钮：只有多个可选清晰度时才显示可点击的 DropdownMenu
            if (currentQualityLabel.isNotEmpty()) {
                if (qualityOptions.size > 1) {
                    var showQualityMenu by remember { mutableStateOf(false) }
                    Box {
                        PlayerButton(onClick = { showQualityMenu = true }) {
                            Text(
                                text = currentQualityLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White,
                            )
                        }
                        DropdownMenu(
                            expanded = showQualityMenu,
                            onDismissRequest = { showQualityMenu = false },
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp)),
                        ) {
                            qualityOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = option.label,
                                            color = Color.White,
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    },
                                    onClick = {
                                        showQualityMenu = false
                                        onQualitySelected(option)
                                    },
                                )
                            }
                        }
                    }
                } else {
                    // 只有一个清晰度，只显示标签不可点击
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = currentQualityLabel,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                        )
                    }
                }
            }
            PlayerButton(onClick = onPlaylistClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_playlist),
                    contentDescription = null,
                )
            }
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSeekbar(
    modifier: Modifier = Modifier,
    position: Float,
    duration: Float,
    bufferedFraction: Float = 0f,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        if (LocalUseMaterialYouControls.current) {
            MaterialYouSlider(
                modifier = modifier.fillMaxWidth(),
                value = position,
                valueRange = 0f..duration,
                bufferedFraction = bufferedFraction,
                onValueChange = onSeek,
                onValueChangeFinished = onSeekFinished,
            )
        } else {
            SimpleSlider(
                modifier = modifier.fillMaxWidth(),
                value = position,
                valueRange = 0f..duration,
                bufferedFraction = bufferedFraction,
                onValueChange = onSeek,
                onValueChangeFinished = onSeekFinished,
            )
        }
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaterialYouSlider(
    modifier: Modifier = Modifier,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    bufferedFraction: Float = 0f,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val interactionSource = remember { MutableInteractionSource() }
    val trackHeight = 16.dp
    val thumbWidth = 5.dp
    val trackThumbGapWidth = 12.dp

    Slider(
        value = value,
        valueRange = valueRange,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interactionSource,
        modifier = modifier.size(36.dp),
        track = { sliderState ->
            val disabledAlpha = 0.4f
            val bufferedAlpha = 0.25f

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(trackHeight),
            ) {
                val min = sliderState.valueRange.start
                val max = sliderState.valueRange.endInclusive
                val range = (max - min).takeIf { it > 0f } ?: 1f
                val playedFraction = ((sliderState.value - min) / range).coerceIn(0f, 1f)
                val playedPixels = size.width * playedFraction
                val bufferedPixels = (size.width * bufferedFraction).coerceIn(0f, size.width)

                val endCornerRadius = size.height / 2f
                val insideCornerRadius = 2.dp.toPx()
                val gapHalf = trackThumbGapWidth.toPx() / 2f
                val leftEnd = (playedPixels - gapHalf).coerceIn(0f, size.width)
                val rightStart = (playedPixels + gapHalf).coerceIn(0f, size.width)

                // Layer 1: Full-width inactive background
                drawRoundedRect(
                    offset = Offset(0f, 0f),
                    size = Size(size.width, size.height),
                    color = primaryColor.copy(alpha = disabledAlpha),
                    startCornerRadius = endCornerRadius,
                    endCornerRadius = endCornerRadius,
                )

                // Layer 2: Buffered track (from 0 to bufferedPixels, but not past thumb gap)
                val bufferedEnd = bufferedPixels.coerceAtMost(rightStart)
                if (bufferedEnd > 0f) {
                    drawRoundedRect(
                        offset = Offset(0f, 0f),
                        size = Size(bufferedEnd, size.height),
                        color = primaryColor.copy(alpha = disabledAlpha + bufferedAlpha),
                        startCornerRadius = endCornerRadius,
                        endCornerRadius = if (bufferedEnd >= rightStart) insideCornerRadius else endCornerRadius,
                    )
                }
                // Buffered track on right side of thumb gap
                if (bufferedPixels > rightStart) {
                    val bufferedRightEnd = bufferedPixels.coerceAtMost(size.width)
                    drawRoundedRect(
                        offset = Offset(rightStart, 0f),
                        size = Size(bufferedRightEnd - rightStart, size.height),
                        color = primaryColor.copy(alpha = disabledAlpha + bufferedAlpha),
                        startCornerRadius = insideCornerRadius,
                        endCornerRadius = if (bufferedRightEnd >= size.width) endCornerRadius else insideCornerRadius,
                    )
                }

                // Layer 3: Active played track
                if (leftEnd > 0f) {
                    drawRoundedRect(
                        offset = Offset(0f, 0f),
                        size = Size(leftEnd, size.height),
                        color = primaryColor,
                        startCornerRadius = endCornerRadius,
                        endCornerRadius = insideCornerRadius,
                    )
                }
            }
        },
        thumb = {
            Box(
                modifier = Modifier
                    .width(thumbWidth)
                    .height(28.dp)
                    .background(primaryColor, CircleShape),
            )
        },
    )
}

private fun DrawScope.drawRoundedRect(
    offset: Offset,
    size: Size,
    color: Color,
    startCornerRadius: Float,
    endCornerRadius: Float,
) {
    val startCorner = CornerRadius(startCornerRadius, startCornerRadius)
    val endCorner = CornerRadius(endCornerRadius, endCornerRadius)
    val track = RoundRect(
        rect = Rect(Offset(offset.x, 0f), size = Size(size.width, size.height)),
        topLeft = startCorner,
        topRight = endCorner,
        bottomRight = endCorner,
        bottomLeft = startCorner,
    )
    drawPath(
        path = Path().apply {
            addRoundRect(track)
        },
        color = color,
    )
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleSlider(
    modifier: Modifier = Modifier,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    bufferedFraction: Float = 0f,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Slider(
        value = value,
        valueRange = valueRange,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier.height(44.dp),
        thumb = {
            Box(
                modifier = Modifier.size(22.dp)
                    .shadow(4.dp, CircleShape)
                    .background(Color.White)
            )
        },
        track = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(0.5f))
            ) {
                if (valueRange.endInclusive > 0f) {
                    val fraction = (value / valueRange.endInclusive).coerceIn(0f, 1f)
                    // Buffered layer
                    if (bufferedFraction > fraction) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(bufferedFraction)
                                .height(12.dp)
                                .background(Color.White.copy(0.25f))
                        )
                    }
                    // Played layer
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    )
}

@Composable
private fun IntroOutroButton(
    label: String,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .pointerInput(onClick, onLongClick) {
                detectTapGestures(
                    onTap = { onClick?.invoke() },
                    onLongPress = { onLongClick?.invoke() },
                )
            }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * 精细调速弹窗：左边-，右边+，中间可编辑速度文本，步长 0.1。
 */
@Composable
private fun FineTuneSpeedDialog(
    initialSpeed: Float,
    onSpeedChanged: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var speedText by remember { mutableStateOf(String.format("%.1f", initialSpeed)) }
    val step = 0.1f
    val minSpeed = 0.2f
    val maxSpeed = 5.0f

    fun applySpeed(text: String) {
        val value = text.toFloatOrNull()
        if (value != null && value in minSpeed..maxSpeed) {
            onSpeedChanged(value)
            speedText = String.format("%.1f", value)
        } else {
            // 恢复到当前有效速度
            speedText = String.format("%.1f", initialSpeed)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "调速", color = Color.White)
        },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                // 减号按钮
                PlayerButton(
                    modifier = Modifier.size(48.dp),
                    onClick = {
                        val current = speedText.toFloatOrNull() ?: initialSpeed
                        val newSpeed = ((current - step) * 10).toInt().coerceAtLeast((minSpeed * 10).toInt()) / 10f
                        onSpeedChanged(newSpeed)
                        speedText = String.format("%.1f", newSpeed)
                    },
                ) {
                    Text(text = "-", color = Color.White, style = MaterialTheme.typography.titleLarge)
                }

                Spacer(modifier = Modifier.width(16.dp))

                // 可编辑速度文本
                OutlinedTextField(
                    value = speedText,
                    onValueChange = { newText ->
                        // 只允许数字和小数点
                        val filtered = newText.filter { it.isDigit() || it == '.' }
                        if (filtered.count { it == '.' } <= 1 && filtered.length <= 4) {
                            speedText = filtered
                        }
                    },
                    modifier = Modifier.width(80.dp),
                    textStyle = MaterialTheme.typography.titleLarge.copy(
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    ),
                    singleLine = true,
                )

                Spacer(modifier = Modifier.width(16.dp))

                // 加号按钮
                PlayerButton(
                    modifier = Modifier.size(48.dp),
                    onClick = {
                        val current = speedText.toFloatOrNull() ?: initialSpeed
                        val newSpeed = ((current + step) * 10).toInt().coerceAtMost((maxSpeed * 10).toInt()) / 10f
                        onSpeedChanged(newSpeed)
                        speedText = String.format("%.1f", newSpeed)
                    },
                ) {
                    Text(text = "+", color = Color.White, style = MaterialTheme.typography.titleLarge)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    applySpeed(speedText)
                    onDismiss()
                },
            ) {
                Text(text = "确定", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = Color.White.copy(alpha = 0.7f))
            }
        },
        containerColor = Color.Black.copy(alpha = 0.85f),
    )
}
