package dev.anilbeesetti.nextplayer.feature.player.danmaku

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import dev.anilbeesetti.nextplayer.core.model.DanmakuSource
import dev.anilbeesetti.nextplayer.core.model.DanmakuSourceType
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
                .background(Color(0xFF1A1A2E), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "弹幕源管理",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    modifier = Modifier.weight(1f),
                )
                if (!showAddForm) {
                    Button(
                        onClick = { showAddForm = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A0FF)),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("添加", color = Color.White)
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
        Text("添加自定义弹幕源", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("名称", color = Color.White.copy(alpha = 0.5f)) },
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00A0FF),
                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                cursorColor = Color(0xFF00A0FF),
            ),
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("API 地址，如 https://api.dandanplay.com", color = Color.White.copy(alpha = 0.5f)) },
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00A0FF),
                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                cursorColor = Color(0xFF00A0FF),
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton("取消", onClick = onCancel)
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onAdd(name, url) },
                enabled = name.isNotBlank() && url.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A0FF)),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("确认添加", color = Color.White)
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
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
            )
            Text(
                text = source.baseUrl,
                color = Color.White.copy(alpha = 0.4f),
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = source.enabled,
                onCheckedChange = { onToggleEnabled() },
            )
            if (source.type == DanmakuSourceType.CUSTOM) {
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF4444)),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Text("删除", color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun TextButton(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Text(text, color = Color(0xFF00A0FF))
    }
}
