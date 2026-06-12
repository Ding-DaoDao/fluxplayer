package com.fluxplayer.app.settings.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxplayer.app.core.common.extensions.isPipFeatureSupported
import com.fluxplayer.app.core.common.extensions.round
import com.fluxplayer.app.core.model.CacheMaxSize
import com.fluxplayer.app.core.model.ControlButtonsPosition
import com.fluxplayer.app.core.model.PlayerPreferences
import com.fluxplayer.app.core.model.Resume
import com.fluxplayer.app.core.model.ScreenOrientation
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.components.CancelButton
import com.fluxplayer.app.core.ui.components.ClickablePreferenceItem
import com.fluxplayer.app.core.ui.components.ListSectionTitle
import com.fluxplayer.app.core.ui.components.NextDialog
import com.fluxplayer.app.core.ui.components.NextDialogWithDoneAndCancelButtons
import com.fluxplayer.app.core.ui.components.NextTopAppBar
import com.fluxplayer.app.core.model.DanmakuSource
import com.fluxplayer.app.settings.screens.player.DanmakuSourceManagerDialog
import com.fluxplayer.app.core.ui.components.PreferenceSlider
import com.fluxplayer.app.core.ui.components.PreferenceSwitch
import com.fluxplayer.app.core.ui.components.PreferenceSwitchWithDivider
import com.fluxplayer.app.core.ui.components.RadioTextButton
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.preview.DayNightPreview
import com.fluxplayer.app.core.ui.theme.NextPlayerTheme
import com.fluxplayer.app.settings.composables.OptionsDialog
import com.fluxplayer.app.settings.extensions.name

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
    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(id = R.string.player_name),
                navigationIcon = {
                    FilledTonalIconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = NextIcons.ArrowBack,
                            contentDescription = stringResource(id = R.string.navigate_up),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(state = rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            ListSectionTitle(text = stringResource(id = R.string.interface_name))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                PreferenceSwitch(
                    title = stringResource(id = R.string.material_you_controls),
                    description = stringResource(id = R.string.material_you_controls_description),
                    icon = NextIcons.Appearance,
                    isChecked = uiState.preferences.useMaterialYouControls,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ToggleUseMaterialYouControls) },
                    isFirstItem = true
                )
                PreferenceSlider(
                    title = stringResource(R.string.controller_timeout),
                    description = stringResource(R.string.seconds, uiState.preferences.controllerAutoHideTimeout),
                    icon = NextIcons.Timer,
                    value = uiState.preferences.controllerAutoHideTimeout.toFloat(),
                    valueRange = 1.0f..60.0f,
                    onValueChange = { onEvent(PlayerPreferencesUiEvent.UpdateControlAutoHideTimeout(it.toInt())) },
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
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                ClickablePreferenceItem(
                    title = stringResource(id = R.string.resume),
                    description = stringResource(id = R.string.resume_description),
                    icon = NextIcons.Resume,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.ResumeDialog)) },
                    isFirstItem = true,
                )
                PreferenceSlider(
                    title = stringResource(id = R.string.default_playback_speed),
                    description = uiState.preferences.defaultPlaybackSpeed.toString(),
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
                PreferenceSwitchWithDivider(
                    title = stringResource(id = R.string.dynamic_long_press_speed),
                    description = stringResource(id = R.string.dynamic_long_press_speed_desc),
                    icon = NextIcons.Tap,
                    isChecked = uiState.preferences.useDynamicLongPressSpeed,
                    onChecked = { onEvent(PlayerPreferencesUiEvent.ToggleUseDynamicLongPressSpeed) },
                    onClick = {
                        onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.DynamicLongPressMultiplierDialog))
                    },
                )
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
                PreferenceSwitch(
                    title = stringResource(id = R.string.background_play),
                    description = stringResource(
                        id = R.string.background_play_description,
                    ),
                    icon = NextIcons.Headset,
                    isChecked = uiState.preferences.autoBackgroundPlay,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ToggleAutoBackgroundPlay) },
                )
                PreferenceSwitch(
                    title = stringResource(id = R.string.remember_brightness_level),
                    description = stringResource(
                        id = R.string.remember_brightness_level_description,
                    ),
                    icon = NextIcons.Brightness,
                    isChecked = uiState.preferences.rememberPlayerBrightness,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ToggleRememberBrightnessLevel) },
                )
                PreferenceSwitch(
                    title = stringResource(id = R.string.remember_selections),
                    description = stringResource(id = R.string.remember_selections_description),
                    icon = NextIcons.Selection,
                    isChecked = uiState.preferences.rememberSelections,
                    onClick = { onEvent(PlayerPreferencesUiEvent.ToggleRememberSelections) },
                )
                ClickablePreferenceItem(
                    title = stringResource(id = R.string.player_screen_orientation),
                    description = uiState.preferences.playerScreenOrientation.name(),
                    icon = NextIcons.Rotation,
                    onClick = {
                        onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.PlayerScreenOrientationDialog))
                    },
                )
                ClickablePreferenceItem(
                    title = "弹幕源管理",
                    description = "${DanmakuSource.filterValid(uiState.preferences.danmakuSources).size} 个弹幕源",
                    icon = NextIcons.Caption,
                    onClick = {
                        onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.DanmakuSourceManagerDialog))
                    },
                )
                ClickablePreferenceItem(
                    title = "本地弹幕目录",
                    description = uiState.preferences.localDanmakuPath,
                    icon = NextIcons.Folder,
                    onClick = {
                        onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.LocalDanmakuPathDialog))
                    },
                    isLastItem = true
                )
            }

            ListSectionTitle(text = stringResource(id = R.string.playback_cache))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                PreferenceSwitch(
                    title = stringResource(id = R.string.playback_cache),
                    description = stringResource(id = R.string.playback_cache_description),
                    icon = NextIcons.Storage,
                    isChecked = uiState.preferences.playbackCacheEnabled,
                    onClick = { onEvent(PlayerPreferencesUiEvent.TogglePlaybackCacheEnabled) },
                    isFirstItem = true,
                )
                if (uiState.preferences.playbackCacheEnabled) {
                    ClickablePreferenceItem(
                        title = stringResource(id = R.string.cache_size),
                        description = uiState.preferences.playbackCacheMaxSize.name(),
                        icon = NextIcons.Settings,
                        onClick = {
                            onEvent(PlayerPreferencesUiEvent.ShowDialog(PlayerPreferenceDialog.CacheMaxSizeDialog))
                        },
                    )
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
        }

        uiState.showDialog?.let { showDialog ->
            when (showDialog) {
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

                PlayerPreferenceDialog.ControlButtonsDialog -> {
                    OptionsDialog(
                        text = stringResource(id = R.string.control_buttons_alignment),
                        onDismissClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(null)) },
                    ) {
                        items(ControlButtonsPosition.entries.toTypedArray()) {
                            RadioTextButton(
                                text = it.name(),
                                selected = it == uiState.preferences.controlButtonsPosition,
                                onClick = {
                                    onEvent(PlayerPreferencesUiEvent.UpdatePreferredControlButtonsPosition(it))
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

                PlayerPreferenceDialog.DynamicLongPressMultiplierDialog -> {
                    var showCustomDialog by remember { mutableStateOf(false) }
                    if (showCustomDialog) {
                        var customMultiplier by remember {
                            mutableFloatStateOf(uiState.preferences.dynamicLongPressMultiplier)
                        }

                        NextDialogWithDoneAndCancelButtons(
                            title = stringResource(R.string.custom_speed_multiplier),
                            onDoneClick = {
                                onEvent(PlayerPreferencesUiEvent.UpdateDynamicLongPressMultiplier(customMultiplier))
                                onEvent(PlayerPreferencesUiEvent.ShowDialog(null))
                            },
                            onDismissClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(null)) },
                            content = {
                                Text(
                                    text = "x$customMultiplier",
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 20.dp),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Slider(
                                    value = customMultiplier,
                                    onValueChange = { customMultiplier = it.round(1) },
                                    valueRange = 1.1f..5.0f,
                                )
                            },
                        )
                    } else {
                        OptionsDialog(
                            text = stringResource(id = R.string.dynamic_speed_multiplier),
                            onDismissClick = { onEvent(PlayerPreferencesUiEvent.ShowDialog(null)) },
                        ) {
                            items(listOf(1.5f, 2.0f, 3.0f).toTypedArray()) { multiplier ->
                                RadioTextButton(
                                    text = "x$multiplier",
                                    selected = (multiplier == uiState.preferences.dynamicLongPressMultiplier),
                                    onClick = {
                                        onEvent(PlayerPreferencesUiEvent.UpdateDynamicLongPressMultiplier(multiplier))
                                        onEvent(PlayerPreferencesUiEvent.ShowDialog(null))
                                    },
                                )
                            }
                            items(arrayOf("custom")) {
                                RadioTextButton(
                                    text = stringResource(id = R.string.custom_speed),
                                    selected = (uiState.preferences.dynamicLongPressMultiplier !in listOf(1.5f, 2.0f, 3.0f)),
                                    onClick = { showCustomDialog = true },
                                )
                            }
                        }
                    }
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
