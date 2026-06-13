package com.fluxplayer.app.settings.screens.general

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxplayer.app.core.data.repository.CloudDownloadRepository
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.media.sync.MediaInfoSynchronizer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class GeneralPreferencesViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val mediaInfoSynchronizer: MediaInfoSynchronizer,
    private val cloudDownloadRepository: CloudDownloadRepository,
) : ViewModel() {

    private val uiStateInternal = MutableStateFlow(GeneralPreferencesUiState())
    val uiState = uiStateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect { prefs ->
                uiStateInternal.value = uiStateInternal.value.copy(
                    downloadPath = prefs.downloadPath
                )
            }
        }
    }

    fun onEvent(event: GeneralPreferencesUiEvent) {
        when (event) {
            is GeneralPreferencesUiEvent.ShowDialog -> showDialog(event.value)
            GeneralPreferencesUiEvent.ClearThumbnailCache -> clearThumbnailCache()
            GeneralPreferencesUiEvent.ResetSettings -> resetSettings()
            is GeneralPreferencesUiEvent.ChangeDownloadPath -> changeDownloadPath(event.path)
            GeneralPreferencesUiEvent.ResetDownloadPath -> resetDownloadPath()
        }
    }

    private fun showDialog(value: GeneralPreferencesDialog?) {
        uiStateInternal.value = uiStateInternal.value.copy(showDialog = value)
    }

    private fun clearThumbnailCache() {
        viewModelScope.launch {
            mediaInfoSynchronizer.clearThumbnailsCache()
        }
    }

    private fun resetSettings() {
        viewModelScope.launch {
            preferencesRepository.resetPreferences()
        }
    }

    private fun changeDownloadPath(path: String) {
        viewModelScope.launch {
            cloudDownloadRepository.setDownloadPath(path)
        }
    }

    private fun resetDownloadPath() {
        viewModelScope.launch {
            cloudDownloadRepository.setDownloadPath("/storage/emulated/0/Download/")
        }
    }
}

data class GeneralPreferencesUiState(
    val showDialog: GeneralPreferencesDialog? = null,
    val downloadPath: String = "/storage/emulated/0/Download/",
)

sealed interface GeneralPreferencesDialog {
    data object ClearThumbnailCacheDialog : GeneralPreferencesDialog
    data object ResetSettingsDialog : GeneralPreferencesDialog
    data object ChangeDownloadPathDialog : GeneralPreferencesDialog
}

sealed interface GeneralPreferencesUiEvent {
    data class ShowDialog(val value: GeneralPreferencesDialog?) : GeneralPreferencesUiEvent
    data object ClearThumbnailCache : GeneralPreferencesUiEvent
    data object ResetSettings : GeneralPreferencesUiEvent
    data class ChangeDownloadPath(val path: String) : GeneralPreferencesUiEvent
    data object ResetDownloadPath : GeneralPreferencesUiEvent
}
