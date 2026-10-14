package com.fluxplayer.app.core.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.ui.theme.LocalMediaBackdrop
import com.fluxplayer.app.core.ui.theme.LocalMediaPreferences
import com.fluxplayer.app.core.ui.theme.LocalMediaShell
import com.fluxplayer.app.core.ui.theme.mediaGlass

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextTopAppBar(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
    ),
) {
    val glass = LocalMediaShell.current && LocalMediaPreferences.current.glassControlsEnabled && LocalMediaBackdrop.current != null
    TopAppBar(
        title = title,
        navigationIcon = navigationIcon,
        actions = actions,
        colors = if (glass) colors.copy(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.4f * (2f - LocalMediaPreferences.current.glassTranslucency * 2f))) else colors,
        modifier = modifier.mediaGlass(),
        contentPadding = PaddingValues(horizontal = if (LocalMediaShell.current) 12.dp else 8.dp),
        expandedHeight = TopAppBarDefaults.TopAppBarExpandedHeight,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NextTopAppBar(
    modifier: Modifier = Modifier,
    title: String,
    fontWeight: FontWeight? = null,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
    ),
) {
    NextTopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = fontWeight,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = colors,
        modifier = modifier,
    )
}
