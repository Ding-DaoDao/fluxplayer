package com.fluxplayer.app.core.ui.avbox

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.ui.components.NextSwitch

val ColorScheme.cardContainer: Color get() = surfaceBright

enum class SettingsCardPosition { SINGLE, FIRST, MIDDLE, LAST }

/** 分组外圆角和内圆角采用 AVBox 的 32／4 dp，组内间距为 2 dp。 */
@Composable
fun SettingsCard(position: SettingsCardPosition, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = when (position) {
        SettingsCardPosition.SINGLE -> RoundedCornerShape(32.dp)
        SettingsCardPosition.FIRST -> RoundedCornerShape(32.dp, 32.dp, 4.dp, 4.dp)
        SettingsCardPosition.MIDDLE -> RoundedCornerShape(4.dp)
        SettingsCardPosition.LAST -> RoundedCornerShape(4.dp, 4.dp, 32.dp, 32.dp)
    }
    Surface(modifier.fillMaxWidth(), shape = shape, color = MaterialTheme.colorScheme.cardContainer) { Column(content = content) }
}

@Composable
fun SettingsGroup(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        title?.let { Text(it, Modifier.padding(start = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        content()
    }
}

@Composable
fun SettingsSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    NextSwitch(checked, onCheckedChange, enabled = enabled)
}

@Composable
fun SettingsSwitchRow(title: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(16.dp))
        SettingsSwitch(checked, onCheckedChange, enabled)
    }
}

@Composable
fun RowLeadingIcon(id: Int, enabled: Boolean) {
    Icon(painterResource(id), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.38f))
    Spacer(Modifier.width(16.dp))
}

@Composable
fun ColorScheme.filterChipColors(): androidx.compose.material3.SelectableChipColors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
    containerColor = Color.Transparent,
    selectedContainerColor = primaryContainer,
    selectedLabelColor = onPrimaryContainer,
)
