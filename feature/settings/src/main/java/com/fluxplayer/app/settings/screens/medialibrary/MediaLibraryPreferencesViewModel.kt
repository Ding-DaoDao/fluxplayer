package com.fluxplayer.app.settings.screens.medialibrary

import androidx.lifecycle.ViewModel
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class MediaLibraryPreferencesViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    val preferences = preferencesRepository.applicationPreferences
}
