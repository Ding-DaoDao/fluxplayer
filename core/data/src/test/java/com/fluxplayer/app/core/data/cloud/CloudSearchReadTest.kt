package com.fluxplayer.app.core.data.cloud

import com.fluxplayer.app.core.data.BaseCloudApiClient
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSearchReadTest {
    private class Reader : BaseCloudApiClient(OkHttpClient()) {
        suspend fun get(url: String) = executeRequest(Request.Builder().url(url).build())
    }

    @Test
    fun oneRetryBudgetIsSharedAcrossPagesAndHonorsRateLimit() = runBlocking {
        val calls = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val count = calls.incrementAndGet()
            val success = count == 2
            val body = if (success) "ok" else "limited"
            exchange.responseHeaders.add("Retry-After", "0")
            exchange.sendResponseHeaders(if (success) 200 else 503, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/"
            val reader = Reader()
            var limited = false
            val failure = runCatching {
                cloudSearchRead({}, { limited = true }) {
                    assertEquals("ok", reader.get(url + "page1"))
                    reader.get(url + "page2")
                }
            }.exceptionOrNull()
            assertTrue(failure is CloudSearchHttpException)
            assertTrue(limited)
            assertEquals(3, calls.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun cancellingSearchInterruptsAnActualBlockedResponseBody() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val workers = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = workers
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 100)
            exchange.responseBody.write(byteArrayOf(1))
            exchange.responseBody.flush()
            started.complete(Unit)
            release.await()
            exchange.close()
        }
        server.start()
        try {
            withTimeout(4000) {
                val job = launch(Dispatchers.IO) {
                    cloudSearchRead({}, {}) { Reader().get("http://127.0.0.1:${server.address.port}/") }
                }
                started.await()
                job.cancelAndJoin()
                assertTrue(job.isCancelled)
            }
        } finally {
            release.countDown()
            server.stop(0)
            workers.shutdownNow()
        }
    }
}
