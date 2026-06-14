package com.fluxplayer.app.core.data.cloud189

data class C189FileItem(
    val id: String,
    val name: String,
    val isDir: Boolean,
    val size: Long = 0,
    val lastOpTime: String = "",
    val createDate: String = "",
    val fileCount: Int = 0,
    val folderSize: Long = 0,
    val mediaType: Int = -1,
    val thumbnailUrl: String? = null,
    val md5: String = "",
) {
    val isVideo: Boolean
        get() {
            if (isDir) return false
            val videoExts = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts", "rmvb", "rm", "3gp", "mpeg", "mpg", "vob", "iso")
            return videoExts.any { name.endsWith(it, ignoreCase = true) }
        }
}
