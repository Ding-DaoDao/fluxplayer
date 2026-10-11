package com.fluxplayer.app.feature.tingshu

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxplayer.app.core.tingshu.TingshuRepository
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookInformationRefreshTest {
    @Test
    fun refreshedMetadataAndDirectoryKeepPlayingChapterPositionAndTimer(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = TingshuRepository.get(context)
        val fixture = File(context.cacheDir, "refresh-fixture.jdr")
        val server = MockWebServer()
        val size = 8_000 * 2 * 30
        val wav = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + size)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(8_000)
            putInt(16_000)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(size)
        }.array()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(wav))
        }
        server.start()
        var controller: MediaController? = null
        try {
            writeSource(fixture, server.url("/audio.wav").toString(), false)
            repository.importSource(Uri.fromFile(fixture))
            val source = repository.sources.value.single { it.id == "jdr:refresh-test" }
            val listed = repository.browseJdr(source.id, "shelf").books.single()
            val book = repository.detail(source.id, listed)
            assertEquals("旧书名", repository.cachedBook(source.id, book.url)?.title)
            assertTrue(book.jdrBookExtras.contains("__fluxParentDirectory"))
            lateinit var future: ListenableFuture<MediaController>
            instrumentation.runOnMainSync {
                future = MediaController.Builder(context, SessionToken(context, ComponentName(context, TingshuPlaybackService::class.java))).buildAsync()
            }
            val connected = future.get(10, TimeUnit.SECONDS)
            controller = connected
            instrumentation.runOnMainSync {
                connected.sendCustomCommand(
                    SessionCommand(ListeningPlayback.SELECT, Bundle.EMPTY),
                    Bundle().apply {
                        putString("book", book.key)
                        putInt("index", 0)
                        putLong("position", 7_000)
                    },
                )
            }
            awaitState { it.playing && it.position >= 7_000 }
            instrumentation.runOnMainSync {
                connected.sendCustomCommand(SessionCommand(ListeningPlayback.SLEEP, Bundle.EMPTY), Bundle().apply { putInt("episodes", 2) })
            }
            writeSource(fixture, server.url("/audio.wav").toString(), true)
            repository.importSource(Uri.fromFile(fixture))
            val before = ListeningPlayback.state.value.position
            instrumentation.runOnMainSync { connected.sendCustomCommand(SessionCommand(ListeningPlayback.REFRESH, Bundle.EMPTY), Bundle.EMPTY) }
            awaitState { !it.refreshing && it.book?.title == "新书名" }
            val state = ListeningPlayback.state.value
            assertTrue(state.playing)
            assertEquals(1, state.index)
            assertEquals(2, state.sleepEpisodes)
            assertTrue(state.position >= before - 200)
            assertTrue(state.position < before + 3_000)
            assertEquals("改名后的章节", state.book!!.episodes[state.index].title)
            assertEquals("https://example.com/new.png", state.book!!.coverUrl)
            assertEquals("新书名", repository.cachedBook(source.id, book.url)?.title)
            instrumentation.runOnMainSync {
                assertEquals("改名后的章节", connected.mediaMetadata.title.toString())
                assertEquals("新书名", connected.mediaMetadata.artist.toString())
            }
            assertTrue(state.directoryMessage!!.contains("名称更新 1 集"))
        } finally {
            instrumentation.runOnMainSync { controller?.release() }
            context.stopService(Intent(context, TingshuPlaybackService::class.java))
            repository.remove("jdr:refresh-fixture")
            fixture.delete()
            server.shutdown()
        }
    }

    private suspend fun awaitState(predicate: (ListeningPlaybackState) -> Boolean) {
        try {
            withTimeout(20_000) { while (!predicate(ListeningPlayback.state.value)) delay(50) }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("播放状态等待超时：${ListeningPlayback.state.value}", error)
        }
    }

    private fun writeSource(file: File, audio: String, updated: Boolean) {
        val title = if (updated) "新书名" else "旧书名"
        val chapter = if (updated) "改名后的章节" else "原章节"
        val cover = if (updated) "new" else "old"
        val extra = if (updated) "{chapter_id:'new',title:'新增章节'}," else ""
        val manifest = """{"id":"refresh-fixture","name":"刷新测试","version":"1","sources":[{"id":"refresh-test","name":"刷新测试","script":"source.js"}]}"""
        val script = """
            registerSource({id:'refresh-test',
              async browse(p) { return {items:[{id:'book',name:'$title',type:'book',cover:'https://example.com/$cover.png',token:'book-token'}]}; },
              async chapters(p) {
                if(p.token !== 'book-token') throw Error('书籍附加信息丢失');
                return [$extra{chapter_id:'current',title:'$chapter',token:'chapter-token'}];
              },
              async audio(p) {
                if(p.token !== 'chapter-token') throw Error('章节附加信息丢失');
                return {url:'$audio'};
              }
            });
        """.trimIndent()
        ZipOutputStream(file.outputStream()).use { out ->
            mapOf("manifest.json" to manifest, "source.js" to script).forEach { (path, content) ->
                out.putNextEntry(ZipEntry(path))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
    }
}
