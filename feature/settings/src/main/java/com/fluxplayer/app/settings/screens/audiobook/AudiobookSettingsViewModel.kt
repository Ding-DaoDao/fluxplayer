package com.fluxplayer.app.settings.screens.audiobook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class AudiobookSettingsViewModel @Inject constructor(private val preferences: PreferencesRepository) : ViewModel() {
    val preferencesState = preferences.applicationPreferences

    fun setRootUri(uri: String) {
        viewModelScope.launch {
            preferences.updateApplicationPreferences { it.copy(audiobookRootUri = uri) }
        }
    }
}
