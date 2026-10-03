package com.fluxplayer.app.feature.player

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.model.ThemeConfig
import com.fluxplayer.app.core.ui.theme.NextPlayerTheme
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AudioThemePreferences {
    fun preferencesRepository(): PreferencesRepository
}

@Composable
fun AudioPlayerTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context.applicationContext) {
        runCatching { EntryPointAccessors.fromApplication(context.applicationContext, AudioThemePreferences::class.java).preferencesRepository() }.getOrNull()
    }
    val preferences = if (repository != null) {
        val value by repository.applicationPreferences.collectAsStateWithLifecycle()
        value
    } else {
        ApplicationPreferences()
    }
    val materialControls = if (repository != null) {
        val value by repository.playerPreferences.collectAsStateWithLifecycle()
        value.useMaterialYouControls
    } else {
        false
    }
    val dark = when (preferences.themeConfig) {
        ThemeConfig.SYSTEM -> isSystemInDarkTheme()
        ThemeConfig.ON -> true
        ThemeConfig.OFF -> false
    }
    CompositionLocalProvider(LocalUseMaterialYouControls provides materialControls) {
        NextPlayerTheme(
            darkTheme = dark,
            accentPreset = preferences.accentPreset,
            surfaceStyle = preferences.surfaceStyle,
            content = content,
        )
    }
}
