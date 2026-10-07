package com.fluxplayer.app.feature.player

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalAudiobookFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun backgroundQueueKeepsNaturalChapterOrderAndFiltersNonAudioFiles() {
        val book = folder.newFolder("book")
        listOf("10.mp3", "2.mp3", "1.MP3", "cover.jpg", "notes.txt").forEach { book.resolve(it).writeText("") }
        book.resolve("3.mp3").mkdir()
        assertEquals(listOf("1.MP3", "2.mp3", "10.mp3"), scanAudioFiles(book).map { it.name })
    }

    @Test
    fun missingDirectoryDoesNotCreateAnInvalidQueue() {
        assertEquals(emptyList<java.io.File>(), scanAudioFiles(folder.root.resolve("missing")))
        assertEquals(emptyList<java.io.File>(), scanAudioFiles(null))
    }
}
