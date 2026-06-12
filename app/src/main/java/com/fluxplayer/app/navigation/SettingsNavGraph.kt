package com.fluxplayer.app.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.navigation
import com.fluxplayer.app.settings.Setting
import com.fluxplayer.app.settings.navigation.aboutPreferencesScreen
import com.fluxplayer.app.settings.navigation.appearancePreferencesScreen
import com.fluxplayer.app.settings.navigation.backupSettingsScreen
import com.fluxplayer.app.settings.navigation.audioPreferencesScreen
import com.fluxplayer.app.settings.navigation.decoderPreferencesScreen
import com.fluxplayer.app.settings.navigation.folderPreferencesScreen
import com.fluxplayer.app.settings.navigation.generalPreferencesScreen
import com.fluxplayer.app.settings.navigation.librariesScreen
import com.fluxplayer.app.settings.navigation.mediaLibraryPreferencesScreen
import com.fluxplayer.app.settings.navigation.navigateToAboutPreferences
import com.fluxplayer.app.settings.navigation.navigateToAppearancePreferences
import com.fluxplayer.app.settings.navigation.gesturePreferencesScreen
import com.fluxplayer.app.settings.navigation.navigateToAudioPreferences
import com.fluxplayer.app.settings.navigation.navigateToDecoderPreferences
import com.fluxplayer.app.settings.navigation.navigateToGesturePreferences
import com.fluxplayer.app.settings.navigation.navigateToFolderPreferencesScreen
import com.fluxplayer.app.settings.navigation.navigateToBackupSettings
import com.fluxplayer.app.settings.navigation.navigateToGeneralPreferences
import com.fluxplayer.app.settings.navigation.navigateToLibraries
import com.fluxplayer.app.settings.navigation.navigateToMediaLibraryPreferencesScreen
import com.fluxplayer.app.settings.navigation.navigateToPlayerPreferences
import com.fluxplayer.app.settings.navigation.navigateToSubtitlePreferences
import com.fluxplayer.app.settings.navigation.navigateToThumbnailPreferencesScreen
import com.fluxplayer.app.settings.navigation.navigateToOpenListSettings
import com.fluxplayer.app.settings.navigation.navigateToWebDavSettings
import com.fluxplayer.app.settings.navigation.openListSettingsScreen
import com.fluxplayer.app.settings.navigation.playerPreferencesScreen
import com.fluxplayer.app.settings.navigation.settingsNavigationRoute
import com.fluxplayer.app.settings.navigation.settingsScreen
import com.fluxplayer.app.settings.navigation.subtitlePreferencesScreen
import com.fluxplayer.app.settings.navigation.thumbnailPreferencesScreen
import com.fluxplayer.app.settings.navigation.webDavSettingsScreen

const val SETTINGS_ROUTE = "settings_nav_route"

fun NavGraphBuilder.settingsNavGraph(
    navController: NavHostController,
) {
    navigation(
        startDestination = settingsNavigationRoute,
        route = SETTINGS_ROUTE,
    ) {
        settingsScreen(
            onNavigateUp = navController::navigateUp,
            onItemClick = { setting ->
                when (setting) {
                    Setting.APPEARANCE -> navController.navigateToAppearancePreferences()
                    Setting.MEDIA_LIBRARY -> navController.navigateToMediaLibraryPreferencesScreen()
                    Setting.PLAYER -> navController.navigateToPlayerPreferences()
                    Setting.GESTURES -> navController.navigateToGesturePreferences()
                    Setting.DECODER -> navController.navigateToDecoderPreferences()
                    Setting.AUDIO -> navController.navigateToAudioPreferences()
                    Setting.SUBTITLE -> navController.navigateToSubtitlePreferences()
                    Setting.WEBDAV -> navController.navigateToWebDavSettings()
                    Setting.OPENLIST -> navController.navigateToOpenListSettings()
                    Setting.GENERAL -> navController.navigateToGeneralPreferences()
                    Setting.ABOUT -> navController.navigateToAboutPreferences()
                }
            },
        )
        appearancePreferencesScreen(onNavigateUp = navController::navigateUp)
        mediaLibraryPreferencesScreen(
            onNavigateUp = navController::navigateUp,
            onFolderSettingClick = navController::navigateToFolderPreferencesScreen,
            onThumbnailSettingClick = navController::navigateToThumbnailPreferencesScreen,
        )
        thumbnailPreferencesScreen(onNavigateUp = navController::navigateUp)
        folderPreferencesScreen(onNavigateUp = navController::navigateUp)
        playerPreferencesScreen(onNavigateUp = navController::navigateUp)
        gesturePreferencesScreen(onNavigateUp = navController::navigateUp)
        decoderPreferencesScreen(onNavigateUp = navController::navigateUp)
        audioPreferencesScreen(onNavigateUp = navController::navigateUp)
        subtitlePreferencesScreen(onNavigateUp = navController::navigateUp)
        openListSettingsScreen(onNavigateUp = navController::navigateUp)
        webDavSettingsScreen(onNavigateUp = navController::navigateUp)
        generalPreferencesScreen(
            onNavigateUp = navController::navigateUp,
            onBackupClick = navController::navigateToBackupSettings,
        )
        backupSettingsScreen(onNavigateUp = navController::navigateUp)
        aboutPreferencesScreen(
            onLibrariesClick = navController::navigateToLibraries,
            onNavigateUp = navController::navigateUp,
        )
        librariesScreen(onNavigateUp = navController::navigateUp)
    }
}
