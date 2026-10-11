package com.fluxplayer.app.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListeningChapterSearchTest {
    @Test
    fun filteredResultsKeepOriginalIndexes() {
        val names = listOf("序章", "Chapter One", "第二章", "Chapter Three")
        assertEquals(listOf(1, 3), matchingListeningChapters(names, " chapter "))
        assertEquals(emptyList<Int>(), matchingListeningChapters(names, "不存在"))
        assertEquals(listOf(0, 1, 2, 3), matchingListeningChapters(names, ""))
    }

    @Test
    fun chapterNumbersAreOneBasedAndBounded() {
        assertEquals(0, listeningChapterNumber("1", 500))
        assertEquals(499, listeningChapterNumber("500", 500))
        listOf("0", "501", "-1", "文字", "9999999999999").forEach { assertNull(listeningChapterNumber(it, 500)) }
    }
}
