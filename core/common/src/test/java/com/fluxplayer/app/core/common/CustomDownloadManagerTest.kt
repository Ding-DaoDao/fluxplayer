package com.fluxplayer.app.core.common

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CustomDownloadManagerTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var server: HttpServer
    private val executor = Executors.newCachedThreadPool()
    private val manager = CustomDownloadManager()

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    @Test
    fun completedDownloadPreservesExistingFileAndPublishesExactBytes() = runBlocking {
        val content = ByteArray(100_000) { (it % 251).toByte() }
        server.createContext("/file") { exchange ->
            exchange.sendResponseHeaders(200, content.size.toLong())
            exchange.responseBody.use { it.write(content) }
        }
        val existing = folder.newFile("video.bin").apply { writeText("existing") }
        val result = withTimeout(10_000) { manager.download(url("/file"), existing.name, folder.root) }.getOrThrow()
        assertEquals("existing", existing.readText())
        assertArrayEquals(content, File(result).readBytes())
        assertFalse(folder.root.listFiles()!!.any { it.extension == "part" })
    }

    @Test
    fun cancellingOneDownloadClosesItWithoutCancellingAnother() = runBlocking {
        val received = CompletableDeferred<Unit>()
        val releaseServer = CountDownLatch(1)
        server.createContext("/slow") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 1_000_000)
                exchange.responseBody.use {
                    it.write(ByteArray(16_384))
                    it.flush()
                    releaseServer.await(10, TimeUnit.SECONDS)
                }
            } finally {
                exchange.close()
            }
        }
        server.createContext("/fast") { exchange ->
            exchange.sendResponseHeaders(200, 2)
            exchange.responseBody.use { it.write(byteArrayOf(1, 2)) }
        }
        val slow = async { manager.download(url("/slow"), "slow.bin", folder.root, onProgress = { received.complete(Unit) }) }
        try {
            withTimeout(5_000) { received.await() }
            val fast = async { manager.download(url("/fast"), "fast.bin", folder.root) }
            withTimeout(5_000) { slow.cancelAndJoin() }
            val completed = withTimeout(5_000) { fast.await() }.getOrThrow()
            assertArrayEquals(byteArrayOf(1, 2), File(completed).readBytes())
            withTimeout(5_000) {
                while (folder.root.listFiles()!!.any { it.extension == "part" }) delay(20)
            }
            assertFalse(File(folder.root, "slow.bin").exists())
        } finally {
            releaseServer.countDown()
            slow.cancelAndJoin()
        }
    }

    @Test
    fun truncatedResponseNeverPublishesFinalFile() = runBlocking {
        server.createContext("/broken") { exchange ->
            try {
                exchange.sendResponseHeaders(200, 100)
                exchange.responseBody.write(byteArrayOf(1, 2, 3))
            } finally {
                exchange.close()
            }
        }
        val result = withTimeout(35_000) { manager.download(url("/broken"), "broken.bin", folder.root) }
        assertTrue(result.isFailure)
        assertTrue(folder.root.listFiles()!!.isEmpty())
    }
}
