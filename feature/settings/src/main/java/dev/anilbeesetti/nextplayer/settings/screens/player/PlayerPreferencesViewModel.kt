package dev.anilbeesetti.nextplayer.settings.screens.player

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.anilbeesetti.nextplayer.core.common.extensions.round
import dev.anilbeesetti.nextplayer.core.data.cache.PlaybackCacheManager
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.model.CacheMaxSize
import dev.anilbeesetti.nextplayer.core.model.ControlButtonsPosition
import dev.anilbeesetti.nextplayer.core.model.DanmakuSource
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.core.model.Resume
import dev.anilbeesetti.nextplayer.core.model.ScreenOrientation
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class PlayerPreferencesViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val playbackCacheManager: PlaybackCacheManager,
) : ViewModel() {

    private val uiStateInternal = MutableStateFlow(
        PlayerPreferencesUiState(
            preferences = preferencesRepository.playerPreferences.value,
            cacheSizeBytes = playbackCacheManager.getCacheSize(),
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

    fun onEvent(event: PlayerPreferencesUiEvent) {
        when (event) {
            is PlayerPreferencesUiEvent.ShowDialog -> showDialog(event.value)
            is PlayerPreferencesUiEvent.UpdatePlaybackResume -> updatePlaybackResume(event.resume)
            PlayerPreferencesUiEvent.ToggleAutoplay -> toggleAutoplay()
            PlayerPreferencesUiEvent.ToggleAutoPip -> toggleAutoPip()
            PlayerPreferencesUiEvent.ToggleAutoBackgroundPlay -> toggleAutoBackgroundPlay()
            PlayerPreferencesUiEvent.ToggleRememberBrightnessLevel -> toggleRememberBrightnessLevel()
            PlayerPreferencesUiEvent.ToggleRememberSelections -> toggleRememberSelections()
            is PlayerPreferencesUiEvent.UpdatePreferredPlayerOrientation -> updatePreferredPlayerOrientation(event.value)
            is PlayerPreferencesUiEvent.UpdatePreferredControlButtonsPosition -> updatePreferredControlButtonsPosition(event.value)
            is PlayerPreferencesUiEvent.UpdateDefaultPlaybackSpeed -> updateDefaultPlaybackSpeed(event.value)
            is PlayerPreferencesUiEvent.UpdateControlAutoHideTimeout -> updateControlAutoHideTimeout(event.value)
            PlayerPreferencesUiEvent.ToggleUseMaterialYouControls -> toggleUseMaterialYouControls()
            is PlayerPreferencesUiEvent.UpdateDanmakuSources -> updateDanmakuSources(event.sources)
            is PlayerPreferencesUiEvent.UpdateLocalDanmakuPath -> updateLocalDanmakuPath(event.path)
            PlayerPreferencesUiEvent.TogglePlaybackCacheEnabled -> togglePlaybackCacheEnabled()
            is PlayerPreferencesUiEvent.UpdatePlaybackCacheMaxSize -> updatePlaybackCacheMaxSize(event.value)
            PlayerPreferencesUiEvent.ClearPlaybackCache -> clearPlaybackCache()
        }
    }

    private fun showDialog(value: PlayerPreferenceDialog?) {
        uiStateInternal.update {
            it.copy(showDialog = value)
        }
    }

    private fun updatePlaybackResume(resume: Resume) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(
                    resume = resume,
                )
            }
        }
    }

    private fun toggleAutoplay() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(autoplay = !it.autoplay)
            }
        }
    }

    private fun toggleAutoPip() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(autoPip = !it.autoPip)
            }
        }
    }

    private fun toggleAutoBackgroundPlay() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(autoBackgroundPlay = !it.autoBackgroundPlay)
            }
        }
    }

    private fun toggleRememberBrightnessLevel() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(rememberPlayerBrightness = !it.rememberPlayerBrightness)
            }
        }
    }

    private fun toggleRememberSelections() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(rememberSelections = !it.rememberSelections)
            }
        }
    }

    private fun updatePreferredPlayerOrientation(value: ScreenOrientation) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(playerScreenOrientation = value)
            }
        }
    }

    private fun updatePreferredControlButtonsPosition(value: ControlButtonsPosition) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(controlButtonsPosition = value)
            }
        }
    }

    private fun updateDefaultPlaybackSpeed(value: Float) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(defaultPlaybackSpeed = value.round(1))
            }
        }
    }

    private fun updateControlAutoHideTimeout(value: Int) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(controllerAutoHideTimeout = value)
            }
        }
    }

    private fun toggleUseMaterialYouControls() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(useMaterialYouControls = !it.useMaterialYouControls)
            }
        }
    }

    private fun updateDanmakuSources(sources: List<DanmakuSource>) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(danmakuSources = sources)
            }
        }
    }

    private fun updateLocalDanmakuPath(path: String) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(localDanmakuPath = path)
            }
        }
    }

    private fun togglePlaybackCacheEnabled() {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(playbackCacheEnabled = !it.playbackCacheEnabled)
            }
        }
    }

    private fun updatePlaybackCacheMaxSize(value: CacheMaxSize) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(playbackCacheMaxSize = value)
            }
        }
    }

    private fun clearPlaybackCache() {
        playbackCacheManager.clearCache()
        uiStateInternal.update { it.copy(cacheSizeBytes = 0) }
    }
}

@Stable
data class PlayerPreferencesUiState(
    val showDialog: PlayerPreferenceDialog? = null,
    val preferences: PlayerPreferences = PlayerPreferences(),
    val cacheSizeBytes: Long = 0,
)

sealed interface PlayerPreferenceDialog {
    data object ResumeDialog : PlayerPreferenceDialog
    data object PlayerScreenOrientationDialog : PlayerPreferenceDialog
    data object ControlButtonsDialog : PlayerPreferenceDialog
    data object DanmakuSourceManagerDialog : PlayerPreferenceDialog
    data object LocalDanmakuPathDialog : PlayerPreferenceDialog
    data object CacheMaxSizeDialog : PlayerPreferenceDialog
    data object ClearCacheConfirmDialog : PlayerPreferenceDialog
}

sealed interface PlayerPreferencesUiEvent {
    data class ShowDialog(val value: PlayerPreferenceDialog?) : PlayerPreferencesUiEvent
    data class UpdatePlaybackResume(val resume: Resume) : PlayerPreferencesUiEvent
    data object ToggleAutoplay : PlayerPreferencesUiEvent
    data object ToggleAutoPip : PlayerPreferencesUiEvent
    data object ToggleAutoBackgroundPlay : PlayerPreferencesUiEvent
    data object ToggleRememberBrightnessLevel : PlayerPreferencesUiEvent
    data object ToggleRememberSelections : PlayerPreferencesUiEvent
    data class UpdatePreferredPlayerOrientation(val value: ScreenOrientation) : PlayerPreferencesUiEvent
    data class UpdatePreferredControlButtonsPosition(val value: ControlButtonsPosition) : PlayerPreferencesUiEvent
    data class UpdateDefaultPlaybackSpeed(val value: Float) : PlayerPreferencesUiEvent
    data class UpdateControlAutoHideTimeout(val value: Int) : PlayerPreferencesUiEvent
    data object ToggleUseMaterialYouControls : PlayerPreferencesUiEvent
    data class UpdateDanmakuSources(val sources: List<DanmakuSource>) : PlayerPreferencesUiEvent
    data class UpdateLocalDanmakuPath(val path: String) : PlayerPreferencesUiEvent
    data object TogglePlaybackCacheEnabled : PlayerPreferencesUiEvent
    data class UpdatePlaybackCacheMaxSize(val value: CacheMaxSize) : PlayerPreferencesUiEvent
    data object ClearPlaybackCache : PlayerPreferencesUiEvent
}
