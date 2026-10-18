package com.fluxplayer.app.core.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningHistoryResetTest {
    @Test
    fun clearedHistoryRejectsQueuedSavesFromTheOldSession() {
        assertFalse(ListeningHistoryReset.accepts(99, 100))
        assertFalse(ListeningHistoryReset.accepts(100, 100))
        assertFalse(ListeningHistoryReset.accepts(null, 100))
        assertTrue(ListeningHistoryReset.accepts(101, 100))
    }

    @Test
    fun booksWithoutAResetKeepExistingSavingBehavior() {
        assertTrue(ListeningHistoryReset.accepts(null, -1))
        assertTrue(ListeningHistoryReset.accepts(0, -1))
    }
}
