package com.fluxplayer.app.feature.player

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestTaskTest {
    @Test
    fun lateResultCannotReplaceNewEpisode() = runTest {
        val task = LatestTask(this)
        val results = mutableListOf<String>()
        val errors = mutableListOf<Exception>()
        lateinit var oldRequest: Continuation<String>
        task.launch({ suspendCoroutine { oldRequest = it } }, { results.add(it) }, { errors.add(it) })
        runCurrent()
        task.launch({ "episode-3" }, { results.add(it) }, { errors.add(it) })
        runCurrent()
        oldRequest.resume("episode-2")
        runCurrent()
        assertEquals(listOf("episode-3"), results)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun oldFailureCannotDisableNewEpisode() = runTest {
        val task = LatestTask(this)
        val results = mutableListOf<String>()
        val errors = mutableListOf<Exception>()
        lateinit var oldRequest: Continuation<String>
        task.launch({ suspendCoroutine { oldRequest = it } }, { results.add(it) }, { errors.add(it) })
        runCurrent()
        task.launch({ "episode-3" }, { results.add(it) }, { errors.add(it) })
        runCurrent()
        oldRequest.resumeWithException(IllegalStateException("旧请求失败"))
        runCurrent()
        assertEquals(listOf("episode-3"), results)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun leavingPlayerDiscardsInFlightResult() = runTest {
        val task = LatestTask(this)
        val results = mutableListOf<String>()
        lateinit var pending: Continuation<String>
        task.launch({ suspendCoroutine { pending = it } }, { results.add(it) }, { throw it })
        runCurrent()
        task.cancel()
        pending.resume("episode-2")
        runCurrent()
        assertTrue(results.isEmpty())
    }
}
