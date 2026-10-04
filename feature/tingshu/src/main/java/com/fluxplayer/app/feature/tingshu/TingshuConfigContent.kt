package com.fluxplayer.app.feature.tingshu

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fluxplayer.app.core.tingshu.SourceHost
import com.fluxplayer.app.core.ui.theme.FluxTheme
import com.github.eprendre.tingshu.utils.ConfigItem

@Composable
fun TingshuConfigContent(modifier: Modifier = Modifier, viewModel: TingshuViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sources by viewModel.repository.sources.collectAsStateWithLifecycle()
    val packages by viewModel.repository.packages.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<String?>(null) }
    var showImportInfo by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importSource(uri)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("听书书源", style = FluxTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            TextButton(onClick = { showImportInfo = true }, enabled = !state.loading) { Text("导入书源") }
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.error?.let { Text(it, color = FluxTheme.colorScheme.error) }
        if (packages.isEmpty()) Text("暂无书源。导入后将在听书首页显示。", style = FluxTheme.typography.bodyMedium)
        packages.forEach { pkg ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pkg.entry, style = FluxTheme.typography.titleSmall)
                            Text(if (pkg.enabled) "已启用" else "已停用", style = FluxTheme.typography.bodySmall)
                        }
                        Switch(pkg.enabled, { viewModel.enable(pkg.entry, it) }, enabled = !state.loading)
                    }
                    pkg.error?.let { Text(it, color = FluxTheme.colorScheme.error) }
                    sources.filter { it.packageEntry == pkg.entry }.forEach { source ->
                        if (source.id.startsWith("jdr:")) {
                            Text(source.name, modifier = Modifier.padding(vertical = 8.dp))
                        } else {
                            TextButton(onClick = { viewModel.configure(source) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                                Text("${source.name} · 配置")
                            }
                        }
                    }
                    TextButton(onClick = { deleting = pkg.entry }, enabled = !state.loading) { Text("删除书源包") }
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
    state.configItems?.let { items ->
        SourceConfigDialog(state.source!!.id, items, state.loading, viewModel::dismissConfig, viewModel::saveConfig, viewModel::configAction, state.configRevision, state.error)
    }
}

@Composable
private fun SourceConfigDialog(
    sourceId: String,
    items: List<ConfigItem>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
    onAction: (() -> Unit) -> Unit,
    revision: Int,
    error: String?,
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
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("书源配置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (items.isEmpty()) Text("该书源没有配置项")
                error?.let { Text(it, color = FluxTheme.colorScheme.error) }
                items.forEach { item ->
                    val value = values[item.key].orEmpty()
                    when (item) {
                        is ConfigItem.Text -> OutlinedTextField(
                            value = value,
                            onValueChange = { update(item.key, it) },
                            label = { Text(item.label) },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (item.key.contains("password", true) || item.key.contains("token", true) || item.key.contains("cookie", true)) {
                                PasswordVisualTransformation()
                            } else {
                                VisualTransformation.None
                            },
                        )
                        is ConfigItem.Switch -> Row {
                            Switch(checked = value == "true", onCheckedChange = { update(item.key, it.toString()) }, enabled = !busy)
                            Text(item.label, modifier = Modifier.padding(12.dp))
                        }
                        is ConfigItem.Select -> {
                            Text(item.label)
                            item.options.forEach { option ->
                                TextButton(onClick = { update(item.key, option) }, enabled = !busy) {
                                    Text(if (value == option) "✓ $option" else option)
                                }
                            }
                        }
                        is ConfigItem.MultiSelect -> {
                            Text(item.label)
                            item.options.forEach { option ->
                                Row {
                                    Checkbox(
                                        checked = option in value.split(','),
                                        enabled = !busy,
                                        onCheckedChange = { checked ->
                                            val selected = value.split(',').filter { it.isNotBlank() }.toMutableSet()
                                            if (checked) selected.add(option) else selected.remove(option)
                                            update(item.key, selected.joinToString(","))
                                        },
                                    )
                                    Text(option, Modifier.padding(top = 12.dp))
                                }
                            }
                        }
                        is ConfigItem.Button -> TextButton(onClick = { onAction(item.click) }, enabled = !busy) { Text(item.label) }
                    }
                }
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = { TextButton(onClick = { onSave(values) }, enabled = !busy) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}
