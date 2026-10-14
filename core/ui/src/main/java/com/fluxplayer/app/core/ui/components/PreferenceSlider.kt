package com.fluxplayer.app.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PreferenceSlider(
    modifier: Modifier = Modifier,
    title: String,
    description: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    isFirstItem: Boolean = false,
    isLastItem: Boolean = false,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
    trailingContent: @Composable () -> Unit = {},
    steps: Int = 0,
    saveOnRelease: Boolean = false,
    valueDescription: (@Composable (Float) -> String)? = null,
) {
    var draftValue by remember(value, enabled) { mutableFloatStateOf(value) }
    val displayedValue = if (saveOnRelease) draftValue else value
    NextSegmentedListItem(
        modifier = modifier,
        onClick = {},
        onLongClick = null,
        enabled = enabled,
        isFirstItem = isFirstItem,
        isLastItem = isLastItem,
        leadingContent = icon?.let {
            {
                if (com.fluxplayer.app.core.ui.theme.LocalMediaShell.current) {
                    MediaIconBadge(icon, enabled)
                } else {
                    FluxIcon(icon, null, Modifier.size(24.dp))
                }
            }
        },
        supportingContent = {
            Column {
                (valueDescription?.invoke(displayedValue) ?: description)?.let {
                    FluxText(text = it, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FluxSlider(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = enabled,
                    value = displayedValue,
                    valueRange = valueRange,
                    steps = steps,
                    onValueChange = {
                        if (saveOnRelease) draftValue = it else onValueChange(it)
                    },
                    onValueChangeFinished = {
                        if (saveOnRelease && draftValue != value) onValueChange(draftValue)
                        onValueChangeFinished()
                    },
                )
            }
        },
        content = {
            FluxText(text = title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
        },
        trailingContent = trailingContent,
    )
}
