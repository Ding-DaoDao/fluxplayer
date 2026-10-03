package com.fluxplayer.app.feature.tingshu

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxplayer.app.core.tingshu.TingshuRepository
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
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
class TingshuPlaybackTest {
    @Test
    fun importedJarBrowsesPlaysWithHeadersSwitchesAndResumes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = TingshuRepository.get(context)
        val fixture = File(context.cacheDir, "test_source.jar")
        instrumentation.context.assets.open("test_source.jar").use { input -> fixture.outputStream().use(input::copyTo) }
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader("X-Flux-Listening-Test") == "fixture") {
                    MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(silentWav()))
                } else {
                    MockResponse().setResponseCode(403)
                }
        }
        server.start()
        var scenario: ActivityScenario<TingshuPlayerActivity>? = null
        var controller: MediaController? = null
        try {
            repository.importJar(Uri.fromFile(fixture))
            val source = repository.sources.value.single()
            repository.saveConfig(source.id, mapOf("endpoint" to "http://127.0.0.1:${server.port}"))
            val menu = repository.menus(source.id).single()
            val books = repository.category(source.id, menu.tabs.single().url).list
            assertEquals(books.single().bookUrl, repository.search(source.id, "测试", 1).first.single().bookUrl)
            val book = repository.detail(source.id, books.single())
            assertEquals(2, book.episodes.size)
            assertEquals(book, repository.book(book.key))
            scenario = ActivityScenario.launch(
                Intent(context, TingshuPlayerActivity::class.java).putExtra("book", book.key).putExtra("index", 0),
            )
            awaitState { it.playing && it.index == 0 && it.duration > 0 }
            assertEquals("fixture", server.takeRequest(10, TimeUnit.SECONDS)!!.getHeader("X-Flux-Listening-Test"))
            lateinit var future: ListenableFuture<MediaController>
            instrumentation.runOnMainSync {
                future = MediaController.Builder(
                    context, SessionToken(context, ComponentName(context, TingshuPlaybackService::class.java)),
                ).buildAsync()
            }
            val connected = future.get(10, TimeUnit.SECONDS)
            controller = connected
            instrumentation.runOnMainSync { connected.pause() }
            awaitState { !it.playing && !it.loading }
            instrumentation.runOnMainSync { connected.seekTo(1500) }
            awaitState { it.position >= 1400 }
            scenario.recreate()
            awaitState { !it.playing && it.position >= 1400 }
            instrumentation.runOnMainSync {
                connected.sendCustomCommand(SessionCommand(ListeningPlayback.NEXT, Bundle.EMPTY), Bundle.EMPTY)
            }
            awaitState { it.playing && it.index == 1 }
            instrumentation.runOnMainSync { connected.pause() }
            awaitState { !it.playing }
            assertEquals(book.episodes[1].url, repository.progress(book.key).episodeUrl)
            instrumentation.runOnMainSync { connected.seekTo(1800) }
            awaitState { it.position >= 1700 }
            withTimeout(10_000) {
                while (repository.progress(book.key).position < 1700) delay(100)
            }
            val savedPosition = repository.progress(book.key).position
            instrumentation.runOnMainSync {
                connected.sendCustomCommand(
                    SessionCommand(ListeningPlayback.SELECT, Bundle.EMPTY),
                    Bundle().apply {
                        putString("book", book.key)
                        putInt("index", 1)
                        putLong("position", savedPosition)
                    },
                )
            }
            awaitState { it.playing && it.index == 1 && it.position >= 1700 }
            assertTrue(repository.recentBooks.value.any { it.key == book.key })
            assertEquals(2, repository.chapterProgress(book).size)
            instrumentation.runOnMainSync {
                connected.setPlaybackSpeed(1.25f)
                connected.sendCustomCommand(SessionCommand(ListeningPlayback.SLEEP, Bundle.EMPTY), Bundle().apply { putInt("seconds", 2) })
            }
            awaitState { !it.playing && it.sleepSeconds == 0L && it.speed == 1.25f }
            instrumentation.runOnMainSync {
                connected.sendCustomCommand(
                    SessionCommand(ListeningPlayback.SKIP, Bundle.EMPTY),
                    Bundle().apply {
                        putInt("intro", 2)
                        putInt("outro", 1)
                    },
                )
            }
            awaitState { it.introSkipSeconds == 2 && it.outroSkipSeconds == 1 }
            instrumentation.runOnMainSync {
                connected.sendCustomCommand(
                    SessionCommand(ListeningPlayback.SELECT, Bundle.EMPTY),
                    Bundle().apply {
                        putString("book", book.key)
                        putInt("index", 0)
                    },
                )
            }
            awaitState { it.playing && it.index == 0 && it.position >= 1900 }
            instrumentation.runOnMainSync {
                connected.sendCustomCommand(SessionCommand(ListeningPlayback.SLEEP, Bundle.EMPTY), Bundle().apply { putInt("episodes", 1) })
                connected.seekTo(28_500)
            }
            awaitState { !it.playing && it.sleepEpisodes == 0 && it.index == 0 }

            instrumentation.runOnMainSync {
                connected.sendCustomCommand(
                    SessionCommand(ListeningPlayback.SELECT, Bundle.EMPTY),
                    Bundle().apply {
                        putString("book", book.key)
                        putInt("index", 100)
                    },
                )
            }
            awaitState { it.error != null && !it.loading }
        } finally {
            instrumentation.runOnMainSync { controller?.release() }
            scenario?.close()
            context.stopService(Intent(context, TingshuPlaybackService::class.java))
            repository.remove("test_source")
            fixture.delete()
            context.getSharedPreferences("tingshu_playback", android.content.Context.MODE_PRIVATE).edit().clear().commit()
            server.shutdown()
        }
    }

    private var awaitStep = 0

    private suspend fun awaitState(predicate: (ListeningPlaybackState) -> Boolean) {
        val step = ++awaitStep
        try {
            withTimeout(20_000) {
                while (!predicate(ListeningPlayback.state.value)) delay(100)
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("等待播放状态第 $step 步超时：${ListeningPlayback.state.value}", error)
        }
    }

    private fun silentWav(): ByteArray {
        val size = 8_000 * 2 * 30
        return ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
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
    }
}
