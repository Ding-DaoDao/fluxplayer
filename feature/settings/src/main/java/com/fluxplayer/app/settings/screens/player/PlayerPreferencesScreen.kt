package com.fluxplayer.app.settings.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxplayer.app.core.common.extensions.isPipFeatureSupported
import com.fluxplayer.app.core.model.CacheMaxSize
import com.fluxplayer.app.core.model.DanmakuSource
import com.fluxplayer.app.core.model.DecoderPriority
import com.fluxplayer.app.core.model.PlayerPreferences
import com.fluxplayer.app.core.model.Resume
import com.fluxplayer.app.core.model.ScreenOrientation
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.components.CancelButton
import com.fluxplayer.app.core.ui.components.ClickablePreferenceItem
import com.fluxplayer.app.core.ui.components.FluxSettingsScaffold
import com.fluxplayer.app.core.ui.components.ListSectionTitle
import com.fluxplayer.app.core.ui.components.MediaAlertDialog as AlertDialog
import com.fluxplayer.app.core.ui.components.MediaGroupSpacer as HorizontalDivider
import com.fluxplayer.app.core.ui.components.MediaTextField as OutlinedTextField
import com.fluxplayer.app.core.ui.components.NextDialog
import com.fluxplayer.app.core.ui.components.NextDialogWithDoneAndCancelButtons
import com.fluxplayer.app.core.ui.components.PreferenceSlider
import com.fluxplayer.app.core.ui.components.PreferenceSwitch
import com.fluxplayer.app.core.ui.components.RadioTextButton
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.preview.DayNightPreview
import com.fluxplayer.app.core.ui.theme.NextPlayerTheme
import com.fluxplayer.app.settings.composables.OptionsDialog
import com.fluxplayer.app.settings.extensions.name
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PlayerPreferencesScreen(
    onNavigateUp: () -> Unit,
    viewModel: PlayerPreferencesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PlayerPreferencesContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onNavigateUp = onNavigateUp,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlayerPreferencesContent(
    uiState: PlayerPreferencesUiState,
    onEvent: (PlayerPreferencesUiEvent) -> Unit,
    onNavigateUp: () -> Unit = {},
) {
    FluxSettingsScaffold(
        title = stringResource(id = R.string.player_name),
        onNavigateUp = onNavigateUp,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(state = rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            ListSectionTitle(text = stringResource(R.string.advanced_playback))
            Column {
                ClickablePreferenceItem(
                    title = stringResource(R.string.decoder_priority),
                    description = uiState.preferences.decoderPriority.name(),
                    icon = NextIcons.Priority,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.DecoderPriorityDialog)) },
                    isFirstItem = true,
                )
                PreferenceSwitch(
                    title = "隧道播放",
                    description = "交给设备硬件协调音视频同步；需设备和媒体支持",
                    icon = NextIcons.Play,
                    isChecked = uiState.preferences.tunneledPlayback,
                    onClick = { onEvent(PlayerPreferencesUiEvent.UpdatePreferences { it.copy(tunneledPlayback = !it.tunneledPlayback) }) },
                )
                PreferenceSwitch(
                    title = "优先 AAC 音轨",
                    description = "自动选轨时优先 AAC；手动指定的音轨优先",
                    icon = NextIcons.Play,
                    isChecked = uiState.preferences.preferAacAudio,
                    onClick = { onEvent(PlayerPreferencesUiEvent.UpdatePreferences { it.copy(preferAacAudio = !it.preferAacAudio) }) },
                )
                PreferenceSlider(
                    title = "缓冲时长", description = "最多预缓冲 ${uiState.preferences.bufferDurationSeconds} 秒；受原有 64 MB 内存上限约束，重启应用后生效", icon = NextIcons.Timer,
                    value = uiState.preferences.bufferDurationSeconds.toFloat(), valueRange = 10f..120f, steps = 21, saveOnRelease = true,
                    onValueChange = { seconds -> onEvent(PlayerPreferencesUiEvent.UpdatePreferences { it.copy(bufferDurationSeconds = seconds.roundToInt().coerceIn(10, 120)) }) }, isLastItem = true,
                )
            }
            ListSectionTitle(text = stringResource(id = R.string.interface_name))
            Column {
                PreferenceSwitch(
                    title = stringResource(id = R.string.material_you_controls),
                    description = stringResource(id = R.string.material_you_controls_description),
                    icon = NextIcons.Appearance,
                    isChecked = uiState.preferences.useMaterialYouControls,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ToggleUseMaterialYouControls) },
                    isFirstItem = true,
                )
                HorizontalDivider()
                PreferenceSlider(
                    title = stringResource(R.string.controller_timeout),
                    valueDescription = { value -> stringResource(R.string.seconds, value.roundToInt()) },
                    saveOnRelease = true,
                    steps = 58,
                    icon = NextIcons.Timer,
                    value = uiState.preferences.controllerAutoHideTimeout.toFloat(),
                    valueRange = 1.0f..60.0f,
                    onValueChange = { onEvent(PlayerPreferencesUiEvent.UpdateControlAutoHideTimeout(it.roundToInt())) },
                    isLastItem = true,
                    trailingContent = {
                        FilledIconButton(onClick = { onEvent(PlayerPreferencesUiEvent.UpdateControlAutoHideTimeout(PlayerPreferences.DEFAULT_CONTROLLER_AUTO_HIDE_TIMEOUT)) }) {
                            Icon(
                                imageVector = NextIcons.History,
                                contentDescription = stringResource(id = R.string.reset_controller_timeout),
                            )
                        }
                    },
                )
            }

            ListSectionTitle(text = stringResource(id = R.string.playback))
            Column {
                ClickablePreferenceItem(
                    title = stringResource(id = R.string.resume),
                    description = stringResource(if (uiState.preferences.resume == Resume.YES) R.string.resume_enabled_summary else R.string.resume_disabled_summary),
                    icon = NextIcons.Resume,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.ResumeDialog)) },
                    isFirstItem = true,
                )
                HorizontalDivider()
                PreferenceSlider(
                    title = stringResource(id = R.string.default_playback_speed),
                    valueDescription = { value -> stringResource(R.string.playback_speed_value, value) },
                    saveOnRelease = true,
                    steps = 37,
                    icon = NextIcons.Speed,
                    value = uiState.preferences.defaultPlaybackSpeed,
                    valueRange = 0.2f..4.0f,
                    onValueChange = { onEvent(PlayerPreferencesUiEvent.UpdateDefaultPlaybackSpeed(it)) },
                    trailingContent = {
                        FilledIconButton(onClick = { onEvent(PlayerPreferencesUiEvent.UpdateDefaultPlaybackSpeed(1f)) }) {
                            Icon(
                                imageVector = NextIcons.History,
                                contentDescription = stringResource(id = R.string.reset_default_playback_speed),
                            )
                        }
                    },
                )
                HorizontalDivider()
                ClickablePreferenceItem(
                    title = stringResource(R.string.speed_presets),
                    description = uiState.preferences.speedPresets.joinToString(", ") { "${it}x" },
                    icon = NextIcons.Speed,
                    onClick = {
                        onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.SpeedPresetsDialog))
                    },
                )
                HorizontalDivider()
                PreferenceSwitch(
                    title = stringResource(id = R.string.autoplay_settings),
                    description = stringResource(
                        id = R.string.autoplay_settings_description,
                    ),
                    icon = NextIcons.Player,
                    isChecked = uiState.preferences.autoplay,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ToggleAutoplay) },
                )
                if (LocalContext.current.isPipFeatureSupported) {
                    HorizontalDivider()
                    PreferenceSwitch(
                        title = stringResource(id = R.string.pip_settings),
                        description = stringResource(
                            id = R.string.pip_settings_description,
                        ),
                        icon = NextIcons.Pip,
                        isChecked = uiState.preferences.autoPip,
                        onClick = { onEvent(PlayerPreferencesUiEvent.ToggleAutoPip) },
                    )
                }
                HorizontalDivider()
                PreferenceSwitch(
                    title = stringResource(id = R.string.background_play),
                    description = stringResource(
                        id = R.string.background_play_description,
                    ),
                    icon = NextIcons.Headset,
                    isChecked = uiState.preferences.autoBackgroundPlay,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ToggleAutoBackgroundPlay) },
                )
                HorizontalDivider()
                PreferenceSwitch(
                    title = stringResource(id = R.string.remember_brightness_level),
                    description = stringResource(
                        id = R.string.remember_brightness_level_description,
                    ),
                    icon = NextIcons.Brightness,
                    isChecked = uiState.preferences.rememberPlayerBrightness,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ToggleRememberBrightnessLevel) },
                )
                HorizontalDivider()
                PreferenceSwitch(
                    title = stringResource(id = R.string.remember_selections),
                    description = stringResource(id = R.string.remember_selections_description),
                    icon = NextIcons.Selection,
                    isChecked = uiState.preferences.rememberSelections,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ToggleRememberSelections) },
                )
                HorizontalDivider()
                ClickablePreferenceItem(
                    title = stringResource(id = R.string.player_screen_orientation),
                    description = uiState.preferences.playerScreenOrientation.name(),
                    isLastItem = true,
                    icon = NextIcons.Rotation,
                    onClick = {
                        onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.PlayerScreenOrientationDialog))
                    },
                )
            }

            ListSectionTitle(text = stringResource(R.string.danmaku_settings_section))
            Column {
                ClickablePreferenceItem(
                    title = stringResource(R.string.danmaku_sources),
                    isFirstItem = true,
                    description = "${DanmakuSource.filterValid(uiState.preferences.danmakuSources).size} ${stringResource(R.string.danmaku_source_count)}",
                    icon = NextIcons.Caption,
                    onClick = {
                        onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.DanmakuSourceManagerDialog))
                    },
                )
                HorizontalDivider()
                ClickablePreferenceItem(
                    title = stringResource(R.string.local_danmaku_path),
                    description = uiState.preferences.localDanmakuPath.ifBlank { stringResource(R.string.not_set) },
                    icon = NextIcons.Folder,
                    onClick = {
                        onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.LocalDanmakuPathDialog))
                    },
                    isLastItem = true,
                )
            }

            ListSectionTitle(text = stringResource(id = R.string.playback_cache))
            Column {
                PreferenceSwitch(
                    title = stringResource(id = R.string.playback_cache),
                    description = stringResource(id = R.string.playback_cache_description),
                    icon = NextIcons.Storage,
                    isChecked = uiState.preferences.playbackCacheEnabled,
                    onClick = { onEvent(PlayerPreferencesUiEvent.TogglePlaybackCacheEnabled) },
                    isFirstItem = true,
                )
                HorizontalDivider()
                ClickablePreferenceItem(
                    title = stringResource(id = R.string.cache_size),
                    description = if (uiState.preferences.playbackCacheEnabled) {
                        uiState.preferences.playbackCacheMaxSize.name()
                    } else {
                        stringResource(R.string.cache_size_disabled)
                    },
                    enabled = uiState.preferences.playbackCacheEnabled,
                    icon = NextIcons.Settings,
                    onClick = {
                        onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.CacheMaxSizeDialog))
                    },
                )
                HorizontalDivider()
                ClickablePreferenceItem(
                    title = stringResource(id = R.string.clear_cache),
                    description = stringResource(id = R.string.clear_cache_description, formatBytes(uiState.cacheSizeBytes)),
                    icon = NextIcons.DeleteSweep,
                    onClick = {
                        onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.ClearCacheConfirmDialog))
                    },
                    isLastItem = true,
                )
            }
        }

        uiState.showDialog?.let { showDialog ->
            when (showDialog) {
                PlayerPreferenceDialog.DecoderPriorityDialog -> {
                    OptionsDialog(
                        text = stringResource(R.string.decoder_priority),
                        onDismissClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(null)) },
                    ) {
                        items(DecoderPriority.entries) { priority ->
                            RadioTextButton(
                                text = priority.name(),
                                selected = priority == uiState.preferences.decoderPriority,
                                onClick = {
                                    onEvent(PlayerPreferencesUiEvent.UpdatePreferences { it.copy(decoderPriority = priority) })
                                    onEvent(PlayerPreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }
                PlayerPreferenceDialog.ResumeDialog -> {
                    OptionsDialog(
                        text = stringResource(id = R.string.resume),
                        onDismissClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(null)) },
                    ) {
                        items(Resume.entries.toTypedArray()) {
                            RadioTextButton(
                                text = it.name(),
                                selected = (it == uiState.preferences.resume),
                                onClick = {
                                    onEvent(PlayerPreferencesUiEvent.UpdatePlaybackResume(it))
                                    onEvent(PlayerPreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }

                PlayerPreferenceDialog.PlayerScreenOrientationDialog -> {
                    OptionsDialog(
                        text = stringResource(id = R.string.player_screen_orientation),
                        onDismissClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(null)) },
                    ) {
                        items(ScreenOrientation.entries.toTypedArray()) {
                            RadioTextButton(
                                text = it.name(),
                                selected = it == uiState.preferences.playerScreenOrientation,
                                onClick = {
                                    onEvent(PlayerPreferencesUiEvent.UpdatePreferredPlayerOrientation(it))
                                    onEvent(PlayerPreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }

                PlayerPreferenceDialog.DanmakuSourceManagerDialog -> {
                    DanmakuSourceManagerDialog(
                        sources = DanmakuSource.filterValid(uiState.preferences.danmakuSources),
                        onUpdateSources = { sources ->
                            onEvent(PlayerPreferencesUiEvent.UpdateDanmakuSources(DanmakuSource.filterValid(sources)))
                        },
                        onDismiss = {
                            onEvent(PlayerPreferencesUiEvent.ShowDialog(null))
                        },
                    )
                }

                PlayerPreferenceDialog.LocalDanmakuPathDialog -> {
                    LocalDanmakuPathDialog(
                        currentPath = uiState.preferences.localDanmakuPath,
                        onUpdatePath = { path ->
                            onEvent(PlayerPreferencesUiEvent.UpdateLocalDanmakuPath(path))
                            onEvent(PlayerPreferencesUiEvent.ShowDialog(null))
                        },
                        onDismiss = {
                            onEvent(PlayerPreferencesUiEvent.ShowDialog(null))
                        },
                    )
                }

                PlayerPreferenceDialog.CacheMaxSizeDialog -> {
                    OptionsDialog(
                        text = stringResource(id = R.string.cache_size),
                        onDismissClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(null)) },
                    ) {
                        items(CacheMaxSize.entries.toTypedArray()) {
                            RadioTextButton(
                                text = it.name(),
                                selected = it == uiState.preferences.playbackCacheMaxSize,
                                onClick = {
                                    onEvent(PlayerPreferencesUiEvent.UpdatePlaybackCacheMaxSize(it))
                                    onEvent(PlayerPreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }

                PlayerPreferenceDialog.ClearCacheConfirmDialog -> {
                    NextDialog(
                        onDismissRequest = { onEvent(PlayerPreferencesUiEvent.ShowDialog(null)) },
                        title = {
                            Text(
                                text = stringResource(id = R.string.clear_cache),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    onEvent(PlayerPreferencesUiEvent.ClearPlaybackCache)
                                    onEvent(PlayerPreferencesUiEvent.ShowDialog(null))
                                },
                            ) {
                                Text(text = stringResource(id = R.string.confirm))
                            }
                        },
                        dismissButton = { CancelButton(onClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(null)) }) },
                        content = {
                            Text(
                                text = stringResource(id = R.string.clear_cache_confirmation),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        },
                    )
                }

                PlayerPreferenceDialog.SpeedPresetsDialog -> {
                    SpeedPresetsDialog(
                        currentPresets = uiState.preferences.speedPresets,
                        onUpdatePresets = { presets ->
                            onEvent(PlayerPreferencesUiEvent.UpdateSpeedPresets(presets))
                        },
                        onDismiss = {
                            onEvent(PlayerPreferencesUiEvent.ShowDialog(null))
                        },
                    )
                }
            }
        }
    }
}

@DayNightPreview
@Composable
private fun PlayerPreferencesScreenPreview() {
    NextPlayerTheme {
        PlayerPreferencesContent(
            uiState = PlayerPreferencesUiState(),
            onEvent = {},
        )
    }
}

@Composable
fun SpeedPresetsDialog(
    currentPresets: List<Float>,
    onUpdatePresets: (List<Float>) -> Unit,
    onDismiss: () -> Unit,
) {
    var presets by remember(currentPresets) { mutableStateOf(currentPresets.sorted()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newSpeedText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    // onClick 等普通 lambda 中不可调用 stringResource，提前在 Composable 作用域取值。
    val invalidSpeedNumberMessage = stringResource(R.string.invalid_speed_number)
    val speedOutOfRangeMessage = stringResource(R.string.speed_out_of_range)
    val speedAlreadyExistsMessage = stringResource(R.string.speed_already_exists)

    val defaultPresets = listOf(0.5f, 1.0f, 1.5f, 2.0f, 2.5f, 3.0f)

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false
                newSpeedText = ""
                errorMessage = ""
            },
            title = { Text(text = stringResource(R.string.add_speed)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = newSpeedText,
                        onValueChange = {
                            newSpeedText = it
                            errorMessage = ""
                        },
                        label = { Text(stringResource(R.string.speed_value_range_hint)) },
                        singleLine = true,
                        isError = errorMessage.isNotEmpty(),
                    )
                    if (errorMessage.isNotEmpty()) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val speed = newSpeedText.toFloatOrNull()
                        when {
                            speed == null -> errorMessage = invalidSpeedNumberMessage
                            speed < 0.1f || speed > 10.0f -> errorMessage = speedOutOfRangeMessage
                            presets.any { abs(it - speed) < 0.001f } -> errorMessage = speedAlreadyExistsMessage
                            else -> {
                                presets = (presets + speed).sorted()
                                showAddDialog = false
                                newSpeedText = ""
                                errorMessage = ""
                            }
                        }
                    },
                ) { Text(stringResource(R.string.add)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showAddDialog = false
                        newSpeedText = ""
                        errorMessage = ""
                    },
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    NextDialogWithDoneAndCancelButtons(
        title = stringResource(R.string.speed_presets),
        onDoneClick = {
            onUpdatePresets(presets)
            onDismiss()
        },
        onDismissClick = onDismiss,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 当前预设列表
                presets.forEach { speed ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatSpeed(speed),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        TextButton(
                            onClick = {
                                if (presets.size > 1) {
                                    presets = presets.filter { abs(it - speed) > 0.001f }
                                }
                            },
                            enabled = presets.size > 1,
                        ) {
                            Text(
                                text = stringResource(R.string.delete),
                                color = if (presets.size > 1) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                // 添加按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledIconButton(onClick = { showAddDialog = true }) {
                        Icon(painter = painterResource(id = R.drawable.ic_add), contentDescription = stringResource(R.string.add_speed))
                    }
                    TextButton(
                        onClick = { presets = defaultPresets.sorted() },
                    ) {
                        Icon(imageVector = NextIcons.History, contentDescription = stringResource(R.string.restore_defaults))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.restore_defaults))
                    }
                }
            }
        },
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        .coerceIn(0, units.size - 1)
    return String.format(
        "%.1f %s",
        bytes / Math.pow(1024.0, digitGroups.toDouble()),
        units[digitGroups],
    )
}

/**
 * 格式化速度为显示字符串：整数为 "1x"，非整数为 "1.2x"。
 */
private fun formatSpeed(speed: Float): String {
    val rounded = roundToStep(speed)
    return if (rounded == rounded.roundToInt().toFloat()) {
        "${rounded.roundToInt()}x"
    } else {
        val s = "%.2f".format(rounded).trimEnd('0').trimEnd('.')
        "${s}x"
    }
}

/**
 * 把速度四舍五入到 0.05 步长
 */
private fun roundToStep(value: Float): Float {
    return ((value * 20f).roundToInt()) / 20f
}
