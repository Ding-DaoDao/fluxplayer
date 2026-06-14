package com.fluxplayer.app.feature.videopicker.composables

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.core.ui.components.CancelButton
import com.fluxplayer.app.core.ui.components.DoneButton
import com.fluxplayer.app.core.ui.components.NextDialog
import com.fluxplayer.app.core.ui.theme.FluxTheme

/**
 * 共享的分享浏览弹窗 —— 天翼云盘和123云盘共用。
 *
 * @param T 面包屑数据类型（C189Breadcrumb / Pan123Breadcrumb，两者字段完全一致）
 */
@Composable
fun <T> SharedShareBrowseDialog(
    title: String,
    items: List<WebDavResource>,
    targetBreadcrumbs: List<T>,
    breadcrumbLabel: (T) -> String,
    isLoading: Boolean,
    canNavigateUp: Boolean,
    hasMore: Boolean = false,
    subtitleProvider: @Composable (WebDavResource) -> String = { buildItemSubtitle(it) },
    onItemClick: (WebDavResource) -> Unit,
    onSaveSelected: (Set<Int>) -> Unit,
    onBack: () -> Unit,
    onNavigateUp: () -> Unit = onBack,
    onChangeTarget: () -> Unit,
    onDismiss: () -> Unit,
    onTargetBreadcrumbClick: (Int) -> Unit = {},
) {
    var selectedIndices by remember { mutableStateOf<Set<Int>>(emptySet()) }

    BackHandler(enabled = true) { onBack() }

    NextDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 520.dp)) {
                // 转存目的地址面包屑
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (canNavigateUp) {
                        TextButton(
                            onClick = onNavigateUp,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                        ) {
                            Text("‹", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Text(
                        text = "转存到:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        targetBreadcrumbs.forEachIndexed { index, crumb ->
                            val isLast = index == targetBreadcrumbs.lastIndex
                            TextButton(
                                onClick = { if (!isLast) onTargetBreadcrumbClick(index) },
                                enabled = !isLast,
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp),
                            ) {
                                Text(
                                    text = breadcrumbLabel(crumb),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = FluxTheme.colorScheme.primary,
                                )
                            }
                            if (index < targetBreadcrumbs.lastIndex) {
                                Text(" › ", style = MaterialTheme.typography.labelSmall, color = FluxTheme.colorScheme.primary)
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        onClick = onChangeTarget,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Text(
                            text = "更改",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))

                // 选择控制栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { selectedIndices = items.indices.toSet() },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Text("全选", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = {
                            selectedIndices = items.indices.toSet() - selectedIndices
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Text("反选", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(
                        onClick = { selectedIndices = emptySet() },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Text("取消", style = MaterialTheme.typography.labelSmall)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(bottom = 4.dp))

                // 文件列表
                when {
                    isLoading && items.isEmpty() -> {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            itemsIndexed(items) { index, item ->
                                val isSelected = index in selectedIndices
                                Card(
                                    onClick = {
                                        if (item.isDirectory) {
                                            onItemClick(item)
                                        } else {
                                            selectedIndices = if (isSelected) {
                                                selectedIndices - index
                                            } else {
                                                selectedIndices + index
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                    ),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { checked ->
                                                selectedIndices = if (checked) {
                                                    selectedIndices + index
                                                } else {
                                                    selectedIndices - index
                                                }
                                            },
                                        )
                                        FileTypeTextIcon(item = item)
                                        Spacer(Modifier.width(10.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                text = item.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            val subtitle = subtitleProvider(item)
                                            if (subtitle.isNotBlank()) {
                                                Text(
                                                    text = subtitle,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        }
                                        if (item.isDirectory) {
                                            Text(
                                                text = "›",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.titleMedium,
                                            )
                                        }
                                    }
                                }
                            }

                            if (hasMore && items.isNotEmpty()) {
                                item {
                                    Box(
                                        Modifier.fillMaxWidth().padding(12.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "仅显示前 ${items.size} 项",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSaveSelected(selectedIndices) },
                enabled = selectedIndices.isNotEmpty(),
            ) {
                Text("转存 (${selectedIndices.size})")
            }
        },
        dismissButton = null,
    )
}

/**
 * 共享的转存目标文件夹选择器 —— 天翼云盘和123云盘共用。
 */
@Composable
fun <T> SharedShareTargetPickerDialog(
    folders: List<WebDavResource>,
    isLoading: Boolean,
    path: List<T>,
    breadcrumbLabel: (T) -> String,
    onDismiss: () -> Unit,
    onConfirmCurrent: () -> Unit,
    onNavigateToFolder: (String, String) -> Unit,
    onNavigateUp: () -> Unit,
    onNavigateToIndex: (Int) -> Unit,
) {
    NextDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择转存目标") },
        content = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                // 路径面包屑
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    path.forEachIndexed { index, crumb ->
                        val isLast = index == path.lastIndex
                        TextButton(
                            onClick = { if (!isLast) onNavigateToIndex(index) },
                            enabled = !isLast,
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp),
                        ) {
                            Text(
                                text = breadcrumbLabel(crumb),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                color = FluxTheme.colorScheme.primary,
                            )
                        }
                        if (index < path.lastIndex) {
                            Text(" › ", style = MaterialTheme.typography.labelSmall, color = FluxTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (path.size > 1) {
                        TextButton(onClick = onNavigateUp) {
                            Text("上级", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                HorizontalDivider()

                if (isLoading) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (folders.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("此目录下无子文件夹", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        itemsIndexed(folders) { _, folder ->
                            TextButton(
                                onClick = { onNavigateToFolder(folder.path, folder.name) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("📁", style = MaterialTheme.typography.titleMedium)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = folder.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            DoneButton(onClick = onConfirmCurrent)
        },
        dismissButton = {
            CancelButton(onClick = onDismiss)
        },
    )
}

/** 简单的文件类型图标（文字版），两个 ShareBrowseDialog 共用 */
@Composable
private fun FileTypeTextIcon(item: WebDavResource) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val icon = when {
            item.isDirectory -> "📁"
            item.isVideo -> "🎬"
            item.isAudio -> "🎵"
            item.isImage -> "🖼"
            else -> "📄"
        }
        Text(icon, style = MaterialTheme.typography.titleSmall)
    }
}
