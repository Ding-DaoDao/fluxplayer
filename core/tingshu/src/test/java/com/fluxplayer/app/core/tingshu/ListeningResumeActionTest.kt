package com.fluxplayer.app.core.tingshu

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningResumeActionTest {
    @Test
    fun coldStartRestoresSavedBookWithoutController() {
        assertEquals(ListeningResumeAction.Restore, listeningResumeAction(false, false, false, true, false))
    }

    @Test
    fun recreatedServiceWithEmptyQueueRestoresEvenWithStalePlayState() {
        assertEquals(ListeningResumeAction.Restore, listeningResumeAction(true, false, false, true, true))
    }

    @Test
    fun idlePlayerWithMediaMustPrepareBeforePlaying() {
        assertEquals(ListeningResumeAction.PrepareAndPlay, listeningResumeAction(true, true, false, true, true))
    }

    @Test
    fun endedChapterRewindsAndPausedChapterContinues() {
        assertEquals(ListeningResumeAction.Replay, listeningResumeAction(true, true, true, false, true))
        assertEquals(ListeningResumeAction.Play, listeningResumeAction(true, true, false, false, false))
        assertEquals(ListeningResumeAction.Pause, listeningResumeAction(true, true, false, false, true))
    }
}
