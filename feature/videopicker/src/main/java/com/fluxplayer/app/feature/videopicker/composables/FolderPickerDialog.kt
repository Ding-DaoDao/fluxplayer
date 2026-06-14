package com.fluxplayer.app.feature.videopicker.composables

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.core.ui.designsystem.NextIcons

/**
 * 面包屑条目：标签 + 文件夹 ID
 */
private data class PickerBreadcrumb(val label: String, val folderId: String)

/**
 * 移动/复制目标文件夹选择器对话框
 *
 * 当 [pendingAction] 为 "move" 或 "copy" 时显示此对话框，
 * 允许用户浏览文件夹树并选择目标位置。
 *
 * 内置面包屑导航栈，自动管理当前目录状态。
 */
@Composable
fun FolderPickerDialog(
    action: String, // "move" or "copy"
    folders: List<WebDavResource>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (targetFolderId: String) -> Unit,
    onNavigateToFolder: (folderId: String) -> Unit,
    onCreateFolder: (parentFolderId: String, name: String) -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }

    // 面包屑栈：从根目录开始
    var breadcrumbs by remember { mutableStateOf(listOf(PickerBreadcrumb("根目录", ""))) }

    val currentFolderId = breadcrumbs.lastOrNull()?.folderId ?: ""
    val title = if (action == "move") "移动到..." else "复制到..."
    val buttonLabel = if (action == "move") "移动到此处" else "复制到此处"

    // 首次加载根目录
    LaunchedEffect(Unit) { onNavigateToFolder("") }

    // 返回键：多级面包屑时回退，否则关闭
    BackHandler(enabled = true) {
        if (breadcrumbs.size > 1) {
            val truncated = breadcrumbs.dropLast(1)
            breadcrumbs = truncated
            onNavigateToFolder(truncated.last().folderId)
        } else {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.8f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 标题栏
                TopAppBar(
                    title = {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        TextButton(onClick = onDismiss) {
                            Text("取消")
                        }
                    },
                )

                // 面包屑导航行
                if (breadcrumbs.size > 1) {
                    BreadcrumbRow(
                        breadcrumbs = breadcrumbs,
                        onNavigateToIndex = { index ->
                            breadcrumbs = breadcrumbs.take(index + 1)
                            onNavigateToFolder(breadcrumbs.last().folderId)
                        },
                    )
                }

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
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            items(folders, key = { it.path }) { folder ->
                                ListItem(
                                    headlineContent = {
                                        Text(
                                            text = folder.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    leadingContent = {
                                        Icon(
                                            imageVector = NextIcons.Folder,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    trailingContent = {
                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                        )
                                    },
                                    modifier = Modifier.clickable(
                                        onClick = {
                                            breadcrumbs = breadcrumbs + PickerBreadcrumb(folder.name, folder.path)
                                            onNavigateToFolder(folder.path)
                                        }
                                    ),
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()

                // 底部操作栏
                BottomActionBar(
                    breadcrumbs = breadcrumbs,
                    buttonLabel = buttonLabel,
                    showCreateDialog = showCreateDialog,
                    onCreateFolderClick = { showCreateDialog = true },
                    onConfirmClick = { onConfirm(currentFolderId) },
                )
            }
        }

        // 新建文件夹对话框
        if (showCreateDialog) {
            CreateFolderDialog(
                onDismiss = { showCreateDialog = false },
                onCreate = { name ->
                    onCreateFolder(currentFolderId, name)
                    showCreateDialog = false
                }
            )
        }
    }
}

/**
 * 面包屑导航行 —— 类似 CloudBrowserPanel 中的 BreadcrumbBar
 */
@Composable
private fun BreadcrumbRow(
    breadcrumbs: List<PickerBreadcrumb>,
    onNavigateToIndex: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        breadcrumbs.forEachIndexed { index, crumb ->
            TextButton(
                onClick = { onNavigateToIndex(index) },
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = crumb.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (index == breadcrumbs.lastIndex) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (index == breadcrumbs.lastIndex)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (index < breadcrumbs.lastIndex) {
                Text(
                    text = "›",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * 底部操作栏：当前路径预览 + 新建文件夹 + 确认按钮
 */
@Composable
private fun BottomActionBar(
    breadcrumbs: List<PickerBreadcrumb>,
    buttonLabel: String,
    showCreateDialog: Boolean,
    onCreateFolderClick: () -> Unit,
    onConfirmClick: () -> Unit,
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 路径预览
            Column(modifier = Modifier.weight(1f)) {
                val pathText = breadcrumbs.joinToString(" › ") { it.label }
                Text(
                    text = pathText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.width(8.dp))

            // 新建文件夹
            TextButton(onClick = onCreateFolderClick) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "新建文件夹",
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            // 确认按钮
            Button(onClick = onConfirmClick) {
                Text(text = buttonLabel)
            }
        }
    }
}

/**
 * 新建文件夹对话框
 */
@Composable
fun CreateFolderDialog(
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
