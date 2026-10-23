package com.fluxplayer.app.core.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSearchOptionsTest {
    @Test
    fun historyHasBoundedCaseInsensitiveEntriesAndIsolatedScopes() {
        var storage: String? = null
        val history = CloudSearchHistory({ storage }, { storage = it })
        repeat(15) { history.remember("account-a:personal", "term$it") }
        history.remember("account-a:personal", " TERM14 ")
        assertEquals(12, history.read("account-a:personal").size)
        assertEquals("TERM14", history.read("account-a:personal").first())
        history.remember("account-b:personal", "other")
        history.remember("account-a:family", "family")
        history.remove("account-a:personal", "TERM14")
        assertEquals(11, history.read("account-a:personal").size)
        history.clear("account-a:personal")
        assertTrue(history.read("account-a:personal").isEmpty())
        assertEquals(listOf("other"), history.read("account-b:personal"))
        assertEquals(listOf("family"), CloudSearchHistory({ storage }, {}).read("account-a:family"))
    }

    @Test
    fun historyEvictsOldestScopeAndRejectsInvalidTerms() {
        var storage: String? = null
        val history = CloudSearchHistory({ storage }, { storage = it })
        repeat(81) { history.remember("scope$it", "movie") }
        assertTrue(history.read("scope0").isEmpty())
        assertEquals(listOf("movie"), history.read("scope80"))
        history.remember("scope80", " ")
        history.remember("scope80", "a".repeat(121))
        assertEquals(listOf("movie"), history.read("scope80"))
    }

    @Test
    fun categoriesMatchWenxiIncludingSubtitlesAndOtherFiles() {
        fun file(name: String) = CloudRemoteFile("id", name, false)
        assertTrue(CloudSearchCategory.Document.matches(file("字幕.ASS")))
        assertTrue(CloudSearchCategory.Video.matches(file("stream.m3u8")))
        assertTrue(CloudSearchCategory.Other.matches(file("app.apk")))
        assertFalse(CloudSearchCategory.Other.matches(file("book.odt")))
        assertFalse(CloudSearchCategory.Video.matches(CloudRemoteFile("folder", "movie.mp4", true)))
        assertTrue(CloudSearchCategory.Folder.matches(CloudRemoteFile("folder", "movie.mp4", true)))
    }
}
