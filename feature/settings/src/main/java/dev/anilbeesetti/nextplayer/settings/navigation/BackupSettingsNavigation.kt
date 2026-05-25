package dev.anilbeesetti.nextplayer.settings.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import androidx.navigation.navOptions
import dev.anilbeesetti.nextplayer.settings.screens.backup.BackupScreen

const val backupSettingsNavigationRoute = "backup_settings_route"

fun NavController.navigateToBackupSettings(navOptions: NavOptions? = navOptions { launchSingleTop = true }) {
    this.navigate(backupSettingsNavigationRoute, navOptions)
}

fun NavGraphBuilder.backupSettingsScreen(onNavigateUp: () -> Unit) {
    composable(route = backupSettingsNavigationRoute) {
        BackupScreen(onNavigateUp = onNavigateUp)
    }
}
