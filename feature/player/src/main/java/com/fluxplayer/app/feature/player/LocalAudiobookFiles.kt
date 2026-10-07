package com.fluxplayer.app.feature.player

import com.fluxplayer.app.core.common.sortedByNaturalName
import java.io.File

/** 前台播放器与后台续播使用相同的章节筛选和自然排序。 */
internal fun scanAudioFiles(dir: File?): List<File> {
    if (dir == null || !dir.isDirectory) return emptyList()
    val extensions = setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "wma", "opus")
    return dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in extensions }?.sortedByNaturalName().orEmpty()
}
