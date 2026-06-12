package com.fluxplayer.app.core.data.yun139

data class Yun139FileItem(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val isDir: Boolean,
    val createDate: String,
    val lastOpTime: String,
    val contentType: String,
    val thumbnailUrl: String? = null
) {
    val isVideo: Boolean
        get() {
            val videoExts = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "rmvb")
            return videoExts.any { fileName.endsWith(it, ignoreCase = true) }
        }
}
