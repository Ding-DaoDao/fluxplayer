package com.fluxplayer.app.core.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.ui.theme.LocalMediaShell

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NextSegmentedListItem(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    isFirstItem: Boolean = false,
    isLastItem: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    colors: ListItemColors = ListItemDefaults.segmentedColors(
        containerColor = if (LocalMediaShell.current) MaterialTheme.colorScheme.surfaceBright else MaterialTheme.colorScheme.surfaceContainerLow,
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    ),
    shapes: ListItemShapes = ListItemDefaults.shapes(),
    leadingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    overlineContent: @Composable (() -> Unit)? = null,
    supportingContent: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
    onClick: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    interactionSource: MutableInteractionSource? = null,
) {
    val overrideShape = MaterialTheme.shapes.large
    val mediaShell = LocalMediaShell.current
    SegmentedListItem(
        modifier = modifier,
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        verticalAlignment = Alignment.CenterVertically,
        shapes = remember(isFirstItem, isLastItem, shapes, overrideShape, mediaShell) {
            val defaultBaseShape = shapes.shape
            if (mediaShell) {
                shapes.copy(
                    shape = RoundedCornerShape(
                        topStart = if (isFirstItem) 32.dp else 4.dp,
                        topEnd = if (isFirstItem) 32.dp else 4.dp,
                        bottomStart = if (isLastItem) 32.dp else 4.dp,
                        bottomEnd = if (isLastItem) 32.dp else 4.dp,
                    ),
                )
            } else if (defaultBaseShape is CornerBasedShape) {
                shapes.copy(
                    shape = defaultBaseShape.copy(
                        topStart = overrideShape.topStart.takeIf { isFirstItem } ?: defaultBaseShape.topStart,
                        topEnd = overrideShape.topEnd.takeIf { isFirstItem } ?: defaultBaseShape.topEnd,
                        bottomStart = overrideShape.bottomStart.takeIf { isLastItem } ?: defaultBaseShape.bottomStart,
                        bottomEnd = overrideShape.bottomEnd.takeIf { isLastItem } ?: defaultBaseShape.bottomEnd,
                    ),
                )
            } else {
                shapes
            }
        },
        colors = colors,
        contentPadding = contentPadding,
        leadingContent = leadingContent,
        supportingContent = supportingContent,
        trailingContent = trailingContent,
        overlineContent = overlineContent,
        interactionSource = interactionSource,
        content = content,
    )
}

@Composable
fun ListSectionTitle(
    modifier: Modifier = Modifier,
    text: String,
    contentPadding: PaddingValues = PaddingValues(
        start = 16.dp,
        top = 28.dp,
        bottom = 8.dp,
    ),
    color: Color = if (LocalMediaShell.current) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
) {
    Text(
        text = text,
        modifier = modifier.padding(contentPadding),
        color = color,
        style = if (LocalMediaShell.current) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
    )
}
