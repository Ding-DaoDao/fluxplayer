package com.fluxplayer.app.feature.player.service

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxplayer.app.core.common.CloudPlayHeaders
import com.fluxplayer.app.core.data.quark.QuarkAuthProvider
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.Executors
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QuarkRangeHttpTest {
    @Test
    fun actualHttpRangesPreserveAuthAndRecoverFromTemporaryFailure() {
        withServer(503) { server ->
            val source = AuthAwareDataSourceFactory(InstrumentationRegistry.getInstrumentation().context).createDataSource()
            try {
                source.open(DataSpec(server.uri))
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(32768)
                while (true) {
                    val read = source.read(buffer, 0, buffer.size)
                    if (read == C.RESULT_END_OF_INPUT) break
                    result.write(buffer, 0, read)
                }
                assertArrayEquals(server.payload, result.toByteArray())
                val requests = server.requests.toList()
                assertTrue(requests.size >= 5)
                assertTrue(requests.all { it["cookie"] == "test-cookie" })
                assertTrue(requests.all { it["referer"] == "https://pan.quark.cn/" })
                assertTrue(requests.all { it["user-agent"] == "quark-range-test" })
                assertTrue(requests.any { it["if-range"] == "\"test-file\"" })
                assertEquals("bytes=0-262143", requests.first()["range"])
            } finally {
                source.close()
            }
        }
    }

    @Test
    fun unauthorizedUrlIsNotRequestedTwice() {
        withServer(403) { server ->
            val source = AuthAwareDataSourceFactory(InstrumentationRegistry.getInstrumentation().context).createDataSource()
            try {
                source.open(DataSpec(server.uri))
                fail("失效地址应立即报错")
            } catch (expected: HttpDataSource.InvalidResponseCodeException) {
                assertEquals(403, expected.responseCode)
                assertEquals(1, server.requests.size)
            } finally {
                source.close()
            }
        }
    }

    private fun withServer(firstStatus: Int, action: (RangeServer) -> Unit) {
        CloudPlayHeaders.clear()
        CloudPlayHeaders.activeProvider = "quark"
        QuarkAuthProvider.isActive = true
        QuarkAuthProvider.cookie = "test-cookie"
        QuarkAuthProvider.userAgent = "quark-range-test"
        QuarkAuthProvider.referer = "https://pan.quark.cn/"
        try {
            RangeServer(firstStatus).use(action)
        } finally {
            QuarkAuthProvider.clear()
            CloudPlayHeaders.clear()
        }
    }

    private class RangeServer(private val firstStatus: Int) : AutoCloseable {
        val payload = ByteArray(4 * 1024 * 1024 + 19) { (it % 251).toByte() }
        val requests = Collections.synchronizedList(mutableListOf<Map<String, String>>())
        private val server = ServerSocket(0, 20, java.net.InetAddress.getByName("127.0.0.1"))
        private val workers = Executors.newCachedThreadPool { Thread(it, "TestRangeServer").apply { isDaemon = true } }
        val uri: Uri = Uri.parse("http://127.0.0.1:${server.localPort}/video#quarkPlay=true&quarkOriginal=true")

        init {
            workers.execute {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (_: java.io.IOException) {
                        break
                    }
                    workers.execute {
                        socket.use {
                            val reader = it.getInputStream().bufferedReader()
                            reader.readLine()
                            val headers = buildMap {
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.isEmpty()) break
                                    put(line.substringBefore(':').lowercase(), line.substringAfter(':').trim())
                                }
                            }
                            val first = synchronized(requests) {
                                val empty = requests.isEmpty()
                                requests.add(headers)
                                empty
                            }
                            val output = it.getOutputStream()
                            if (first) {
                                output.write("HTTP/1.1 $firstStatus Failure\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            } else {
                                val range = Regex("bytes=(\\d+)-(\\d*)").matchEntire(headers["range"].orEmpty())!!
                                val start = range.groupValues[1].toInt()
                                val end = range.groupValues[2].toIntOrNull()?.coerceAtMost(payload.lastIndex) ?: payload.lastIndex
                                output.write(
                                    (
                                        "HTTP/1.1 206 Partial Content\r\nContent-Length: ${end - start + 1}\r\n" +
                                            "Content-Range: bytes $start-$end/${payload.size}\r\nETag: \"test-file\"\r\n" +
                                            "Content-Type: video/mp4\r\nConnection: close\r\n\r\n"
                                        ).toByteArray(),
                                )
                                output.write(payload, start, end - start + 1)
                            }
                            output.flush()
                        }
                    }
                }
            }
        }

        override fun close() {
            server.close()
            workers.shutdownNow()
        }
    }
}
