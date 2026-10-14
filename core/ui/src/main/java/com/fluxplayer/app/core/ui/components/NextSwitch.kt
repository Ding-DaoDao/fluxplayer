package com.fluxplayer.app.core.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.theme.LocalMediaBackdrop
import com.fluxplayer.app.core.ui.theme.LocalMediaPreferences
import com.fluxplayer.app.core.ui.theme.LocalMediaShell
import com.fluxplayer.app.core.ui.theme.mediaGlass

@Composable
fun NextSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    checkedIcon: ImageVector = NextIcons.Check,
) {
    if (LocalMediaShell.current) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier.mediaGlass(CircleShape),
            colors = if (LocalMediaPreferences.current.glassControlsEnabled && LocalMediaBackdrop.current != null) {
                SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                )
            } else {
                SwitchDefaults.colors()
            },
            enabled = enabled,
            thumbContent = if (checked) {
                { Icon(checkedIcon, null, Modifier.size(SwitchDefaults.IconSize)) }
            } else {
                null
            },
        )
        return
    }
    FluxSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
    )
}
