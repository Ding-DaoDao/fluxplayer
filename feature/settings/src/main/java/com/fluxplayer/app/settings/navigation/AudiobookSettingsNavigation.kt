package com.fluxplayer.app.settings.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.fluxplayer.app.settings.screens.audiobook.AudiobookSettingsScreen

const val audiobookSettingsNavigationRoute = "audiobook_settings_route"

fun NavController.navigateToAudiobookSettings() {
    navigate(audiobookSettingsNavigationRoute) { launchSingleTop = true }
}

fun NavGraphBuilder.audiobookSettingsScreen(onNavigateUp: () -> Unit) {
    composable(audiobookSettingsNavigationRoute) { AudiobookSettingsScreen(onNavigateUp) }
}
