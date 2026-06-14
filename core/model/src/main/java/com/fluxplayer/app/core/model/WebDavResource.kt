package com.fluxplayer.app.core.model

data class WebDavResource(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: String = "",
    val thumbnailUrl: String? = null,
    val fileCount: Int? = null,
    val folderSize: Long = 0,
    val category: String = "",
    val createdAt: String = "",
) {
    val isVideo: Boolean
        get() = !isDirectory && VIDEO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    val isAudio: Boolean
        get() = !isDirectory && AUDIO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    val isImage: Boolean
        get() = !isDirectory && IMAGE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    val isDoc: Boolean
        get() = !isDirectory && DOC_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    val isArchive: Boolean
        get() = !isDirectory && ARCHIVE_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    val fileType: FileType
        get() = when {
            isDirectory -> FileType.FOLDER
            isVideo -> FileType.VIDEO
            isAudio -> FileType.AUDIO
            isImage -> FileType.IMAGE
            isDoc -> FileType.DOC
            isArchive -> FileType.ARCHIVE
            else -> FileType.UNKNOWN
        }

    val fileEmoji: String
        get() = when {
            isDirectory -> "📂"
            isVideo -> "🎬"
            isAudio -> "🎵"
            isDoc -> "📒"
            isImage -> "🖼️"
            isArchive -> "📦"
            else -> "❓"
        }

    val fileTypeLabel: String
        get() = when {
            isDirectory -> ""
            category.isNotBlank() -> when (category.lowercase()) {
                "video" -> "视频"
                "audio" -> "音频"
                "image" -> "图片"
                "doc" -> "文档"
                "archive" -> "压缩包"
                else -> ""
            }
            else -> when (fileType) {
                FileType.VIDEO -> "视频"
                FileType.AUDIO -> "音频"
                FileType.IMAGE -> "图片"
                FileType.DOC -> "文档"
                FileType.ARCHIVE -> "压缩包"
                else -> ""
            }
        }

    enum class FileType {
        FOLDER,
        VIDEO,
        AUDIO,
        IMAGE,
        DOC,
        ARCHIVE,
        UNKNOWN,
    }

    companion object {
        val VIDEO_EXTENSIONS = listOf(
            ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv",
            ".webm", ".m4v", ".3gp", ".ts", ".mts", ".m2ts",
        )
        val AUDIO_EXTENSIONS = listOf(
            ".mp3", ".flac", ".wav", ".aac", ".ogg", ".m4a", ".wma", ".opus", ".ape", ".alac",
        )
        val IMAGE_EXTENSIONS = listOf(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg", ".ico", ".tiff", ".avif",
        )
        val DOC_EXTENSIONS = listOf(
            ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".md", ".csv", ".rtf", ".epub",
        )
        val ARCHIVE_EXTENSIONS = listOf(
            ".zip",
            ".rar",
            ".7z",
            ".tar",
            ".gz",
            ".bz2",
            ".xz",
            ".iso",
        )
    }
}
