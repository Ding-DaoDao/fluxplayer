package com.fluxplayer.app.core.data.cloud

/** 搜索分类与文析的扩展名规则保持一致，筛选不影响对子目录的扫描。 */
enum class CloudSearchCategory(val label: String, private val extensions: Set<String> = emptySet()) {
    All("全部"),
    Video("视频", setOf("mp4", "mkv", "avi", "mov", "flv", "ts", "m3u8", "m4v", "webm", "wmv")),
    Document("文档", setOf("txt", "md", "json", "log", "csv", "srt", "lrc", "xml", "yaml", "yml", "ini", "conf", "vtt", "ass", "ssa", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods", "odp", "rtf", "epub")),
    Image("图片", setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif")),
    Audio("音频", setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")),
    Archive("压缩包", setOf("zip", "rar", "7z", "gz", "tar", "iso")),
    Folder("文件夹"),
    Other("其他"),
    ;

    fun matches(file: CloudRemoteFile): Boolean {
        if (this == All) return true
        if (file.directory) return this == Folder
        if (this == Folder) return false
        val ext = file.name.substringAfterLast('.').lowercase(java.util.Locale.ROOT)
        return if (this == Other) entries.none { it != All && it != Other && ext in it.extensions } else ext in extensions
    }
}
