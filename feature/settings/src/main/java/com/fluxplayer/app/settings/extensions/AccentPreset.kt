package com.fluxplayer.app.settings.extensions

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.fluxplayer.app.core.model.AccentPreset
import com.fluxplayer.app.core.ui.R

@Composable
fun AccentPreset.name(): String {
    val stringRes = when (this) {
        AccentPreset.INK -> R.string.accent_ink
        AccentPreset.SEAL -> R.string.accent_seal
        AccentPreset.AZURE -> R.string.accent_azure
        AccentPreset.VIOLET -> R.string.accent_violet
        AccentPreset.AMBER -> R.string.accent_amber
        AccentPreset.CYAN -> R.string.accent_cyan
        AccentPreset.GRAPHITE -> R.string.accent_graphite
    }

    return stringResource(id = stringRes)
}
