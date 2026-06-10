package dev.anilbeesetti.nextplayer.feature.videopicker.composables

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.anilbeesetti.nextplayer.core.model.WebDavResource

/**
 * 移动/复制目标文件夹选择器对话框
 *
 * 当 [pendingAction] 为 "move" 或 "copy" 时显示此对话框，
 * 允许用户浏览文件夹树并选择目标位置。
 */
@Composable
fun FolderPickerDialog(
    action: String, // "move" or "copy"
    folders: List<WebDavResource>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    onNavigateToFolder: (WebDavResource) -> Unit,
    onCreateFolder: (String) -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.7f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 标题栏
                TopAppBar(
                    title = {
                        Text(
                            text = if (action == "move") "移动到..." else "复制到...",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        TextButton(onClick = onDismiss) {
                            Text("取消")
                        }
                    },
                    actions = {
                        TextButton(onClick = onConfirm) {
                            Text("确定")
                        }
                        IconButton(onClick = { showCreateDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "新建文件夹"
                            )
                        }
                    },
                )

                HorizontalDivider()

                // 内容区域
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    } else if (folders.isEmpty()) {
                        Text(
                            text = "此目录下没有子文件夹",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(folders, key = { it.path }) { folder ->
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            text = folder.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    modifier = Modifier.clickable { onNavigateToFolder(folder) },
                                )
                            }
                        }
                    }
                }
            }
        }

        // 新建文件夹对话框
        if (showCreateDialog) {
            CreateFolderDialog(
                onDismiss = { showCreateDialog = false },
                onCreate = { name ->
                    onCreateFolder(name)
                    showCreateDialog = false
                }
            )
        }
    }
}

/**
 * 新建文件夹对话框
 */
@Composable
private fun CreateFolderDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var folderName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建文件夹") },
        text = {
            OutlinedTextField(
                value = folderName,
                onValueChange = { folderName = it },
                label = { Text("文件夹名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (folderName.isNotBlank()) {
                        onCreate(folderName.trim())
                    }
                },
                enabled = folderName.isNotBlank(),
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
