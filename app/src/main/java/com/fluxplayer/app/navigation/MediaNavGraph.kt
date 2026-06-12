package com.fluxplayer.app.navigation

import android.content.Context
import android.content.Intent
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.navigation
import com.fluxplayer.app.feature.player.PlayerActivity
import com.fluxplayer.app.feature.player.utils.PlayerApi
import com.fluxplayer.app.feature.videopicker.navigation.MediaPickerRoute
import com.fluxplayer.app.feature.videopicker.navigation.mediaPickerScreen
import com.fluxplayer.app.feature.videopicker.navigation.navigateToMediaPickerScreen
import com.fluxplayer.app.feature.videopicker.navigation.navigateToSearch
import com.fluxplayer.app.feature.videopicker.navigation.searchScreen
import com.fluxplayer.app.settings.navigation.navigateToSettings
import kotlinx.serialization.Serializable

@Serializable
data object MediaRootRoute

fun NavGraphBuilder.mediaNavGraph(
    context: Context,
    navController: NavHostController,
) {
    navigation<MediaRootRoute>(startDestination = MediaPickerRoute()) {
        mediaPickerScreen(
            onNavigateUp = navController::navigateUp,
            onPlayVideo = { uri, title ->
                val intent = Intent(context, PlayerActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = uri
                    if (!title.isNullOrEmpty()) putExtra(PlayerApi.API_TITLE, title)
                }
                context.startActivity(intent)
            },
            onPlayVideos = { uris, startUri ->
                val intent = Intent(context, PlayerActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = startUri
                    putParcelableArrayListExtra(PlayerApi.API_PLAYLIST, ArrayList(uris))
                }
                context.startActivity(intent)
            },
            onFolderClick = navController::navigateToMediaPickerScreen,
            onSettingsClick = navController::navigateToSettings,
            onSearchClick = navController::navigateToSearch,
        )

        searchScreen(
            onNavigateUp = navController::navigateUp,
            onPlayVideo = { uri, title ->
                val intent = Intent(context, PlayerActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    data = uri
                    if (!title.isNullOrEmpty()) putExtra(PlayerApi.API_TITLE, title)
                }
                context.startActivity(intent)
            },
            onFolderClick = navController::navigateToMediaPickerScreen,
        )
    }
}
