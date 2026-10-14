package com.fluxplayer.app.settings.screens.appearance

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.model.NavStyle
import com.fluxplayer.app.core.model.StartupPage
import com.fluxplayer.app.core.model.ThemeConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class AppearancePreferencesViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    private val uiStateInternal = MutableStateFlow(
        AppearancePreferencesUiState(
            preferences = preferencesRepository.applicationPreferences.value,
        ),
    )
    val uiState = uiStateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect { preferences ->
                uiStateInternal.update { it.copy(preferences = preferences) }
            }
        }
    }

    fun onEvent(event: AppearancePreferencesEvent) {
        when (event) {
            is AppearancePreferencesEvent.UpdatePreferences -> viewModelScope.launch {
                preferencesRepository.updateApplicationPreferences(event.transform)
            }

            is AppearancePreferencesEvent.ShowDialog -> showDialog(event.value)
            is AppearancePreferencesEvent.UpdateThemeConfig -> updateThemeConfig(event.themeConfig)
            is AppearancePreferencesEvent.UpdateNavStyle -> updateNavStyle(event.navStyle)
            AppearancePreferencesEvent.ToggleShowVideosTab -> toggleShowVideosTab()
            AppearancePreferencesEvent.ToggleShowBrowseTab -> toggleShowBrowseTab()
            AppearancePreferencesEvent.ToggleShowHistoryTab -> toggleShowHistoryTab()
            AppearancePreferencesEvent.ToggleShowAudiobookTab -> toggleShowAudiobookTab()
            is AppearancePreferencesEvent.UpdateStartupPage -> updateStartupPage(event.startupPage)
        }
    }

    private fun showDialog(value: AppearancePreferenceDialog?) {
        uiStateInternal.update {
            it.copy(showDialog = value)
        }
    }

    private fun updateThemeConfig(themeConfig: ThemeConfig) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(themeConfig = themeConfig)
            }
        }
    }

    private fun updateNavStyle(navStyle: NavStyle) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { it.copy(navStyle = navStyle) }
        }
    }

    private fun toggleShowVideosTab() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                ensureValidStartupPage(it.copy(showVideosTab = !it.showVideosTab))
            }
        }
    }

    private fun toggleShowBrowseTab() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                ensureValidStartupPage(it.copy(showBrowseTab = !it.showBrowseTab))
            }
        }
    }

    private fun toggleShowHistoryTab() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                ensureValidStartupPage(it.copy(showHistoryTab = !it.showHistoryTab))
            }
        }
    }

    private fun toggleShowAudiobookTab() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                ensureValidStartupPage(it.copy(showAudiobookTab = !it.showAudiobookTab))
            }
        }
    }

    /**
     * 启动页必须指向仍可见的 tab：当前启动页被隐藏时，自动回退到第一个可见 tab。
     */
    private fun ensureValidStartupPage(preferences: ApplicationPreferences): ApplicationPreferences {
        val visiblePages = buildList {
            if (preferences.showVideosTab) add(StartupPage.VIDEOS)
            if (preferences.showBrowseTab) add(StartupPage.BROWSE)
            if (preferences.showHistoryTab) add(StartupPage.HISTORY)
            if (preferences.showAudiobookTab) add(StartupPage.AUDIOBOOK)
        }
        if (visiblePages.isEmpty()) return preferences.copy(showVideosTab = true, startupPage = StartupPage.VIDEOS)
        val startupPage = preferences.startupPage
            .takeIf { it in visiblePages }
            ?: visiblePages.firstOrNull()
            ?: StartupPage.VIDEOS
        return preferences.copy(startupPage = startupPage)
    }

    private fun updateStartupPage(startupPage: StartupPage) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                when (startupPage) {
                    StartupPage.VIDEOS -> it.copy(startupPage = startupPage, showVideosTab = true)
                    StartupPage.BROWSE -> it.copy(startupPage = startupPage, showBrowseTab = true)
                    StartupPage.HISTORY -> it.copy(startupPage = startupPage, showHistoryTab = true)
                    StartupPage.AUDIOBOOK -> it.copy(startupPage = startupPage, showAudiobookTab = true)
                }
            }
        }
    }
}

@Stable
data class AppearancePreferencesUiState(
    val showDialog: AppearancePreferenceDialog? = null,
    val preferences: ApplicationPreferences = ApplicationPreferences(),
)

sealed interface AppearancePreferencesEvent {
    data class UpdatePreferences(val transform: (ApplicationPreferences) -> ApplicationPreferences) : AppearancePreferencesEvent
    data class ShowDialog(val value: AppearancePreferenceDialog?) : AppearancePreferencesEvent
    data class UpdateThemeConfig(val themeConfig: ThemeConfig) : AppearancePreferencesEvent
    data class UpdateNavStyle(val navStyle: NavStyle) : AppearancePreferencesEvent
    data object ToggleShowVideosTab : AppearancePreferencesEvent
    data object ToggleShowBrowseTab : AppearancePreferencesEvent
    data object ToggleShowHistoryTab : AppearancePreferencesEvent
    data object ToggleShowAudiobookTab : AppearancePreferencesEvent
    data class UpdateStartupPage(val startupPage: StartupPage) : AppearancePreferencesEvent
}

sealed interface AppearancePreferenceDialog {
    data object Theme : AppearancePreferenceDialog
    data object NavStyle : AppearancePreferenceDialog
    data object StartupPage : AppearancePreferenceDialog
}
