package dev.anilbeesetti.nextplayer.core.model

data class WebDavResource(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: String = "",
) {
    val isVideo: Boolean
        get() = !isDirectory && VIDEO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    companion object {
        val VIDEO_EXTENSIONS = listOf(
            ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv",
            ".webm", ".m4v", ".3gp", ".ts", ".mts", ".m2ts",
        )
    }
}
