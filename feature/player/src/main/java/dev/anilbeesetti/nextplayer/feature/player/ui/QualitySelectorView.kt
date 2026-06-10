package dev.anilbeesetti.nextplayer.feature.player.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.feature.player.extensions.noRippleClickable

@Composable
fun BoxScope.QualitySelectorView(
    modifier: Modifier = Modifier,
    show: Boolean,
    qualities: List<QualityOption>,
    currentUri: Uri?,
    onQualitySelected: (QualityOption) -> Unit,
) {
    if (!show) return

    Box(
        modifier = modifier
            .matchParentSize()
            .noRippleClickable { },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.width(IntrinsicSize.Max),
            shape = RoundedCornerShape(12.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "清晰度",
                    modifier = Modifier.padding(bottom = 8.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (qualities.isEmpty()) {
                    Text(
                        text = "当前视频无可用清晰度信息",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    val hasUriMatch = qualities.any { q ->
                        q.uri.toString().trimEnd('#') == currentUri?.toString()?.trimEnd('#')
                    }
                    Column(
                        modifier = Modifier
                            .selectableGroup()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        qualities.forEachIndexed { index, option ->
                            val isSelected = if (hasUriMatch) {
                                option.uri.toString().trimEnd('#') == currentUri?.toString()?.trimEnd('#')
                            } else {
                                index == 0
                            }
                            RadioButtonRow(
                                selected = isSelected,
                                text = option.label,
                                onClick = { onQualitySelected(option) },
                            )
                        }
                    }
                }
            }
        }
    }
}
