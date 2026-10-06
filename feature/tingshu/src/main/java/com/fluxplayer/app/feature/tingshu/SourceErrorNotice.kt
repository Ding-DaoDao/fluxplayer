package com.fluxplayer.app.feature.tingshu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.ui.theme.FluxTheme

/** 错误在当前操作界面持续展示，长详情可展开、滚动和复制。 */
@Composable
internal fun SourceErrorNotice(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null, onDismiss: (() -> Unit)? = null) {
    var expanded by remember(message) { mutableStateOf(false) }
    val summary = message.substringBefore('\n')
    val detail = message.substringAfter('\n', "")
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = FluxTheme.colorScheme.errorContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(summary, style = FluxTheme.typography.bodyMedium, color = FluxTheme.colorScheme.onErrorContainer)
            if (expanded && detail.isNotBlank()) {
                SelectionContainer {
                    Text(detail, modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState()), style = FluxTheme.typography.bodySmall, color = FluxTheme.colorScheme.onErrorContainer)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                onRetry?.let { TextButton(onClick = it) { Text("重试") } }
                if (detail.isNotBlank()) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起详情" else "错误详情") }
                onDismiss?.let { TextButton(onClick = it) { Text("关闭") } }
            }
        }
    }
}
