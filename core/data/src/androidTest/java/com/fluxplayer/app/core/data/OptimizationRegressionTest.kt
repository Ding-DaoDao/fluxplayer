package com.fluxplayer.app.core.data

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fluxplayer.app.core.data.cache.PlaybackCacheManager
import com.fluxplayer.app.core.data.danmaku.BilibiliDanmakuFetcher
import com.fluxplayer.app.core.data.danmaku.DanmakuApiClient
import com.fluxplayer.app.core.data.webdav.WebDavClient
import com.fluxplayer.app.core.database.MediaDatabase
import com.fluxplayer.app.core.database.converter.UriListConverter
import com.fluxplayer.app.core.model.DanmakuSource
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimizationRegressionTest {
    @Test
    fun webDavClosesSuccessFailureAndMalformedResponses() = runBlocking {
        for (status in listOf(200, 401)) {
            val bodies = mutableListOf<TrackedBody>()
            val client = WebDavClient(client(bodies, status, "<invalid"))
            client.listDirectory("https://test.invalid", "/", "")
            client.testConnection("https://test.invalid", "", "")
            client.createFolder("https://test.invalid", "/folder", "")
            client.delete("https://test.invalid", "/file", "")
            client.move("https://test.invalid", "/a", "/b", "")
            client.copy("https://test.invalid", "/a", "/b", "")
            client.put("https://test.invalid", "/file", byteArrayOf(1), "")
            client.get("https://test.invalid", "/file", "", maxBytes = 1)
            assertEquals(8, bodies.size)
            assertTrue(bodies.all { it.closed })
        }
    }

    @Test
    fun danmakuClosesFailureAndParsingResponses() = runBlocking {
        for (status in listOf(200, 403)) {
            val bodies = mutableListOf<TrackedBody>()
            val http = client(bodies, status, "invalid")
            val api = DanmakuApiClient(http)
            val source = DanmakuSource(id = "test", name = "test", baseUrl = "https://test.invalid")
            api.searchAnime(source, "test")
            api.getEpisodes(source, 1)
            api.downloadDanmaku(source, 1)?.close()
            BilibiliDanmakuFetcher(http).fetchDanmaku("https://www.bilibili.com/video/av1")?.close()
            assertTrue(bodies.size >= 4)
            assertTrue(bodies.all { it.closed })
        }
    }

    @Test
    fun bilibiliClosesXmlAndSegmentFailuresAfterCidResolution() = runBlocking {
        val bodies = mutableListOf<TrackedBody>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val resolving = request.url.encodedPath == "/x/web-interface/view"
            val body = TrackedBody(if (resolving) """{"data":{"cid":123}}""" else "invalid")
            bodies += body
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (request.url.encodedPath.endsWith(".so")) 403 else 200)
                .message("test").body(body).build()
        }.build()
        BilibiliDanmakuFetcher(http).fetchDanmaku("https://www.bilibili.com/video/av1")?.close()
        assertEquals(4, bodies.size)
        assertTrue(bodies.all { it.closed })
    }

    @Test
    fun cacheClearAndResizeKeepExistingDataSourceFactoriesUsable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "cache-regression-${UUID.randomUUID()}")
        val isolated = object : ContextWrapper(context) {
            override fun getCacheDir(): File = directory.apply { mkdirs() }
        }
        val manager = PlaybackCacheManager(isolated)
        val content = ByteArray(4096) { it.toByte() }
        try {
            val cache = manager.getCache(8192)
            var opens = 0
            val factory = CacheDataSource.Factory().setCache(cache)
                .setUpstreamDataSourceFactory(
                    DataSource.Factory {
                        opens++
                        ByteArrayDataSource(content)
                    },
                )
            fun read() {
                val source = factory.createDataSource()
                try {
                    source.open(DataSpec(Uri.parse("https://test.invalid/audio")))
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(1024)
                    while (true) {
                        val count = source.read(buffer, 0, buffer.size)
                        if (count == C.RESULT_END_OF_INPUT) break
                        output.write(buffer, 0, count)
                    }
                    assertTrue(content.contentEquals(output.toByteArray()))
                } finally {
                    source.close()
                }
            }
            read()
            assertEquals(4096L, cache.cacheSpace)
            manager.clearCache()
            assertEquals(0L, cache.cacheSpace)
            assertSame(cache, manager.getCache(16384))
            read()
            assertTrue(opens >= 2)
            assertSame(cache, manager.getCache(1024))
            assertTrue(cache.cacheSpace <= 1024)
        } finally {
            manager.release()
            directory.deleteRecursively()
        }
    }

    @Test
    fun concurrentMediaSettingsAndSubtitleAddsPreserveEveryField() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, MediaDatabase::class.java).build()
        try {
            val dao = database.mediumStateDao()
            val first = UriListConverter.fromListToString(listOf(Uri.parse("content://test/字幕,a.srt")))
            val second = UriListConverter.fromListToString(listOf(Uri.parse("content://test/b.srt")))
            repeat(10) { iteration ->
                val uri = "content://test/$iteration"
                val start = CompletableDeferred<Unit>()
                val writes: List<suspend () -> Unit> = listOf(
                    { dao.upsertPosition(uri, 12345, 1) },
                    { dao.upsertPlaybackSpeed(uri, 1.5f, 2) },
                    { dao.upsertAudioTrack(uri, 2, 3) },
                    { dao.upsertSubtitleTrack(uri, 3, 4) },
                    { dao.upsertZoom(uri, 1.25f, 5) },
                    { dao.upsertSubtitleDelay(uri, 250, 6) },
                    { dao.upsertSubtitleSpeed(uri, 0.9f, 7) },
                    { dao.upsertIntroOutro(uri, 100, 200, 8) },
                    { dao.upsertLastPlayedTime(uri, 9) },
                    { dao.appendExternalSubtitle(uri, first, 10) },
                    { dao.appendExternalSubtitle(uri, second, 11) },
                    { dao.appendExternalSubtitle(uri, first, 12) },
                )
                coroutineScope {
                    writes.forEach { write ->
                        launch(Dispatchers.IO) {
                            start.await()
                            write()
                        }
                    }
                    start.complete(Unit)
                }
                val state = requireNotNull(dao.get(uri))
                assertEquals(12345L, state.playbackPosition)
                assertEquals(1.5f, state.playbackSpeed)
                assertEquals(2, state.audioTrackIndex)
                assertEquals(3, state.subtitleTrackIndex)
                assertEquals(1.25f, state.videoScale)
                assertEquals(250L, state.subtitleDelayMilliseconds)
                assertEquals(0.9f, state.subtitleSpeed)
                assertEquals(100L, state.introMs)
                assertEquals(200L, state.outroMs)
                val subtitles = UriListConverter.fromStringToList(state.externalSubs)
                assertEquals(2, subtitles.size)
                assertEquals(setOf(Uri.parse("content://test/字幕,a.srt"), Uri.parse("content://test/b.srt")), subtitles.toSet())
            }
        } finally {
            database.close()
        }
    }

    private fun client(bodies: MutableList<TrackedBody>, status: Int, content: String): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val body = TrackedBody(content)
            bodies += body
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").body(body).build()
        }.build()

    private class TrackedBody(content: String) : ResponseBody() {
        var closed = false
        private val bytes = content.toByteArray()
        private val source = object : ForwardingSource(Buffer().write(bytes)) {
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = bytes.size.toLong()
        override fun source(): BufferedSource = source
    }
}
