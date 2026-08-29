package com.fluxplayer.app.settings.screens.player

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.model.DanmakuSource
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.model.DanmakuSourceType
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
                    text = stringResource(R.string.danmaku_sources),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                if (!showAddForm) {
                    Button(
                        onClick = { showAddForm = true },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(stringResource(R.string.add))
                    }
                }
            }
        },
        text = {
            if (showAddForm) {
                AddSourceForm(
                    onAdd = { name, url ->
                        val newSource = DanmakuSource(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            baseUrl = url.trimEnd('/'),
                            type = DanmakuSourceType.CUSTOM,
                        )
                        onUpdateSources(sources + newSource)
                        showAddForm = false
                    },
                    onCancel = { showAddForm = false },
                )
            } else {
                if (sources.isEmpty()) {
                    Text(stringResource(R.string.danmaku_source_empty_hint))
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
                Text(stringResource(R.string.done))
            }
        },
    )
}

@Composable
private fun AddSourceForm(
    onAdd: (name: String, url: String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.danmaku_add_custom_title),
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.danmaku_name_placeholder)) },
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.danmaku_url_placeholder)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onAdd(name, url) },
                enabled = name.isNotBlank() && url.isNotBlank(),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(stringResource(R.string.confirm_add))
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
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = source.enabled,
                    onCheckedChange = { onToggleEnabled() },
                )
                if (source.type == DanmakuSourceType.CUSTOM) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
