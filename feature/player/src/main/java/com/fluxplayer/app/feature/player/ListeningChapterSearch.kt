package com.fluxplayer.app.feature.player

/** 保留原章节索引，筛选后点击仍播放正确章节。 */
internal fun matchingListeningChapters(names: List<String>, query: String): List<Int> {
    val text = query.trim()
    if (text.isEmpty()) return names.indices.toList()
    return names.indices.filter { names[it].contains(text, ignoreCase = true) }
}

internal fun listeningChapterNumber(text: String, count: Int): Int? = text.trim().toIntOrNull()
    ?.takeIf { it in 1..count }?.minus(1)
