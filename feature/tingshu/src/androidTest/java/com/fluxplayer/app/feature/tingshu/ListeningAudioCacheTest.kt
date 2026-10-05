package com.fluxplayer.app.feature.tingshu

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxplayer.app.core.tingshu.TingshuRepository
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class ListeningAudioCacheTest {
    @Test
    fun downloadsProgressiveAndHlsWithHeadersAndReadsOffline(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = TingshuRepository.get(context)
        val cache = ListeningAudioCache.get(context)
        val server = MockWebServer()
        val bytes = ByteArray(32_000) { (it % 127).toByte() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("X-Cache-Test") != "fixture") return MockResponse().setResponseCode(403)
                return if (request.path == "/playlist.m3u8") {
                    MockResponse().setHeader("Content-Type", "application/vnd.apple.mpegurl").setBody("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2,\nsegment.ts\n#EXT-X-ENDLIST\n")
                } else {
                    MockResponse().setBody(Buffer().write(bytes))
                }
            }
        }
        server.start()
        val file = File(context.cacheDir, "cache-test.jdr")
        val entry = "jdr:flux-cache-test"
        val manifest = """{"id":"flux-cache-test","name":"Cache test","version":"1","sources":[{"id":"cachetest","name":"Cache","script":"source.js"}]}"""
        val script = """
            registerSource({id:'cachetest',
              async search(){return [{id:'book',bookTitle:'缓存测试'}];},
              async chapters(){return [{chapter_id:'direct',title:'直链'},{chapter_id:'hls',title:'HLS'}];},
              async audio(p){return {url:'${server.url("/")}'+(p.chapterId==='direct'?'one.wav':'playlist.m3u8'),headers:{'X-Cache-Test':'fixture'}};}
            });
        """.trimIndent()
        ZipOutputStream(file.outputStream()).use { out ->
            mapOf("manifest.json" to manifest, "source.js" to script).forEach { (name, data) ->
                out.putNextEntry(ZipEntry(name))
                out.write(data.toByteArray())
                out.closeEntry()
            }
        }
        var stopped = false
        try {
            cache.clear()
            repository.importSource(Uri.fromFile(file))
            val book = repository.detail("jdr:cachetest", repository.search("jdr:cachetest", "缓存", 1).first.single())
            cache.download(book, 0, wholeBook = true)
            withTimeout(30_000) { while (cache.state.value.download != null) delay(100) }
            assertEquals("整本书已缓存", cache.state.value.message)
            assertNotNull(cache.offlineResource(book, 0))
            assertNotNull(cache.offlineResource(book, 1))
            server.shutdown()
            stopped = true
            repository.setEnabled(entry, false)
            val offline = cache.offlineResource(book, 0)!!
            val dataSource = cache.dataSource(book, 0, offline, true).createDataSource()
            val out = java.io.ByteArrayOutputStream()
            try {
                dataSource.open(DataSpec(Uri.parse(offline.url)))
                val buffer = ByteArray(8192)
                while (true) {
                    val n = dataSource.read(buffer, 0, buffer.size)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            } finally {
                dataSource.close()
            }
            assertTrue(bytes.contentEquals(out.toByteArray()))
            val hls = cache.offlineResource(book, 1)!!
            val manifestSource = cache.dataSource(book, 1, hls, true).createDataSource()
            try {
                assertTrue(manifestSource.open(DataSpec(Uri.parse(hls.url))) > 0)
            } finally {
                manifestSource.close()
            }
            cache.clear()
            assertNull(cache.offlineResource(book, 0))
            assertNull(cache.offlineResource(book, 1))
        } finally {
            cache.clear()
            repository.remove(entry)
            file.delete()
            if (!stopped) server.shutdown()
        }
    }
}
