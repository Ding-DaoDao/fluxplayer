package com.fluxplayer.app.settings.extensions

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.fluxplayer.app.core.model.ComposeEngine
import com.fluxplayer.app.core.ui.R

@Composable
fun ComposeEngine.name(): String {
    val stringRes = when (this) {
        ComposeEngine.MATERIAL -> R.string.compose_engine_material
        ComposeEngine.MIUIX -> R.string.compose_engine_miuix
    }

    return stringResource(id = stringRes)
}
