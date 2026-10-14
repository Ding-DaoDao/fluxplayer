package com.fluxplayer.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.model.NavStyle
import com.fluxplayer.app.core.model.SurfaceStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class MainViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
) : ViewModel() {

    init {
        // 首次升级修复旧底栏配置；之后用户主动关闭玻璃的选择不会被覆盖。
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { preferences ->
                if (preferences.glassSettingsVersion >= 1) {
                    preferences
                } else {
                    preferences.copy(
                        useFloatingBottomBar = true,
                        navStyle = NavStyle.DOCK,
                        surfaceStyle = SurfaceStyle.GLASS,
                        useLiquidGlass = true,
                        glassSettingsVersion = 1,
                    )
                }
            }
        }
    }

    val uiState = preferencesRepository.persistedApplicationPreferences.map { preferences ->
        MainActivityUiState.Success(preferences)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainActivityUiState.Loading,
    )
}

sealed interface MainActivityUiState {
    object Loading : MainActivityUiState
    data class Success(val preferences: ApplicationPreferences) : MainActivityUiState
}
