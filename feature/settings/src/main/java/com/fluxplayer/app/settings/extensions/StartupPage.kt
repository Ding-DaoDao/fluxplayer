package com.fluxplayer.app.settings.extensions

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.fluxplayer.app.core.model.StartupPage
import com.fluxplayer.app.core.ui.R

@Composable
fun StartupPage.name(): String {
    val stringRes = when (this) {
        StartupPage.VIDEOS -> R.string.startup_page_videos
        StartupPage.BROWSE -> R.string.startup_page_browse
        StartupPage.HISTORY -> R.string.startup_page_history
    }

    return stringResource(id = stringRes)
}
