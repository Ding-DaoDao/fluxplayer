package com.fluxplayer.app.core.data.pan123

/**
 * 分享链接中的文件元数据（来自 shareFileDetails API）
 */
data class Pan123ShareFileItem(
    val fileId: String,
    val fileName: String,
    // 1 表示文件夹，0 表示文件。
    val type: Int,
    val size: Long,
    val etag: String,
    val s3keyFlag: String = "",
    val storageNode: String = "",
) {
    val isDirectory: Boolean get() = type == 1
}
