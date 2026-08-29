package com.fluxplayer.app.feature.player.danmaku

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import com.fluxplayer.app.core.ui.components.FluxSwitch
import com.fluxplayer.app.core.ui.components.FluxText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.fluxplayer.app.core.model.DanmakuSource
import com.fluxplayer.app.core.model.DanmakuSourceType
import com.fluxplayer.app.feature.player.R
import java.util.UUID

/**
 * 弹幕源管理底部弹出层。
 *
 * 显示已有源列表，支持添加自定义 API 源、删除、启用/禁用。
 */
@Composable
fun DanmakuSourceManageSheet(
    show: Boolean,
    sources: List<DanmakuSource>,
    onUpdateSources: (List<DanmakuSource>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!show) return

    var showAddForm by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        // 半透明背景 — 点击外部关闭
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(onClick = onDismiss),
        )

        // 底部内容面板
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(480.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.medium)
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FluxText(
                    text = "弹幕源管理",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (!showAddForm) {
                    Button(
                        onClick = { showAddForm = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        FluxText(stringResource(R.string.danmaku_source_add), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (showAddForm) {
                AddSourceForm(
                    onAdd = { name, url ->
                        val newSource = DanmakuSource(
                            id = UUID.randomUUID().toString(),
                            name = name,
                            baseUrl = url.trimEnd('/'),
                            type = DanmakuSourceType.CUSTOM,
                            token = "",
                        )
                        onUpdateSources(sources + newSource)
                        showAddForm = false
                    },
                    onCancel = { showAddForm = false },
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(sources) { source ->
                        SourceItem(
                            source = source,
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
    }
}

@Composable
private fun AddSourceForm(
    onAdd: (name: String, url: String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.danmaku_source_add_custom_title), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { FluxText(stringResource(R.string.danmaku_source_name), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) },
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { FluxText(stringResource(R.string.danmaku_source_api_hint), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)) },
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                cursorColor = MaterialTheme.colorScheme.primary,
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(stringResource(R.string.danmaku_source_cancel), onClick = onCancel)
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onAdd(name, url) },
                enabled = name.isNotBlank() && url.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(8.dp),
            ) {
                FluxText("确认添加", color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun SourceItem(
    source: DanmakuSource,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = source.name,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
            )
            Text(
                text = source.baseUrl,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FluxSwitch(
                checked = source.enabled,
                onCheckedChange = { onToggleEnabled() },
            )
            if (source.type == DanmakuSourceType.CUSTOM) {
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Text(stringResource(R.string.danmaku_source_delete), color = MaterialTheme.colorScheme.onError, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun TextButton(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        FluxText(text, color = MaterialTheme.colorScheme.primary)
    }
}
