package com.fluxplayer.app.core.tingshu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningHistoryVisibilityTest {
    @Test
    fun removalHidesOldRecordUntilPlayedAgain() {
        val history = ListeningHistoryVisibility().remove("local:book", 100L)
        assertFalse(history.contains("local:book", 0L))
        assertFalse(history.contains("local:book", 100L))
        assertTrue(history.contains("local:book", 101L))
        assertTrue(history.contains("source:other", 50L))
    }

    @Test
    fun clearAlsoHidesRecordsOutsideVisiblePage() {
        val history = ListeningHistoryVisibility().remove("local:book", 50L).clear(100L)
        assertFalse(history.contains("local:book", 50L))
        assertFalse(history.contains("source:older", 0L))
        assertFalse(history.contains("source:recent", 100L))
        assertTrue(history.contains("source:recent", 101L))
    }
}
