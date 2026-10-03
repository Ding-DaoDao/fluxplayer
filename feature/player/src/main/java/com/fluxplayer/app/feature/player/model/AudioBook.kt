package com.fluxplayer.app.feature.player.model

import android.net.Uri

/**
 * 一本书 = 一个子文件夹，包含多个音频章节 + 一个封面图
 */
data class AudioBook(
    val title: String,
    val folderPath: String,
    val coverUri: Uri?,
    val chapterCount: Int,
    val chapters: List<AudioChapter>,
)

data class AudioChapter(
    val title: String,
    val uri: Uri,
    val size: Long,
    val duration: Long = 0L,
)
