package com.fluxplayer.app.feature.player.service

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ParallelRangeDataSourceTest {
    private val payload = ByteArray(5 * 1024 * 1024 + 137) { (it % 251).toByte() }
    private val uri = Uri.parse("https://example.test/video#quarkPlay=true&quarkOriginal=true")

    @Test
    fun readsExactBytesWithThreeBoundedConnections() {
        val factory = FakeFactory()
        val source = ParallelRangeDataSource(factory)
        try {
            assertEquals(payload.size.toLong(), source.open(DataSpec(uri)))
            assertArrayEquals(payload, readAll(source))
            assertTrue(factory.ranges.get() > 3)
            assertTrue(factory.peak.get() <= 4)
            assertTrue(factory.conditional.get() >= 3)
        } finally {
            source.close()
        }
        assertEquals(0, factory.active.get())
    }

    @Test
    fun seekAndBoundedRequestsReturnOnlyRequestedBytes() {
        val source = ParallelRangeDataSource(FakeFactory())
        try {
            val start = 731_123
            val length = 2_300_001
            assertEquals(length.toLong(), source.open(DataSpec.Builder().setUri(uri).setPosition(start.toLong()).setLength(length.toLong()).build()))
            assertArrayEquals(payload.copyOfRange(start, start + length), readAll(source))
        } finally {
            source.close()
        }
    }

    @Test
    fun ignoredRangeOrMissingIdentityFallsBackToDirect() {
        for (factory in listOf(FakeFactory(ignoreRanges = true), FakeFactory(noIdentity = true))) {
            val source = ParallelRangeDataSource(factory)
            try {
                source.open(DataSpec(uri))
                assertArrayEquals(payload, readAll(source))
                assertEquals(2, factory.opens.get())
            } finally {
                source.close()
            }
        }
    }

    @Test
    fun invalidSegmentResumesWithoutDuplicatingBytes() {
        val source = ParallelRangeDataSource(FakeFactory(invalidSegments = true))
        try {
            source.open(DataSpec(uri))
            assertArrayEquals(payload, readAll(source))
        } finally {
            source.close()
        }
    }

    @Test
    fun changedFileCannotBeSplicedIntoOldPrefix() {
        val source = ParallelRangeDataSource(FakeFactory(changedFile = true))
        try {
            source.open(DataSpec(uri))
            try {
                readAll(source)
                fail("不应拼接已变化的文件")
            } catch (expected: IOException) {
                assertTrue(expected.message.orEmpty().contains("文件已变化"))
            }
        } finally {
            source.close()
        }
    }

    @Test
    fun closingCancelsOutstandingPrefetch() {
        val factory = FakeFactory(blockSegments = true)
        val source = ParallelRangeDataSource(factory)
        source.open(DataSpec(uri))
        assertTrue(factory.started.await(3, TimeUnit.SECONDS))
        source.close()
        assertTrue(factory.stopped.await(3, TimeUnit.SECONDS))
        assertEquals(0, factory.active.get())
    }

    @Test
    fun transcodedStreamDoesNotStartRangeWorkers() {
        val factory = FakeFactory()
        val source = ParallelRangeDataSource(factory)
        try {
            source.open(DataSpec(Uri.parse("https://example.test/video.m3u8#quarkPlay=true")))
            assertEquals(1, factory.opens.get())
            assertEquals(0, factory.ranges.get())
        } finally {
            source.close()
        }
    }

    private fun readAll(source: DataSource): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(32768)
        while (true) {
            val size = source.read(buffer, 0, buffer.size)
            if (size == C.RESULT_END_OF_INPUT) return output.toByteArray()
            output.write(buffer, 0, size)
        }
    }

    private inner class FakeFactory(
        val ignoreRanges: Boolean = false,
        val noIdentity: Boolean = false,
        val invalidSegments: Boolean = false,
        val changedFile: Boolean = false,
        val blockSegments: Boolean = false,
    ) : DataSource.Factory {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val opens = AtomicInteger()
        val ranges = AtomicInteger()
        val conditional = AtomicInteger()
        val started = CountDownLatch(3)
        val stopped = CountDownLatch(3)

        override fun createDataSource(): DataSource = object : DataSource {
            var request: DataSpec? = null
            var cursor = 0
            var limit = 0
            var opened = false
            override fun addTransferListener(transferListener: TransferListener) = Unit
            override fun open(dataSpec: DataSpec): Long {
                request = dataSpec
                cursor = dataSpec.position.toInt()
                limit = if (dataSpec.length == C.LENGTH_UNSET.toLong()) payload.size else minOf(payload.size.toLong(), dataSpec.position + dataSpec.length).toInt()
                opened = true
                opens.incrementAndGet()
                peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                if (dataSpec.length != C.LENGTH_UNSET.toLong()) ranges.incrementAndGet()
                if ("If-Range" in dataSpec.httpRequestHeaders) conditional.incrementAndGet()
                return (limit - cursor).toLong()
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (blockSegments && "If-Range" in request!!.httpRequestHeaders) {
                    started.countDown()
                    try {
                        CountDownLatch(1).await()
                    } finally {
                        stopped.countDown()
                    }
                }
                if (cursor == limit) return C.RESULT_END_OF_INPUT
                val count = minOf(length, limit - cursor)
                payload.copyInto(buffer, offset, cursor, cursor + count)
                cursor += count
                return count
            }
            override fun getUri(): Uri = this@ParallelRangeDataSourceTest.uri
            override fun getResponseHeaders(): Map<String, List<String>> {
                val spec = request!!
                val segment = "If-Range" in spec.httpRequestHeaders
                return buildMap {
                    if (!ignoreRanges) put("Content-Range", listOf("bytes ${if (invalidSegments && segment) 0 else spec.position}-${limit - 1}/${payload.size}"))
                    if (!noIdentity) put("ETag", listOf(if (changedFile && spec.position > 0) "\"changed\"" else "\"original\""))
                    put("Content-Type", listOf("video/mp4"))
                }
            }
            override fun close() {
                if (opened) {
                    opened = false
                    active.decrementAndGet()
                }
            }
        }
    }
}
