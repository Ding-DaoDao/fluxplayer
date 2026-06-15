package com.fluxplayer.app.feature.player.ui.controls

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/** 倍速横条的固定预设档位（从左到右） */
val SPEED_PRESETS: List<Float> = listOf(0.5f, 0.8f, 1.0f, 1.2f, 1.5f, 2.0f, 2.5f, 3.0f)

/**
 * 倍速选择横条：半透明深色背景，水平排列预设档位 + 自定义入口。
 * 点击「自定义」通过 [onCustomClick] 回调弹出独立调节弹窗。
 *
 * @param presets 倍速预设列表，默认使用 [SPEED_PRESETS]
 */
@Composable
fun SpeedBar(
    currentSpeed: Float,
    presets: List<Float> = SPEED_PRESETS,
    onSpeedSelected: (Float) -> Unit,
    onCustomClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isInPresets = currentSpeed in presets

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        presets.forEach { speed ->
            SpeedPresetColumn(
                speed = speed,
                isCurrent = isInPresets && abs(speed - currentSpeed) < 0.001f,
                onClick = { onSpeedSelected(speed) },
            )
        }

        // 竖线分隔
        Spacer(
            modifier = Modifier
                .width(1.dp)
                .height(24.dp)
                .background(Color.White.copy(alpha = 0.4f)),
        )

        // 自定义档位
        Column(
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCustomClick,
                )
                .padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val customText = if (isInPresets) "自定义" else "自定义 ${formatSpeed(currentSpeed)}"
            Text(
                text = customText,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = if (!isInPresets) FontWeight.Bold else FontWeight.Normal,
            )
            SpeedDot(visible = !isInPresets)
        }
    }
}

@Composable
private fun SpeedPresetColumn(
    speed: Float,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = formatSpeed(speed),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
        )
        SpeedDot(visible = isCurrent)
    }
}

/** 当前档下方的白色圆点指示器；不可见时保留占位以保持等高对齐 */
@Composable
private fun SpeedDot(visible: Boolean) {
    Box(
        modifier = Modifier
            .padding(top = 3.dp)
            .size(5.dp)
            .then(
                if (visible) {
                    Modifier.background(Color.White, CircleShape)
                } else {
                    Modifier
                },
            ),
    )
}

// ===== 自定义倍速弹窗 =====

/** 自定义滑块范围 */
private const val MIN_SPEED = 0.2f
private const val MAX_SPEED = 6.0f

/**
 * 自定义倍速调节条：半透明深色横条，含滑块 + 当前值。
 * 固定宽度，点击外部由调用方处理 dismiss。
 */
@Composable
fun CustomSpeedDialog(
    currentSpeed: Float,
    onSpeedChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sliderValue by remember(currentSpeed) { mutableStateOf(currentSpeed) }

    Row(
        modifier = modifier
            .width(320.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${MIN_SPEED}x",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
        )
        Slider(
            value = sliderValue,
            onValueChange = {
                sliderValue = it
                onSpeedChanged(roundToStep(it))
            },
            valueRange = MIN_SPEED..MAX_SPEED,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
        )
        Text(
            text = "${MAX_SPEED}x",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
        )
        Text(
            text = formatSpeed(sliderValue),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

// ===== 工具函数（供 SpeedBar 与倍速按钮共用）=====

/** 把速度四舍五入到 0.05 步长 */
fun roundToStep(value: Float): Float {
    return ((value * 20f).roundToInt()) / 20f
}

/**
 * 格式化速度为显示字符串：整数为 "1x"，非整数为 "1.2x"。
 */
fun formatSpeed(speed: Float): String {
    val rounded = roundToStep(speed)
    return if (rounded == rounded.roundToInt().toFloat()) {
        "${rounded.roundToInt()}x"
    } else {
        val s = "%.2f".format(rounded).trimEnd('0').trimEnd('.')
        "${s}x"
    }
}

/**
 * 计算当前倍速在进度条上的标记比例位置 [0, 1]。
 * 命中预设 → 该预设索引占比；否则在自定义范围内线性映射。
 *
 * @param presets 倍速预设列表，默认使用 [SPEED_PRESETS]
 */
fun speedMarkFraction(currentSpeed: Float, presets: List<Float> = SPEED_PRESETS): Float {
    val idx = presets.indexOfFirst { abs(it - currentSpeed) < 0.001f }
    return if (idx >= 0) {
        if (presets.size > 1) idx.toFloat() / (presets.size - 1) else 0.5f
    } else {
        val minPreset = presets.minOrNull() ?: 0.5f
        val maxPreset = presets.maxOrNull() ?: 3.0f
        ((currentSpeed - minPreset) / (maxPreset - minPreset)).coerceIn(0f, 1f)
    }
}
