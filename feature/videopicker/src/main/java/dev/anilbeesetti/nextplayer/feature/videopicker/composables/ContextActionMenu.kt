package dev.anilbeesetti.nextplayer.feature.videopicker.composables

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.anilbeesetti.nextplayer.core.model.WebDavResource

/**
 * 云盘文件/文件夹统一长按操作菜单
 */
@Composable
fun ContextActionMenu(
    item: WebDavResource,
    onDismiss: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onDownload: () -> Unit,
) {
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("移动") }, onClick = { onMove(); onDismiss() })
        DropdownMenuItem(text = { Text("复制") }, onClick = { onCopy(); onDismiss() })
        DropdownMenuItem(text = { Text("重命名") }, onClick = { onRename(); onDismiss() })
        DropdownMenuItem(text = { Text("删除") }, onClick = { onDelete(); onDismiss() })
        if (!item.isDirectory) {
            DropdownMenuItem(text = { Text("下载") }, onClick = { onDownload(); onDismiss() })
        }
    }
}
