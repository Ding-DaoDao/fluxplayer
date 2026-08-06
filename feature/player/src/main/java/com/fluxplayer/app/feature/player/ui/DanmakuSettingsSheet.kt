package com.fluxplayer.app.feature.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import com.fluxplayer.app.core.ui.components.FluxIcon
import com.fluxplayer.app.core.ui.components.FluxIconButton
import com.fluxplayer.app.core.ui.components.FluxText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.fluxplayer.app.core.model.DanmakuConfig
import com.fluxplayer.app.feature.player.R

@Composable
private fun settingsList(): List<DanmakuSetting> = listOf(
    DanmakuSetting(
        name = stringResource(R.string.danmaku_setting_opacity),
        options = DanmakuConfig.OPACITY_OPTIONS,
        valueToString = { it.second },
        currentValue = { it.opacity },
        updateValue = { config, value -> config.copy(opacity = value as Float) },
    ),
    DanmakuSetting(
        name = stringResource(R.string.danmaku_setting_density),
        options = DanmakuConfig.DENSITY_OPTIONS,
        valueToString = { it.second },
        currentValue = { it.density },
        updateValue = { config, value -> config.copy(density = value as Float) },
    ),
    DanmakuSetting(
        name = stringResource(R.string.danmaku_setting_time_offset),
        options = DanmakuConfig.TIME_OFFSET_OPTIONS,
        valueToString = { it.second },
        currentValue = { it.timeOffsetMs },
        updateValue = { config, value -> config.copy(timeOffsetMs = value as Int) },
    ),
    DanmakuSetting(
        name = stringResource(R.string.danmaku_setting_track_spacing),
        options = DanmakuConfig.TRACK_SPACING_OPTIONS,
        valueToString = { it.second },
        currentValue = { it.trackSpacingDp },
        updateValue = { config, value -> config.copy(trackSpacingDp = value as Int) },
    ),
    DanmakuSetting(
        name = stringResource(R.string.danmaku_setting_refresh_rate),
        options = DanmakuConfig.FPS_OPTIONS,
        valueToString = { it.second },
        currentValue = { it.targetFps },
        updateValue = { config, value -> config.copy(targetFps = value as Int) },
    ),
)

private data class DanmakuSetting(
    val name: String,
    val options: List<Pair<Any, String>>,
    val valueToString: (Pair<Any, String>) -> String,
    val currentValue: (DanmakuConfig) -> Any,
    val updateValue: (DanmakuConfig, Any) -> DanmakuConfig,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxScope.DanmakuSettingsSheet(
    config: DanmakuConfig,
    onConfigChange: (DanmakuConfig) -> Unit,
    show: Boolean,
) {
    OverlayView(
        show = show,
        title = stringResource(R.string.danmaku_settings_title),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            settingsList().forEach { setting ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FluxText(
                        text = setting.name,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        setting.options.forEach { option ->
                            val label = setting.valueToString(option)
                            val isSelected = option.first == setting.currentValue(config)
                            val textColor = if (isSelected) Color(0xFF4CAF50) else Color.White

                            Text(
                                text = label,
                                color = textColor,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .clickable { onConfigChange(setting.updateValue(config, option.first)) }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            // 显示区域 — 滑条 不显示(0) ~ 满屏(9)
            DisplayModeSliderRow(
                displayMode = config.displayMode,
                onDisplayModeChange = { onConfigChange(config.copy(displayMode = it)) },
            )

            // 弹幕速度 — 自定义滑条 0.5x ~ 4.0x
            SpeedSliderRow(
                speed = config.speed,
                onSpeedChange = { onConfigChange(config.copy(speed = it)) },
            )

            // 屏蔽关键词管理
            BlockKeywordSection(
                keywords = config.blockKeywords,
                onKeywordsChange = { onConfigChange(config.copy(blockKeywords = it)) },
            )
        }
    }
}

@Composable
private fun DisplayModeSliderRow(
    displayMode: Int,
    onDisplayModeChange: (Int) -> Unit,
) {
    var sliderValue by remember { mutableFloatStateOf(displayMode.toFloat()) }

    val label = when (sliderValue.toInt()) {
        0 -> stringResource(R.string.danmaku_display_hidden)
        9 -> stringResource(R.string.danmaku_display_fullscreen)
        else -> stringResource(R.string.danmaku_display_rows, sliderValue.toInt())
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FluxText(
                text = stringResource(R.string.danmaku_display_area),
                style = MaterialTheme.typography.titleSmall,
            )
            FluxText(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF4CAF50),
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onDisplayModeChange(sliderValue.toInt()) },
            valueRange = 0f..9f,
            steps = 8,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF4CAF50),
                activeTrackColor = Color(0xFF4CAF50),
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SpeedSliderRow(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
) {
    var sliderValue by remember { mutableFloatStateOf(speed) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FluxText(
                text = stringResource(R.string.danmaku_speed),
                style = MaterialTheme.typography.titleSmall,
            )
            FluxText(
                text = "×${"%.2f".format(sliderValue).trimEnd('0').trimEnd('.')}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF4CAF50),
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onSpeedChange(sliderValue) },
            valueRange = 0.5f..4.0f,
            steps = 13, // 0.25 递增：0.5, 0.75, 1.0, ..., 4.0
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF4CAF50),
                activeTrackColor = Color(0xFF4CAF50),
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BlockKeywordSection(
    keywords: List<String>,
    onKeywordsChange: (List<String>) -> Unit,
) {
    var newKeyword by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FluxText(
            text = stringResource(R.string.danmaku_block_keywords),
            style = MaterialTheme.typography.titleSmall,
        )

        // 添加关键词输入
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = newKeyword,
                onValueChange = { newKeyword = it },
                placeholder = {
                    FluxText(
                        text = stringResource(R.string.danmaku_block_keyword_hint),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            FluxIconButton(
                onClick = {
                    val trimmed = newKeyword.trim()
                    if (trimmed.isNotEmpty() && trimmed !in keywords) {
                        onKeywordsChange(keywords + trimmed)
                        newKeyword = ""
                    }
                },
                enabled = newKeyword.trim().isNotEmpty(),
            ) {
                FluxIcon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.danmaku_add_keyword),
                    tint = Color(0xFF4CAF50),
                )
            }
        }

        // 已有关键词列表
        if (keywords.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                keywords.forEach { keyword ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable { onKeywordsChange(keywords - keyword) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        FluxText(
                            text = keyword,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        FluxIcon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.danmaku_remove_keyword, keyword),
                            tint = Color.White.copy(alpha = 0.6f),
                            modifier = Modifier.height(16.dp),
                        )
                    }
                }
            }
        }
    }
}
