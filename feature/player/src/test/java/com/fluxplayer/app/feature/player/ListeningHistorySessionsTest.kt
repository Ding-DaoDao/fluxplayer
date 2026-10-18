package com.fluxplayer.app.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningHistorySessionsTest {
    @Test
    fun deletingOneBookStopsOnlyItsSessionAndClearAllStopsRemainingBooks() {
        val local = Any()
        val online = Any()
        val video = Any()
        val reset = mutableListOf<String>()
        try {
            ListeningHistorySessions.register(local, { "local:book" }, { reset.add("local") })
            ListeningHistorySessions.register(online, { "online-book" }, { reset.add("online") })
            ListeningHistorySessions.register(video, { null }, { reset.add("video") })
            ListeningHistorySessions.reset("local:book")
            assertEquals(listOf("local"), reset)
            ListeningHistorySessions.unregister(local)
            ListeningHistorySessions.reset()
            assertEquals(listOf("local", "online"), reset)
        } finally {
            listOf(local, online, video).forEach(ListeningHistorySessions::unregister)
        }
    }
}
