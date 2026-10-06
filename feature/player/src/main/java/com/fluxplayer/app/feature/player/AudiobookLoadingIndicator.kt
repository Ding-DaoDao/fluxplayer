package com.fluxplayer.app.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.ui.theme.FluxTheme

/** 书库、分页和播放确认窗共用的加载提示，由各自的操作状态控制。 */
@Composable
fun AudiobookLoadingIndicator(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = FluxTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = FluxTheme.colorScheme.primary,
                strokeWidth = 2.dp,
                strokeCap = StrokeCap.Round,
            )
            Text(message, style = FluxTheme.typography.bodyMedium, color = FluxTheme.colorScheme.onSurfaceVariant)
        }
    }
}
