package com.fluxplayer.app.feature.videopicker.navigation

import android.net.Uri
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavOptions
import androidx.navigation.compose.composable
import com.fluxplayer.app.feature.videopicker.screens.mediapicker.MediaPickerRoute

internal const val folderIdArg = "folderId"
internal const val selectedTabArg = "selectedTab"

fun NavController.navigateToMediaPickerScreen(
    folderId: String,
    selectedTab: Int? = null,
    navOptions: NavOptions? = null,
) {
    val encodedFolderId = Uri.encode(folderId)
    this.navigate(MediaPickerRoute(encodedFolderId, selectedTab), navOptions)
}

fun NavGraphBuilder.mediaPickerScreen(
    onNavigateUp: () -> Unit,
    onPlayVideo: (uri: Uri, title: String?) -> Unit,
    onPlayVideos: (uris: List<Uri>, startUri: Uri, isAudioOnly: Boolean) -> Unit,
    onFolderClick: (folderPath: String, selectedTab: Int) -> Unit,
    onSettingsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onWebDavClick: () -> Unit = {},
) {
    composable<MediaPickerRoute> {
        MediaPickerRoute(
            onPlayVideo = onPlayVideo,
            onPlayVideos = onPlayVideos,
            onNavigateUp = onNavigateUp,
            onFolderClick = onFolderClick,
            onSettingsClick = onSettingsClick,
            onSearchClick = onSearchClick,
            onWebDavClick = onWebDavClick,
        )
    }
}
