package com.fluxplayer.app.settings.screens.gesture

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxplayer.app.core.common.extensions.round
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.model.DoubleTapGesture
import com.fluxplayer.app.core.model.PlayerPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class GesturePreferencesViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val uiStateInternal = MutableStateFlow(
        GesturePreferencesUiState(
            preferences = preferencesRepository.playerPreferences.value,
        ),
    )
    val uiState = uiStateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.playerPreferences.collect { preferences ->
                uiStateInternal.update { it.copy(preferences = preferences) }
            }
        }
    }

    fun onEvent(event: GesturePreferencesUiEvent) {
        when (event) {
            is GesturePreferencesUiEvent.ShowDialog -> showDialog(event.value)
            is GesturePreferencesUiEvent.UpdateDoubleTapGesture -> updateDoubleTapGesture(event.gesture)
            GesturePreferencesUiEvent.ToggleDoubleTapGesture -> toggleDoubleTapGesture()
            GesturePreferencesUiEvent.ToggleEnableBrightnessSwipeGesture -> toggleEnableBrightnessSwipeGesture()
            GesturePreferencesUiEvent.ToggleEnableVolumeSwipeGesture -> toggleEnableVolumeSwipeGesture()
            GesturePreferencesUiEvent.ToggleUseSeekControls -> toggleUseSeekControls()
            GesturePreferencesUiEvent.ToggleUseZoomControls -> toggleUseZoomControls()
            GesturePreferencesUiEvent.ToggleEnablePanGesture -> toggleEnablePanGesture()
            GesturePreferencesUiEvent.ToggleUseDynamicLongPressSpeed -> toggleUseDynamicLongPressSpeed()
            is GesturePreferencesUiEvent.UpdateDynamicLongPressMultiplier -> updateDynamicLongPressMultiplier(event.value)
            is GesturePreferencesUiEvent.UpdateLongPressControlsSpeed -> updateLongPressControlsSpeed(event.value)
            is GesturePreferencesUiEvent.UpdateSeekIncrement -> updateSeekIncrement(event.value)
            is GesturePreferencesUiEvent.UpdateSeekSensitivity -> updateSeekSensitivity(event.value)
            is GesturePreferencesUiEvent.UpdateVolumeGestureSensitivity -> updateVolumeGestureSensitivity(event.value)
            is GesturePreferencesUiEvent.UpdateBrightnessGestureSensitivity -> updateBrightnessGestureSensitivity(event.value)
            is GesturePreferencesUiEvent.UpdateHapticFeedbackStrength -> updateHapticFeedbackStrength(event.value)
        }
    }

    private fun updatePreferences(transform: (PlayerPreferences) -> PlayerPreferences) {
        viewModelScope.launch { preferencesRepository.updatePlayerPreferences(transform) }
    }

    private fun showDialog(value: GesturePreferenceDialog?) {
        uiStateInternal.update {
            it.copy(showDialog = value)
        }
    }

    private fun updateDoubleTapGesture(gesture: DoubleTapGesture) {
        updatePreferences {
            it.copy(doubleTapGesture = gesture)
        }
    }

    private fun toggleDoubleTapGesture() {
        updatePreferences {
            it.copy(
                doubleTapGesture = if (it.doubleTapGesture == DoubleTapGesture.NONE) {
                    DoubleTapGesture.FAST_FORWARD_AND_REWIND
                } else {
                    DoubleTapGesture.NONE
                },
            )
        }
    }

    private fun toggleEnableBrightnessSwipeGesture() {
        updatePreferences {
            it.copy(enableBrightnessSwipeGesture = !it.enableBrightnessSwipeGesture)
        }
    }

    private fun toggleEnableVolumeSwipeGesture() {
        updatePreferences {
            it.copy(enableVolumeSwipeGesture = !it.enableVolumeSwipeGesture)
        }
    }

    private fun toggleUseSeekControls() {
        updatePreferences {
            it.copy(useSeekControls = !it.useSeekControls)
        }
    }

    private fun toggleUseZoomControls() {
        updatePreferences {
            it.copy(useZoomControls = !it.useZoomControls)
        }
    }

    private fun toggleEnablePanGesture() {
        updatePreferences {
            it.copy(enablePanGesture = !it.enablePanGesture)
        }
    }

    private fun toggleUseDynamicLongPressSpeed() {
        updatePreferences {
            it.copy(useDynamicLongPressSpeed = !it.useDynamicLongPressSpeed)
        }
    }

    private fun updateDynamicLongPressMultiplier(value: Float) {
        updatePreferences {
            it.copy(dynamicLongPressMultiplier = value.coerceIn(1.1f, 5.0f).round(1))
        }
    }

    private fun updateLongPressControlsSpeed(value: Float) {
        updatePreferences { it.copy(longPressControlsSpeed = value.coerceIn(1.5f, 4.0f).round(1)) }
    }

    private fun updateSeekIncrement(value: Int) {
        updatePreferences {
            it.copy(seekIncrement = value)
        }
    }

    private fun updateSeekSensitivity(value: Float) {
        updatePreferences {
            it.copy(seekSensitivity = value.round(2))
        }
    }

    private fun updateVolumeGestureSensitivity(value: Float) {
        updatePreferences {
            it.copy(volumeGestureSensitivity = value.round(2))
        }
    }

    private fun updateBrightnessGestureSensitivity(value: Float) {
        updatePreferences {
            it.copy(brightnessGestureSensitivity = value.round(2))
        }
    }

    private fun updateHapticFeedbackStrength(value: Float) {
        updatePreferences {
            it.copy(hapticFeedbackStrength = value.round(2))
        }
    }
}

@Stable
data class GesturePreferencesUiState(
    val showDialog: GesturePreferenceDialog? = null,
    val preferences: PlayerPreferences = PlayerPreferences(),
)

sealed interface GesturePreferenceDialog {
    data object DynamicLongPressMultiplierDialog : GesturePreferenceDialog
    data object DoubleTapDialog : GesturePreferenceDialog
}

sealed interface GesturePreferencesUiEvent {
    data class ShowDialog(val value: GesturePreferenceDialog?) : GesturePreferencesUiEvent
    data class UpdateDoubleTapGesture(val gesture: DoubleTapGesture) : GesturePreferencesUiEvent
    data object ToggleDoubleTapGesture : GesturePreferencesUiEvent
    data object ToggleEnableBrightnessSwipeGesture : GesturePreferencesUiEvent
    data object ToggleEnableVolumeSwipeGesture : GesturePreferencesUiEvent
    data object ToggleUseSeekControls : GesturePreferencesUiEvent
    data object ToggleUseZoomControls : GesturePreferencesUiEvent
    data object ToggleEnablePanGesture : GesturePreferencesUiEvent
    data object ToggleUseDynamicLongPressSpeed : GesturePreferencesUiEvent
    data class UpdateDynamicLongPressMultiplier(val value: Float) : GesturePreferencesUiEvent
    data class UpdateLongPressControlsSpeed(val value: Float) : GesturePreferencesUiEvent
    data class UpdateSeekIncrement(val value: Int) : GesturePreferencesUiEvent
    data class UpdateSeekSensitivity(val value: Float) : GesturePreferencesUiEvent
    data class UpdateVolumeGestureSensitivity(val value: Float) : GesturePreferencesUiEvent
    data class UpdateBrightnessGestureSensitivity(val value: Float) : GesturePreferencesUiEvent
    data class UpdateHapticFeedbackStrength(val value: Float) : GesturePreferencesUiEvent
}
