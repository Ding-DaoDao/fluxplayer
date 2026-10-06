package com.fluxplayer.app.feature.tingshu

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fluxplayer.app.core.tingshu.SourceHost
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.theme.FluxTheme
import com.github.eprendre.tingshu.utils.ConfigItem

@Composable
fun TingshuConfigContent(modifier: Modifier = Modifier, viewModel: TingshuViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sources by viewModel.repository.sources.collectAsStateWithLifecycle()
    val packages by viewModel.repository.packages.collectAsStateWithLifecycle()
    var configuringJdr by remember { mutableStateOf<com.fluxplayer.app.core.tingshu.ListeningSource?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var showImportInfo by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importSource(uri)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("我的书源", style = FluxTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("${sources.size} 个可用书源", style = FluxTheme.typography.bodySmall, color = FluxTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = { showImportInfo = true }, enabled = !state.loading, shape = RoundedCornerShape(14.dp)) { Text("导入书源") }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.configItems == null) state.error?.let { SourceErrorNotice(it, onRetry = viewModel::retry, onDismiss = viewModel::dismissError) }
        if (packages.isEmpty()) {
            ListeningSettingsCard("添加第一个书源", "导入书源后，就能在听书首页浏览和收听。") {
                Text("支持 JDR 和 JAR 书源文件", style = FluxTheme.typography.bodyMedium)
            }
        }
        packages.forEach { pkg ->
            val entries = sources.filter { it.packageEntry == pkg.entry }
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = FluxTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(14.dp), color = FluxTheme.colorScheme.primaryContainer) {
                        Icon(NextIcons.Headset, null, Modifier.padding(12.dp).size(24.dp), tint = FluxTheme.colorScheme.onPrimaryContainer)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (entries.size == 1) entries.single().name else if (pkg.entry.startsWith("jdr:")) "网盘书源" else "听书源包", style = FluxTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(if (pkg.enabled) "已启用 · ${entries.size} 个书源" else "已停用", style = FluxTheme.typography.bodySmall, color = FluxTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(pkg.enabled, { viewModel.enable(pkg.entry, it) }, enabled = !state.loading)
                }
                pkg.error?.let { Text(it, color = FluxTheme.colorScheme.error, style = FluxTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                entries.forEach { source ->
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = FluxTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ListItem(
                        headlineContent = { Text(source.name, style = FluxTheme.typography.bodyLarge) },
                        supportingContent = { Text("账号、目录与偏好", style = FluxTheme.typography.bodySmall) },
                        trailingContent = { Icon(NextIcons.Settings, "配置", tint = FluxTheme.colorScheme.onSurfaceVariant) },
                        colors = ListItemDefaults.colors(containerColor = FluxTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clickable(enabled = !state.loading) {
                            if (source.id.startsWith("jdr:")) configuringJdr = source else viewModel.configure(source)
                        }.padding(horizontal = 4.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { deleting = pkg.entry }, enabled = !state.loading) {
                        Text("移除源包", color = FluxTheme.colorScheme.onSurfaceVariant, style = FluxTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    if (showImportInfo) {
        AlertDialog(
            onDismissRequest = { showImportInfo = false },
            title = { Text("导入听书书源") },
            text = { Text("支持 Timbre 的 .jdr 源包和听书 JAR。JAR 文件需保留原名，例如 sources_by_pan123.jar。重新导入同一包会更新书源。") },
            confirmButton = {
                TextButton(onClick = {
                    showImportInfo = false
                    picker.launch(arrayOf("*/*"))
                }) { Text("选择文件") }
            },
            dismissButton = { TextButton(onClick = { showImportInfo = false }) { Text("取消") } },
        )
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除书源包？") },
            text = { Text("该包内的书源将无法继续解析，听书进度和配置会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.remove(entry)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
    configuringJdr?.let { source -> JdrSourceDialog(source, viewModel.repository) { configuringJdr = null } }
    state.configItems?.let { items ->
        SourceConfigDialog(
            state.source!!.id, state.source!!.name, items, state.loading, viewModel::dismissConfig, viewModel::saveConfig,
            viewModel::configAction, state.configRevision, state.error, state.loginState,
            message = state.configMessage,
            onRetry = viewModel::retry,
        )
    }
}

@Composable
private fun SourceConfigDialog(
    sourceId: String,
    sourceName: String,
    items: List<ConfigItem>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
    onAction: (() -> Unit, Map<String, String>) -> Unit,
    revision: Int,
    error: String?,
    loginState: Boolean?,
    message: String?,
    onRetry: () -> Unit,
) {
    var values by remember(sourceId, items, revision) {
        mutableStateOf(
            items.filterNot { it is ConfigItem.Button }.associate { item ->
                val default = when (item) {
                    is ConfigItem.Text -> item.default
                    is ConfigItem.Switch -> item.default.toString()
                    is ConfigItem.Select -> item.default
                    is ConfigItem.MultiSelect -> item.default.joinToString(",")
                    else -> ""
                }
                item.key to SourceHost.getString("$sourceId.${item.key}", default).orEmpty()
            },
        )
    }
    fun update(key: String, value: String) {
        values = values + (key to value)
    }
    SourceSettingsSheet(title = sourceName, busy = busy, onDismiss = onDismiss, onSave = { onSave(values) }) {
        error?.let { SourceErrorNotice(it, onRetry = onRetry.takeUnless { busy }) }
        message?.let { SourceStatusCard("书源提示", it) }
        if (loginState != null) {
            SourceStatusCard(if (loginState) "已登录" else "尚未登录")
        }
        if (items.isEmpty()) Text("该书源没有配置项", color = FluxTheme.colorScheme.onSurfaceVariant)
        items.filterNot { it is ConfigItem.Button && isSourceLoginAction(it.label) }.forEach { item ->
            val value = values[item.key].orEmpty()
            when (item) {
                is ConfigItem.Text -> SourceConfigField(
                    label = item.label,
                    value = value,
                    onValueChange = { update(item.key, it) },
                    enabled = !busy,
                    secret = listOf("password", "token", "cookie", "authorization").any { item.key.contains(it, true) },
                )
                is ConfigItem.Switch -> SourceConfigSwitch(item.label, value == "true", !busy) { update(item.key, it.toString()) }
                is ConfigItem.Select -> SourceConfigOptions(item.label, item.options, setOf(value), !busy) { update(item.key, it) }
                is ConfigItem.MultiSelect -> SourceConfigOptions(item.label, item.options, value.split(',').toSet(), !busy) { option ->
                    val selected = value.split(',').filter { it.isNotBlank() }.toMutableSet()
                    if (!selected.remove(option)) selected.add(option)
                    update(item.key, selected.joinToString(","))
                }
                is ConfigItem.Button -> OutlinedButton(onClick = { onAction(item.click, values) }, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(item.label) }
            }
        }
    }
}
