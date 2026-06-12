package com.fluxplayer.app.settings.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import androidx.navigation.navOptions
import com.fluxplayer.app.settings.screens.about.AboutPreferencesScreen
import com.fluxplayer.app.settings.screens.about.LibrariesScreen

const val aboutPreferencesNavigationRoute = "about_preferences_route"
const val librariesNavigationRoute = "libraries_route"

fun NavController.navigateToAboutPreferences(navOptions: NavOptions? = navOptions { launchSingleTop = true }) {
    this.navigate(aboutPreferencesNavigationRoute, navOptions)
}

fun NavController.navigateToLibraries(navOptions: NavOptions? = navOptions { launchSingleTop = true }) {
    this.navigate(librariesNavigationRoute, navOptions)
}

fun NavGraphBuilder.aboutPreferencesScreen(
    onNavigateUp: () -> Unit,
) {
    composable(route = aboutPreferencesNavigationRoute) {
        AboutPreferencesScreen(
            onNavigateUp = onNavigateUp,
        )
    }
}

fun NavGraphBuilder.librariesScreen(
    onNavigateUp: () -> Unit,
) {
    composable(route = librariesNavigationRoute) {
        LibrariesScreen(
            onNavigateUp = onNavigateUp,
        )
    }
}
