package com.fluxplayer.app.settings.screens.gesture

import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxplayer.app.core.common.extensions.round
import com.fluxplayer.app.core.common.extensions.toString
import com.fluxplayer.app.core.model.DoubleTapGesture
import com.fluxplayer.app.core.model.PlayerPreferences
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.components.ListSectionTitle
import com.fluxplayer.app.core.ui.components.NextDialogWithDoneAndCancelButtons
import com.fluxplayer.app.core.ui.components.FluxSettingsScaffold
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
fun GesturePreferencesScreen(
    onNavigateUp: () -> Unit,
    viewModel: GesturePreferencesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    GesturePreferencesContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onNavigateUp = onNavigateUp,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GesturePreferencesContent(
    uiState: GesturePreferencesUiState,
    onEvent: (GesturePreferencesUiEvent) -> Unit,
    onNavigateUp: () -> Unit = {},
) {
    FluxSettingsScaffold(
        title = stringResource(id = R.string.gestures),
        onNavigateUp = onNavigateUp,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(state = rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            ListSectionTitle(text = stringResource(id = R.string.gestures))
            Column {
                PreferenceSwitch(
                    title = stringResource(id = R.string.seek_gesture),
                    description = stringResource(id = R.string.seek_gesture_description),
                    icon = NextIcons.SwipeHorizontal,
                    isChecked = uiState.preferences.useSeekControls,
                    onClick = { onEvent(GesturePreferencesUiEvent.ToggleUseSeekControls) },
                    isFirstItem = true
                )
                HorizontalDivider()
                PreferenceSlider(
                    title = stringResource(R.string.seek_gesture_sensitivity),
                    description = uiState.preferences.seekSensitivity.toString(decimalPlaces = 2),
                    icon = NextIcons.Sensitivity,
                    enabled = uiState.preferences.useSeekControls,
                    value = uiState.preferences.seekSensitivity,
                    valueRange = 0.1f..2.0f,
                    onValueChange = { onEvent(GesturePreferencesUiEvent.UpdateSeekSensitivity(it)) },
                    trailingContent = {
                        FilledIconButton(
                            enabled = uiState.preferences.useSeekControls,
                            onClick = { onEvent(GesturePreferencesUiEvent.UpdateSeekSensitivity(PlayerPreferences.DEFAULT_SEEK_SENSITIVITY)) }
                        ) {
                            Icon(
                                imageVector = NextIcons.History,
                                contentDescription = stringResource(id = R.string.reset_seek_sensitivity),
                            )
                        }
                    },
                )
                HorizontalDivider()
                PreferenceSwitch(
                    title = stringResource(id = R.string.brightness_gesture),
                    description = stringResource(id = R.string.brightness_gesture_description),
                    icon = NextIcons.SwipeVertical,
                    isChecked = uiState.preferences.enableBrightnessSwipeGesture,
                    onClick = { onEvent(GesturePreferencesUiEvent.ToggleEnableBrightnessSwipeGesture) },
                )
                HorizontalDivider()
                PreferenceSlider(
                    title = stringResource(R.string.brightness_gesture_sensitivity),
                    description = uiState.preferences.brightnessGestureSensitivity.toString(decimalPlaces = 2),
                    icon = NextIcons.Sensitivity,
                    enabled = uiState.preferences.enableBrightnessSwipeGesture,
                    value = uiState.preferences.brightnessGestureSensitivity,
                    valueRange = 0.1f..2.0f,
                    onValueChange = { onEvent(GesturePreferencesUiEvent.UpdateBrightnessGestureSensitivity(it)) },
                    trailingContent = {
                        FilledIconButton(
                            enabled = uiState.preferences.enableBrightnessSwipeGesture,
                            onClick = { onEvent(GesturePreferencesUiEvent.UpdateBrightnessGestureSensitivity(PlayerPreferences.DEFAULT_BRIGHTNESS_GESTURE_SENSITIVITY)) }
                        ) {
                            Icon(
                                imageVector = NextIcons.History,
                                contentDescription = stringResource(id = R.string.reset_brightness_gesture_sensitivity),
                            )
                        }
                    },
                )
                HorizontalDivider()
                PreferenceSwitch(
                    title = stringResource(id = R.string.volume_gesture),
                    description = stringResource(id = R.string.volume_gesture_description),
                    icon = NextIcons.SwipeVertical,
                    isChecked = uiState.preferences.enableVolumeSwipeGesture,
                    onClick = { onEvent(GesturePreferencesUiEvent.ToggleEnableVolumeSwipeGesture) },
                )
                HorizontalDivider()
                PreferenceSlider(
                    title = stringResource(R.string.volume_gesture_sensitivity),
                    description = uiState.preferences.volumeGestureSensitivity.toString(decimalPlaces = 2),
                    icon = NextIcons.Sensitivity,
                    enabled = uiState.preferences.enableVolumeSwipeGesture,
                    value = uiState.preferences.volumeGestureSensitivity,
                    valueRange = 0.1f..2.0f,
                    onValueChange = { onEvent(GesturePreferencesUiEvent.UpdateVolumeGestureSensitivity(it)) },
                    trailingContent = {
                        FilledIconButton(
                            enabled = uiState.preferences.enableVolumeSwipeGesture,
                            onClick = { onEvent(GesturePreferencesUiEvent.UpdateVolumeGestureSensitivity(PlayerPreferences.DEFAULT_VOLUME_GESTURE_SENSITIVITY)) }
                        ) {
                            Icon(
                                imageVector = NextIcons.History,
                                contentDescription = stringResource(id = R.string.reset_volume_gesture_sensitivity),
                            )
                        }
                    },
                )
                HorizontalDivider()
                PreferenceSwitch(
                    title = stringResource(id = R.string.zoom_gesture),
                    description = stringResource(id = R.string.zoom_gesture_description),
                    icon = NextIcons.Pinch,
                    isChecked = uiState.preferences.useZoomControls,
                    onClick = { onEvent(GesturePreferencesUiEvent.ToggleUseZoomControls) },
                )
                HorizontalDivider()
                PreferenceSwitch(
                    title = stringResource(id = R.string.pan_gesture),
                    description = stringResource(id = R.string.pan_gesture_description),
                    icon = NextIcons.Pan,
                    enabled = uiState.preferences.useZoomControls,
                    isChecked = uiState.preferences.enablePanGesture,
                    onClick = { onEvent(GesturePreferencesUiEvent.ToggleEnablePanGesture) },
                )
                HorizontalDivider()
                PreferenceSwitchWithDivider(
                    title = stringResource(id = R.string.double_tap),
                    description = stringResource(id = R.string.double_tap_description),
                    icon = NextIcons.DoubleTap,
                    isChecked = (uiState.preferences.doubleTapGesture != DoubleTapGesture.NONE),
                    onChecked = { onEvent(GesturePreferencesUiEvent.ToggleDoubleTapGesture) },
                    onClick = { onEvent(GesturePreferencesUiEvent.ShowDialog(GesturePreferenceDialog.DoubleTapDialog)) },
                )
                HorizontalDivider()
                PreferenceSlider(
                    title = stringResource(R.string.seek_increment),
                    description = stringResource(R.string.seconds, uiState.preferences.seekIncrement),
                    icon = NextIcons.Replay,
                    value = uiState.preferences.seekIncrement.toFloat(),
                    valueRange = 1.0f..60.0f,
                    onValueChange = { onEvent(GesturePreferencesUiEvent.UpdateSeekIncrement(it.toInt())) },
                    trailingContent = {
                        FilledIconButton(onClick = { onEvent(GesturePreferencesUiEvent.UpdateSeekIncrement(PlayerPreferences.DEFAULT_SEEK_INCREMENT)) }) {
                            Icon(
                                imageVector = NextIcons.History,
                                contentDescription = stringResource(id = R.string.reset_seek_increment),
                            )
                        }
                    },
                )
                HorizontalDivider()
                PreferenceSlider(
                    title = stringResource(R.string.haptic_feedback_strength),
                    description = uiState.preferences.hapticFeedbackStrength.toString(decimalPlaces = 2),
                    isLastItem = true,
                    icon = NextIcons.Tap,
                    value = uiState.preferences.hapticFeedbackStrength,
                    valueRange = 0f..1f,
                    onValueChange = { onEvent(GesturePreferencesUiEvent.UpdateHapticFeedbackStrength(it)) },
                    trailingContent = {
                        FilledIconButton(onClick = { onEvent(GesturePreferencesUiEvent.UpdateHapticFeedbackStrength(PlayerPreferences.DEFAULT_HAPTIC_STRENGTH)) }) {
                            Icon(
                                imageVector = NextIcons.History,
                                contentDescription = stringResource(id = R.string.reset_haptic_feedback_strength),
                            )
                        }
                    },
                )
            }
        }

        uiState.showDialog?.let { showDialog ->
            when (showDialog) {
                GesturePreferenceDialog.DoubleTapDialog -> {
                    OptionsDialog(
                        text = stringResource(id = R.string.double_tap),
                        onDismissClick = { onEvent(GesturePreferencesUiEvent.ShowDialog(null)) },
                    ) {
                        items(DoubleTapGesture.entries.toTypedArray()) {
                            RadioTextButton(
                                text = it.name(),
                                selected = (it == uiState.preferences.doubleTapGesture),
                                onClick = {
                                    onEvent(GesturePreferencesUiEvent.UpdateDoubleTapGesture(it))
                                    onEvent(GesturePreferencesUiEvent.ShowDialog(null))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@DayNightPreview
@Composable
private fun GesturePreferencesScreenPreview() {
    NextPlayerTheme {
        GesturePreferencesContent(
            uiState = GesturePreferencesUiState(),
            onEvent = {},
        )
    }
}
