package com.fluxplayer.app.core.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.fluxplayer.app.core.ui.designsystem.NextIcons

@Composable
fun NextSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    checkedIcon: ImageVector = NextIcons.Check,
) {
    FluxSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
    )
}
