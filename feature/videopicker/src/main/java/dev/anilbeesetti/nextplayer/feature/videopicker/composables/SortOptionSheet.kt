package dev.anilbeesetti.nextplayer.feature.videopicker.composables

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class SortOption(val key: String, val label: String) {
    NAME_ASC("name:asc", "名称 A-Z"),
    NAME_DESC("name:desc", "名称 Z-A"),
    TIME_DESC("time:desc", "时间 新→旧"),
    TIME_ASC("time:asc", "时间 旧→新"),
    SIZE_DESC("size:desc", "大小 大→小"),
    SIZE_ASC("size:asc", "大小 小→大"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortOptionSheet(
    currentKey: String,
    onSelect: (SortOption) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                text = "排序方式",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            SortOption.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(option) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = option.key == currentKey,
                        onClick = { onSelect(option) },
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
