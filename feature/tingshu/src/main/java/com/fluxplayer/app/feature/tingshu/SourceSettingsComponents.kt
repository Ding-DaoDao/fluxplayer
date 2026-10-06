package com.fluxplayer.app.feature.tingshu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fluxplayer.app.core.ui.theme.FluxTheme

@Composable
fun ListeningSettingsCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = FluxTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = FluxTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = FluxTheme.typography.bodySmall, color = FluxTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

/** 配置表单使用独立滚动区域，标题和保存按钮始终可见。 */
@Composable
internal fun SourceSettingsSheet(
    title: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    canSave: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = FluxTheme.colorScheme.surface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp).imePadding().widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight(0.9f),
        ) {
            Column {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("书源设置", style = FluxTheme.typography.labelMedium, color = FluxTheme.colorScheme.primary)
                    Text(title, style = FluxTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("管理账号、目录与书源偏好", style = FluxTheme.typography.bodySmall, color = FluxTheme.colorScheme.onSurfaceVariant)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp), content = content)
                HorizontalDivider(color = FluxTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.weight(1f)) { Text("关闭") }
                    Button(onClick = onSave, enabled = !busy && canSave, modifier = Modifier.weight(2f), shape = RoundedCornerShape(14.dp)) { Text("保存配置") }
                }
            }
        }
    }
}

@Composable
internal fun SourceConfigField(label: String, value: String, onValueChange: (String) -> Unit, enabled: Boolean, secret: Boolean = false, hint: String = "") {
    var visible by remember(label) { mutableStateOf(false) }
    val title = label.substringBefore('\n')
    val description = hint.ifBlank { label.substringAfter('\n', "").trim() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = FluxTheme.typography.labelLarge, color = FluxTheme.colorScheme.onSurface)
        OutlinedTextField(
            value = value, onValueChange = onValueChange, enabled = enabled, singleLine = true,
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            placeholder = { Text("请输入$title", style = FluxTheme.typography.bodyMedium) },
            visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = if (secret) {
                { TextButton(onClick = { visible = !visible }) { Text(if (visible) "隐藏" else "显示", style = FluxTheme.typography.labelMedium) } }
            } else {
                null
            },
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = FluxTheme.colorScheme.surfaceContainerLow,
                focusedContainerColor = FluxTheme.colorScheme.surfaceContainerLow,
                unfocusedBorderColor = FluxTheme.colorScheme.outlineVariant,
            ),
        )
        if (description.isNotBlank()) Text(description, style = FluxTheme.typography.bodySmall, color = FluxTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SourceConfigSwitch(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label.substringBefore('\n'), style = FluxTheme.typography.bodyMedium)
            label.substringAfter('\n', "").takeIf { it.isNotBlank() }?.let { Text(it, style = FluxTheme.typography.bodySmall, color = FluxTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onCheckedChange, enabled = enabled)
    }
}

@Composable
internal fun SourceConfigOptions(label: String, options: List<String>, selected: Set<String>, enabled: Boolean, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = FluxTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option -> FilterChip(selected = option in selected, onClick = { onSelect(option) }, label = { Text(option) }, enabled = enabled) }
        }
    }
}

@Composable
internal fun SourceStatusCard(title: String, message: String = "", error: Boolean = false) {
    Surface(shape = RoundedCornerShape(16.dp), color = if (error) FluxTheme.colorScheme.errorContainer else FluxTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = FluxTheme.typography.labelLarge, color = if (error) FluxTheme.colorScheme.onErrorContainer else FluxTheme.colorScheme.onSecondaryContainer)
            if (message.isNotBlank()) Text(message, style = FluxTheme.typography.bodySmall, color = if (error) FluxTheme.colorScheme.onErrorContainer else FluxTheme.colorScheme.onSecondaryContainer)
        }
    }
}

// 书源自带的登录操作也隐藏，退出登录仍可用于清除已有凭证。
internal fun isSourceLoginAction(label: String): Boolean =
    (label.contains("登录") && !label.contains("退出登录")) ||
        label.trim().lowercase() in setOf("login", "log in", "sign in", "signin", "relogin")
