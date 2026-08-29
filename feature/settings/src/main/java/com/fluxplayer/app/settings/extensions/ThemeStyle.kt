package com.fluxplayer.app.settings.extensions

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.fluxplayer.app.core.model.ThemeStyle
import com.fluxplayer.app.core.ui.R

@Composable
fun ThemeStyle.name(): String {
    val stringRes = when (this) {
        ThemeStyle.TONAL -> R.string.theme_style_tonal
        ThemeStyle.INK -> R.string.theme_style_ink
    }

    return stringResource(id = stringRes)
}
