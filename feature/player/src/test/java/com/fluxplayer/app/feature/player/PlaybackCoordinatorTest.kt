package com.fluxplayer.app.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackCoordinatorTest {
    @Test
    fun switchingBothWaysPausesThePreviousService() {
        val coordinator = PlaybackCoordinator()
        val local = Any()
        val online = Any()
        var localPauses = 0
        var onlinePauses = 0
        coordinator.claim(local) { localPauses++ }
        coordinator.claim(online) { onlinePauses++ }
        assertEquals(1, localPauses)
        assertEquals(0, onlinePauses)
        coordinator.claim(local) { localPauses++ }
        assertEquals(1, onlinePauses)
    }

    @Test
    fun repeatedClaimDoesNotPauseItsOwnService() {
        val coordinator = PlaybackCoordinator()
        val owner = Any()
        var pauses = 0
        repeat(3) { coordinator.claim(owner) { pauses++ } }
        assertEquals(0, pauses)
    }

    @Test
    fun destroyingOldServiceDoesNotReleaseCurrentOwner() {
        val coordinator = PlaybackCoordinator()
        val local = Any()
        val online = Any()
        var onlinePauses = 0
        coordinator.claim(local) { }
        coordinator.claim(online) { onlinePauses++ }
        coordinator.release(local)
        coordinator.claim(local) { }
        assertEquals(1, onlinePauses)
    }

    @Test
    fun destroyedOwnerIsNotCalledAgain() {
        val coordinator = PlaybackCoordinator()
        val owner = Any()
        var pauses = 0
        coordinator.claim(owner) { pauses++ }
        coordinator.release(owner)
        coordinator.claim(Any()) { }
        assertEquals(0, pauses)
    }
}
