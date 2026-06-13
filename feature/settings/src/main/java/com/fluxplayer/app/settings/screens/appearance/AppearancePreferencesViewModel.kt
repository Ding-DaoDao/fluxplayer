package com.fluxplayer.app.settings.screens.appearance

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.model.ComposeEngine
import com.fluxplayer.app.core.model.ThemeConfig
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
            is AppearancePreferencesEvent.ShowDialog -> showDialog(event.value)
            AppearancePreferencesEvent.ToggleDarkTheme -> toggleDarkTheme()
            is AppearancePreferencesEvent.UpdateThemeConfig -> updateThemeConfig(event.themeConfig)
            AppearancePreferencesEvent.ToggleUseDynamicColors -> toggleUseDynamicColors()
            AppearancePreferencesEvent.ToggleUseHighContrastDarkTheme -> toggleUseHighContrastDarkTheme()
            AppearancePreferencesEvent.ToggleUseLiquidGlass -> toggleUseLiquidGlass()
            AppearancePreferencesEvent.ToggleUseFloatingBottomBar -> toggleUseFloatingBottomBar()
            AppearancePreferencesEvent.ToggleEnableBlur -> toggleEnableBlur()
            AppearancePreferencesEvent.ToggleEnableProgressiveBlur -> toggleEnableProgressiveBlur()
            is AppearancePreferencesEvent.UpdateTopBarBlurRadius -> updateTopBarBlurRadius(event.value)
            is AppearancePreferencesEvent.UpdateTopBarBlurAlpha -> updateTopBarBlurAlpha(event.value)
            is AppearancePreferencesEvent.UpdateBottomBarBlurRadius -> updateBottomBarBlurRadius(event.value)
            is AppearancePreferencesEvent.UpdateBottomBarBlurAlpha -> updateBottomBarBlurAlpha(event.value)
            is AppearancePreferencesEvent.UpdateComposeEngine -> updateComposeEngine(event.composeEngine)
        }
    }

    private fun showDialog(value: AppearancePreferenceDialog?) {
        uiStateInternal.update {
            it.copy(showDialog = value)
        }
    }

    private fun toggleDarkTheme() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(
                    themeConfig = if (it.themeConfig == ThemeConfig.ON) ThemeConfig.OFF else ThemeConfig.ON,
                )
            }
        }
    }

    private fun updateThemeConfig(themeConfig: ThemeConfig) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(themeConfig = themeConfig)
            }
        }
    }

    private fun toggleUseDynamicColors() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(useDynamicColors = !it.useDynamicColors)
            }
        }
    }

    private fun toggleUseHighContrastDarkTheme() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(useHighContrastDarkTheme = !it.useHighContrastDarkTheme)
            }
        }
    }

    private fun toggleUseLiquidGlass() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(useLiquidGlass = !it.useLiquidGlass)
            }
        }
    }

    private fun toggleUseFloatingBottomBar() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(useFloatingBottomBar = !it.useFloatingBottomBar)
            }
        }
    }

    private fun toggleEnableBlur() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(enableBlur = !it.enableBlur)
            }
        }
    }

    private fun toggleEnableProgressiveBlur() {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(enableProgressiveBlur = !it.enableProgressiveBlur)
            }
        }
    }

    private fun updateTopBarBlurRadius(value: Int) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(topBarBlurRadius = value.coerceIn(0, 50))
            }
        }
    }

    private fun updateTopBarBlurAlpha(value: Int) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(topBarBlurAlpha = value.coerceIn(0, 100))
            }
        }
    }

    private fun updateBottomBarBlurRadius(value: Int) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(bottomBarBlurRadius = value.coerceIn(0, 50))
            }
        }
    }

    private fun updateBottomBarBlurAlpha(value: Int) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(bottomBarBlurAlpha = value.coerceIn(0, 100))
            }
        }
    }

    private fun updateComposeEngine(composeEngine: ComposeEngine) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(composeEngine = composeEngine)
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
    data class ShowDialog(val value: AppearancePreferenceDialog?) : AppearancePreferencesEvent
    data object ToggleDarkTheme : AppearancePreferencesEvent
    data class UpdateThemeConfig(val themeConfig: ThemeConfig) : AppearancePreferencesEvent
    data object ToggleUseDynamicColors : AppearancePreferencesEvent
    data object ToggleUseHighContrastDarkTheme : AppearancePreferencesEvent
    data object ToggleUseLiquidGlass : AppearancePreferencesEvent
    data object ToggleUseFloatingBottomBar : AppearancePreferencesEvent
    data object ToggleEnableBlur : AppearancePreferencesEvent
    data object ToggleEnableProgressiveBlur : AppearancePreferencesEvent
    data class UpdateTopBarBlurRadius(val value: Int) : AppearancePreferencesEvent
    data class UpdateTopBarBlurAlpha(val value: Int) : AppearancePreferencesEvent
    data class UpdateBottomBarBlurRadius(val value: Int) : AppearancePreferencesEvent
    data class UpdateBottomBarBlurAlpha(val value: Int) : AppearancePreferencesEvent
    data class UpdateComposeEngine(val composeEngine: ComposeEngine) : AppearancePreferencesEvent
}

sealed interface AppearancePreferenceDialog {
    data object Theme : AppearancePreferenceDialog
    data object ComposeEngine : AppearancePreferenceDialog
}
