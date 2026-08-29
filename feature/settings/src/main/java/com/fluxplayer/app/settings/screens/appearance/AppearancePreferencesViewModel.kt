package com.fluxplayer.app.settings.screens.appearance

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.model.AccentPreset
import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.model.ComposeEngine
import com.fluxplayer.app.core.model.StartupPage
import com.fluxplayer.app.core.model.ThemeConfig
import com.fluxplayer.app.core.model.ThemeStyle
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
            is AppearancePreferencesEvent.UpdateThemeStyle -> updateThemeStyle(event.themeStyle)
            is AppearancePreferencesEvent.UpdateAccentPreset -> updateAccentPreset(event.accentPreset)
            is AppearancePreferencesEvent.UpdateCustomSeedColor -> updateCustomSeedColor(event.value)
            is AppearancePreferencesEvent.UpdateTopBarOpacity -> updateTopBarOpacity(event.value)
            is AppearancePreferencesEvent.UpdateBottomBarOpacity -> updateBottomBarOpacity(event.value)
            is AppearancePreferencesEvent.UpdateContainerOpacity -> updateContainerOpacity(event.value)
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

    private fun updateThemeStyle(themeStyle: ThemeStyle) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(
                    themeStyle = themeStyle,
                    // 墨 · 极简是 Material 3 专属风格（Miuix 引擎使用内置默认色，
                    // 不接收墨色方案），选墨色时引擎自动切回 Material 3。
                    composeEngine = if (themeStyle == ThemeStyle.INK) {
                        ComposeEngine.MATERIAL
                    } else {
                        it.composeEngine
                    },
                )
            }
        }
    }

    private fun updateAccentPreset(accentPreset: AccentPreset) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(accentPreset = accentPreset)
            }
        }
    }

    private fun updateCustomSeedColor(value: Int) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(
                    customSeedColor = value,
                    useDynamicColors = false,
                )
            }
        }
    }

    private fun updateTopBarOpacity(value: Int) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(topBarOpacity = value.coerceIn(0, 100))
            }
        }
    }

    private fun updateBottomBarOpacity(value: Int) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(bottomBarOpacity = value.coerceIn(0, 100))
            }
        }
    }

    private fun updateContainerOpacity(value: Int) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(containerOpacity = value.coerceIn(0, 100))
            }
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
        val startupPage = preferences.startupPage
            .takeIf { it in visiblePages }
            ?: visiblePages.firstOrNull()
            ?: StartupPage.VIDEOS
        return preferences.copy(startupPage = startupPage)
    }

    private fun updateStartupPage(startupPage: StartupPage) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences {
                it.copy(startupPage = startupPage)
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
    data class UpdateThemeStyle(val themeStyle: ThemeStyle) : AppearancePreferencesEvent
    data class UpdateAccentPreset(val accentPreset: AccentPreset) : AppearancePreferencesEvent
    data class UpdateCustomSeedColor(val value: Int) : AppearancePreferencesEvent
    data class UpdateTopBarOpacity(val value: Int) : AppearancePreferencesEvent
    data class UpdateBottomBarOpacity(val value: Int) : AppearancePreferencesEvent
    data class UpdateContainerOpacity(val value: Int) : AppearancePreferencesEvent
    data object ToggleShowVideosTab : AppearancePreferencesEvent
    data object ToggleShowBrowseTab : AppearancePreferencesEvent
    data object ToggleShowHistoryTab : AppearancePreferencesEvent
    data object ToggleShowAudiobookTab : AppearancePreferencesEvent
    data class UpdateStartupPage(val startupPage: StartupPage) : AppearancePreferencesEvent
}

sealed interface AppearancePreferenceDialog {
    data object Theme : AppearancePreferenceDialog
    data object ComposeEngine : AppearancePreferenceDialog
    data object ThemeStyle : AppearancePreferenceDialog
    data object StartupPage : AppearancePreferenceDialog
}
