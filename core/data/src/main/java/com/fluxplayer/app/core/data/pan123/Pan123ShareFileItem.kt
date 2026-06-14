package com.fluxplayer.app.core.data.pan123

/**
 * 分享链接中的文件元数据（来自 shareFileDetails API）
 */
data class Pan123ShareFileItem(
    val fileId: String,
    val fileName: String,
    val type: Int,   // 1=文件夹, 0=文件
    val size: Long,
    val etag: String,
) {
    val isDirectory: Boolean get() = type == 1
}
