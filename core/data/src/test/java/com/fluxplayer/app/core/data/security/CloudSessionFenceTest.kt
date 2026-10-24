package com.fluxplayer.app.core.data.security

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSessionFenceTest {
    @Test fun staleResponseCannotCommitAfterLogoutEvenWithSameToken() {
        val provider = UUID.randomUUID().toString()
        val ticket = CloudSessionFence.current(provider)
        var written = false
        CloudSessionFence.advance(provider)
        assertFalse(CloudSessionFence.commit(provider, ticket) { written = true })
        assertFalse(written)
    }

    @Test fun differentProvidersDoNotInvalidateEachOther() {
        val provider = UUID.randomUUID().toString()
        val ticket = CloudSessionFence.current(provider)
        CloudSessionFence.advance(UUID.randomUUID().toString())
        assertTrue(CloudSessionFence.commit(provider, ticket) {})
        assertEquals(ticket, CloudSessionFence.current(provider))
    }

    @Test fun sharedRefreshMutexIsTheSameAcrossClients() {
        val provider = UUID.randomUUID().toString()
        assertTrue(CloudSessionFence.refreshMutex(provider) === CloudSessionFence.refreshMutex(provider))
    }
}
