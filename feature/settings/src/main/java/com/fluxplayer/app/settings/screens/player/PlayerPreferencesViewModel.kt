package com.fluxplayer.app.settings.screens.player

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxplayer.app.core.common.extensions.round
import com.fluxplayer.app.core.data.cache.PlaybackCacheManager
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.model.CacheMaxSize
import com.fluxplayer.app.core.model.DanmakuSource
import com.fluxplayer.app.core.model.PlayerPreferences
import com.fluxplayer.app.core.model.Resume
import com.fluxplayer.app.core.model.ScreenOrientation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class PlayerPreferencesViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val playbackCacheManager: PlaybackCacheManager,
) : ViewModel() {

    private val uiStateInternal = MutableStateFlow(
        PlayerPreferencesUiState(
            preferences = preferencesRepository.playerPreferences.value,
        ),
    )
    val uiState = uiStateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { playbackCacheManager.getCacheSize() }
            uiStateInternal.update { it.copy(cacheSizeBytes = bytes) }
        }
        viewModelScope.launch {
            preferencesRepository.playerPreferences.collect { preferences ->
                uiStateInternal.update { it.copy(preferences = preferences) }
            }
        }
    }

    fun onEvent(event: PlayerPreferencesUiEvent) {
        when (event) {
            is PlayerPreferencesUiEvent.UpdatePreferences -> viewModelScope.launch { preferencesRepository.updatePlayerPreferences { event.transform(it) } }
            is PlayerPreferencesUiEvent.ShowDialog -> showDialog(event.value)
            is PlayerPreferencesUiEvent.UpdatePlaybackResume -> updatePlaybackResume(event.resume)
            PlayerPreferencesUiEvent.ToggleAutoplay -> toggleAutoplay()
            PlayerPreferencesUiEvent.ToggleAutoPip -> toggleAutoPip()
            PlayerPreferencesUiEvent.ToggleAutoBackgroundPlay -> toggleAutoBackgroundPlay()
            PlayerPreferencesUiEvent.ToggleRememberBrightnessLevel -> toggleRememberBrightnessLevel()
            PlayerPreferencesUiEvent.ToggleRememberSelections -> toggleRememberSelections()
            is PlayerPreferencesUiEvent.UpdatePreferredPlayerOrientation -> updatePreferredPlayerOrientation(event.value)
            is PlayerPreferencesUiEvent.UpdateDefaultPlaybackSpeed -> updateDefaultPlaybackSpeed(event.value)
            is PlayerPreferencesUiEvent.UpdateControlAutoHideTimeout -> updateControlAutoHideTimeout(event.value)
            PlayerPreferencesUiEvent.ToggleUseMaterialYouControls -> toggleUseMaterialYouControls()
            is PlayerPreferencesUiEvent.UpdateDanmakuSources -> updateDanmakuSources(event.sources)
            is PlayerPreferencesUiEvent.UpdateLocalDanmakuPath -> updateLocalDanmakuPath(event.path)
            PlayerPreferencesUiEvent.TogglePlaybackCacheEnabled -> togglePlaybackCacheEnabled()
            is PlayerPreferencesUiEvent.UpdatePlaybackCacheMaxSize -> updatePlaybackCacheMaxSize(event.value)
            PlayerPreferencesUiEvent.ClearPlaybackCache -> clearPlaybackCache()
            is PlayerPreferencesUiEvent.UpdateSpeedPresets -> updateSpeedPresets(event.presets)
        }
    }

    private fun updatePreferences(transform: (PlayerPreferences) -> PlayerPreferences) {
        viewModelScope.launch { preferencesRepository.updatePlayerPreferences(transform) }
    }

    private fun showDialog(value: PlayerPreferenceDialog?) {
        uiStateInternal.update {
            it.copy(showDialog = value)
        }
    }

    private fun updatePlaybackResume(resume: Resume) {
        updatePreferences {
            it.copy(
                resume = resume,
            )
        }
    }

    private fun toggleAutoplay() {
        updatePreferences {
            it.copy(autoplay = !it.autoplay)
        }
    }

    private fun toggleAutoPip() {
        updatePreferences {
            it.copy(autoPip = !it.autoPip)
        }
    }

    private fun toggleAutoBackgroundPlay() {
        updatePreferences {
            it.copy(autoBackgroundPlay = !it.autoBackgroundPlay)
        }
    }

    private fun toggleRememberBrightnessLevel() {
        updatePreferences {
            it.copy(rememberPlayerBrightness = !it.rememberPlayerBrightness)
        }
    }

    private fun toggleRememberSelections() {
        updatePreferences {
            it.copy(rememberSelections = !it.rememberSelections)
        }
    }

    private fun updatePreferredPlayerOrientation(value: ScreenOrientation) {
        updatePreferences {
            it.copy(playerScreenOrientation = value)
        }
    }

    private fun updateDefaultPlaybackSpeed(value: Float) {
        updatePreferences {
            it.copy(defaultPlaybackSpeed = value.round(1))
        }
    }

    private fun updateControlAutoHideTimeout(value: Int) {
        updatePreferences {
            it.copy(controllerAutoHideTimeout = value)
        }
    }

    private fun toggleUseMaterialYouControls() {
        updatePreferences {
            it.copy(useMaterialYouControls = !it.useMaterialYouControls)
        }
    }

    private fun updateDanmakuSources(sources: List<DanmakuSource>) {
        updatePreferences {
            it.copy(danmakuSources = sources)
        }
    }

    private fun updateLocalDanmakuPath(path: String) {
        updatePreferences {
            it.copy(localDanmakuPath = path)
        }
    }

    private fun togglePlaybackCacheEnabled() {
        updatePreferences {
            it.copy(playbackCacheEnabled = !it.playbackCacheEnabled)
        }
    }

    private fun updatePlaybackCacheMaxSize(value: CacheMaxSize) {
        updatePreferences {
            it.copy(playbackCacheMaxSize = value)
        }
    }

    private fun clearPlaybackCache() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                playbackCacheManager.clearCache()
                playbackCacheManager.getCacheSize()
            }
            uiStateInternal.update { it.copy(cacheSizeBytes = bytes) }
        }
    }

    fun updateSpeedPresets(presets: List<Float>) {
        updatePreferences {
            it.copy(speedPresets = presets.sorted())
        }
    }
}

@Stable
data class PlayerPreferencesUiState(
    val showDialog: PlayerPreferenceDialog? = null,
    val preferences: PlayerPreferences = PlayerPreferences(),
    val cacheSizeBytes: Long = 0,
)

sealed interface PlayerPreferenceDialog {
    data object DecoderPriorityDialog : PlayerPreferenceDialog
    data object ResumeDialog : PlayerPreferenceDialog
    data object PlayerScreenOrientationDialog : PlayerPreferenceDialog
    data object DanmakuSourceManagerDialog : PlayerPreferenceDialog
    data object LocalDanmakuPathDialog : PlayerPreferenceDialog
    data object CacheMaxSizeDialog : PlayerPreferenceDialog
    data object ClearCacheConfirmDialog : PlayerPreferenceDialog
    data object SpeedPresetsDialog : PlayerPreferenceDialog
}

sealed interface PlayerPreferencesUiEvent {
    data class UpdatePreferences(val transform: (PlayerPreferences) -> PlayerPreferences) : PlayerPreferencesUiEvent
    data class ShowDialog(val value: PlayerPreferenceDialog?) : PlayerPreferencesUiEvent
    data class UpdatePlaybackResume(val resume: Resume) : PlayerPreferencesUiEvent
    data object ToggleAutoplay : PlayerPreferencesUiEvent
    data object ToggleAutoPip : PlayerPreferencesUiEvent
    data object ToggleAutoBackgroundPlay : PlayerPreferencesUiEvent
    data object ToggleRememberBrightnessLevel : PlayerPreferencesUiEvent
    data object ToggleRememberSelections : PlayerPreferencesUiEvent
    data class UpdatePreferredPlayerOrientation(val value: ScreenOrientation) : PlayerPreferencesUiEvent
    data class UpdateDefaultPlaybackSpeed(val value: Float) : PlayerPreferencesUiEvent
    data class UpdateControlAutoHideTimeout(val value: Int) : PlayerPreferencesUiEvent
    data object ToggleUseMaterialYouControls : PlayerPreferencesUiEvent
    data class UpdateDanmakuSources(val sources: List<DanmakuSource>) : PlayerPreferencesUiEvent
    data class UpdateLocalDanmakuPath(val path: String) : PlayerPreferencesUiEvent
    data object TogglePlaybackCacheEnabled : PlayerPreferencesUiEvent
    data class UpdatePlaybackCacheMaxSize(val value: CacheMaxSize) : PlayerPreferencesUiEvent
    data object ClearPlaybackCache : PlayerPreferencesUiEvent
    data class UpdateSpeedPresets(val presets: List<Float>) : PlayerPreferencesUiEvent
}
