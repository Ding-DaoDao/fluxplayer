package com.fluxplayer.app.core.data.pan123

data class Pan123FileItem(
    val fileId: String,
    val fileName: String,
    val type: Int,
    val size: Long,
    val category: Int = 0,
    val etag: String,
    val s3keyFlag: String,
    val downloadUrl: String,
    val createAt: String,
    val trashedAt: String,
    val starredStatus: Int,
    val thumbnailUrl: String? = null
) {
    val isDirectory: Boolean get() = type == 1
    val isVideo: Boolean get() = Regex("\\.(mp4|mkv|avi|rmvb|mov|flv|wmv|webm|m4v|ts)$", RegexOption.IGNORE_CASE).containsMatchIn(fileName)
    val isImage: Boolean get() = Regex("\\.(jpg|jpeg|png|webp|gif|bmp)$", RegexOption.IGNORE_CASE).containsMatchIn(fileName)
}
