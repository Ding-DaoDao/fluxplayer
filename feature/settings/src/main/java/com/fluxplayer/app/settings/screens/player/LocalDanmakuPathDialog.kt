package com.fluxplayer.app.settings.screens.player

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import java.io.File

@Composable
fun LocalDanmakuPathDialog(
    currentPath: String,
    onUpdatePath: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var path by remember { mutableStateOf(currentPath) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val resolvedPath = uriToFilePath(uri)
            if (resolvedPath != null) {
                path = resolvedPath
                error = null
            } else {
                error = "无法解析所选目录路径"
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本地弹幕目录") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("设置本地弹幕文件的默认浏览目录")
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = path,
                    onValueChange = {
                        path = it
                        error = null
                    },
                    label = { Text("目录路径") },
                    placeholder = { Text("/storage/emulated/0/Video") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            directoryPickerLauncher.launch(null)
                        },
                    ) {
                        Icon(
                            imageVector = NextIcons.Folder,
                            contentDescription = null,
                            modifier = Modifier.height(18.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("浏览目录")
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                )
                            },
                        ) {
                            Text("授予存储权限", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val dir = File(path)
                    if (path.isBlank()) {
                        error = "路径不能为空"
                    } else if (!dir.exists()) {
                        error = "目录不存在"
                    } else if (!dir.isDirectory) {
                        error = "路径不是目录"
                    } else {
                        onUpdatePath(path.trimEnd('/'))
                    }
                },
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/**
 * 将 OpenDocumentTree 返回的 content URI 转换为文件系统路径。
 * 处理常见的 primary storage URI 格式。
 */
private fun uriToFilePath(uri: Uri): String? {
    val docId = DocumentsContract.getTreeDocumentId(uri)
    // primary storage: content://com.android.externalstorage.documents/tree/primary%3AFoo
    // docId 格式: "primary:Foo" → /storage/emulated/0/Foo
    if (docId.startsWith("primary:")) {
        val relativePath = docId.removePrefix("primary:")
        return "/storage/emulated/0/$relativePath"
    }
    // 尝试从 URI path 解析
    val path = uri.path
    if (path != null) {
        val treeIndex = path.indexOf("/tree/")
        if (treeIndex >= 0) {
            val treePath = path.substring(treeIndex + "/tree/".length)
                .replace("%2F", "/")
                .replace("%3A", ":")
            if (treePath.startsWith("primary:")) {
                return "/storage/emulated/0/${treePath.removePrefix("primary:")}"
            }
            // 其他存储设备（如 SD 卡）
            val segments = treePath.split(":")
            if (segments.size == 2) {
                val volume = segments[0]
                val subPath = segments[1]
                // 常见卷名到路径的映射
                val volumePath = when {
                    volume.matches(Regex("\\d+")) -> "/storage/$volume"
                    else -> "/storage/$volume"
                }
                return "$volumePath/$subPath"
            }
        }
    }
    return null
}
