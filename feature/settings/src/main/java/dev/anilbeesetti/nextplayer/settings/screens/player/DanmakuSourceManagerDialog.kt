package dev.anilbeesetti.nextplayer.settings.screens.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.core.model.DanmakuSource
import dev.anilbeesetti.nextplayer.core.model.DanmakuSourceType
import java.util.UUID

@Composable
fun DanmakuSourceManagerDialog(
    sources: List<DanmakuSource>,
    onUpdateSources: (List<DanmakuSource>) -> Unit,
    onDismiss: () -> Unit,
) {
    var showAddForm by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "弹幕源管理",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                if (!showAddForm) {
                    Button(
                        onClick = { showAddForm = true },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("添加")
                    }
                }
            }
        },
        text = {
            if (showAddForm) {
                AddSourceForm(
                    onAdd = { name, url, appId, appSecret ->
                        val newSource = DanmakuSource(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            baseUrl = url.trimEnd('/'),
                            type = DanmakuSourceType.CUSTOM,
                            appId = appId,
                            token = appSecret,
                        )
                        onUpdateSources(sources + newSource)
                        showAddForm = false
                    },
                    onCancel = { showAddForm = false },
                )
            } else {
                if (sources.isEmpty()) {
                    Text("还没有弹幕源。点击「添加」按钮添加自定义弹幕源。")
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                    ) {
                        items(sources) { source ->
                            SourceRow(
                                source = source,
                                sources = sources,
                                onUpdateSources = onUpdateSources,
                                onToggleEnabled = {
                                    onUpdateSources(
                                        sources.map { s ->
                                            if (s.id == source.id) s.copy(enabled = !s.enabled) else s
                                        },
                                    )
                                },
                                onDelete = {
                                    onUpdateSources(sources.filter { it.id != source.id })
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("完成")
            }
        },
    )
}

@Composable
private fun AddSourceForm(
    onAdd: (name: String, url: String, appId: String, appSecret: String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var appSecret by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "添加自定义弹幕源",
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("名称，如「我的源」") },
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("API 地址，如 https://api.dandanplay.net") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = appId,
            onValueChange = { appId = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("AppId（弹弹play开放平台凭证，可选）") },
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = appSecret,
            onValueChange = { appSecret = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("AppSecret / Token（可选）") },
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) {
                Text("取消")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onAdd(name, url, appId, appSecret) },
                enabled = name.isNotBlank() && url.isNotBlank(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("确认添加")
            }
        }
    }
}

@Composable
private fun SourceRow(
    source: DanmakuSource,
    sources: List<DanmakuSource>,
    onUpdateSources: (List<DanmakuSource>) -> Unit,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit,
) {
    var showEdit by remember { mutableStateOf(false) }
    var editAppId by remember(source) { mutableStateOf(source.appId) }
    var editToken by remember(source) { mutableStateOf(source.token) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.name,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = source.baseUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (source.appId.isNotBlank()) {
                    Text(
                        text = "AppId: ${source.appId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showEdit = !showEdit }) {
                    Text(if (showEdit) "收起" else "凭证")
                }
                TextButton(onClick = onToggleEnabled) {
                    Text(if (source.enabled) "禁用" else "启用")
                }
                if (source.type == DanmakuSourceType.CUSTOM) {
                    TextButton(onClick = onDelete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (showEdit) {
            OutlinedTextField(
                value = editAppId,
                onValueChange = { editAppId = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                placeholder = { Text("AppId") },
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = editToken,
                onValueChange = { editToken = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("AppSecret / Token") },
                singleLine = true,
            )
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(
                onClick = {
                    onUpdateSources(
                        sources.map { s ->
                            if (s.id == source.id) s.copy(appId = editAppId, token = editToken) else s
                        }
                    )
                    showEdit = false
                },
            ) {
                Text("保存凭证")
            }
        }
    }
}
