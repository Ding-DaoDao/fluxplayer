package com.fluxplayer.app.settings.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import androidx.navigation.navOptions
import com.fluxplayer.app.settings.screens.webdav.WebDavSettingsScreen

const val webDavSettingsNavigationRoute = "webdav_settings_route"

fun NavController.navigateToWebDavSettings(navOptions: NavOptions? = navOptions { launchSingleTop = true }) {
    this.navigate(webDavSettingsNavigationRoute, navOptions)
}

fun NavGraphBuilder.webDavSettingsScreen(onNavigateUp: () -> Unit) {
    composable(route = webDavSettingsNavigationRoute) {
        WebDavSettingsScreen(onNavigateUp = onNavigateUp)
    }
}
