package com.fluxplayer.app.settings.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import androidx.navigation.navOptions
import com.fluxplayer.app.settings.screens.openlist.OpenListSettingsScreen

const val openListSettingsNavigationRoute = "openlist_settings_route"

fun NavController.navigateToOpenListSettings(navOptions: NavOptions? = navOptions { launchSingleTop = true }) {
    this.navigate(openListSettingsNavigationRoute, navOptions)
}

fun NavGraphBuilder.openListSettingsScreen(onNavigateUp: () -> Unit) {
    composable(route = openListSettingsNavigationRoute) {
        OpenListSettingsScreen(onNavigateUp = onNavigateUp)
    }
}
