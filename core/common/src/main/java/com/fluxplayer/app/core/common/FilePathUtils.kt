package com.fluxplayer.app.core.common

import android.net.Uri
import android.provider.DocumentsContract

/**
 * 将 OpenDocumentTree 返回的 URI 转换为文件系统绝对路径
 */
fun uriToFilePath(uri: Uri): String? {
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
