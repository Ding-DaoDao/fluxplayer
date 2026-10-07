package com.fluxplayer.app.feature.tingshu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Keep scroll position, but discard measured rows when recent books change layout. */
@Composable
internal fun ListeningHomeGrid(
    grid: Boolean,
    modifier: Modifier = Modifier,
    content: LazyGridScope.(Boolean) -> Unit,
) {
    val state = rememberLazyGridState()
    key(grid) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = state,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The span and item callbacks must share this immutable layout snapshot.
            // Reading mutable state inside a span callback can invalidate cached row placement.
            content(grid)
        }
    }
}
