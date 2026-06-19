package com.fluxplayer.app.feature.player.buttons

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.fluxplayer.app.core.ui.R as coreUiR
import com.fluxplayer.app.feature.player.LocalControlsVisibilityState

@Composable
internal fun PreviousButton(player: Player, modifier: Modifier = Modifier) {
    val controlsVisibilityState = LocalControlsVisibilityState.current

    PlayerButton(
        modifier = modifier.size(48.dp),
        isEnabled = player.hasPreviousMediaItem(),
        onClick = {
            player.seekToPreviousMediaItem()
            controlsVisibilityState?.showControls()
        },
    ) {
        Icon(
            painter = painterResource(coreUiR.drawable.ic_skip_prev),
            contentDescription = stringResource(coreUiR.string.player_controls_previous),
            modifier = Modifier.size(28.dp),
        )
    }
}
