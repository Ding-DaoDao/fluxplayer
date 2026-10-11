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
import com.fluxplayer.app.core.tingshu.ListeningDownloads
import com.fluxplayer.app.core.tingshu.ListeningProgress
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
    fun importedJarBrowsesPlaysWithHeadersSwitchesAndResumes() = checkPlayback(false)

    @Test
    fun firstOnlinePlayImmediatelyPersistsChapterAndDuration() = checkPlayback(true)

    @Test
    fun timerResumeAndAudioFocusInterruptionKeepSavedPositionAndPausedNotification() = checkPlayback(false, true)

    @Test
    fun episodeTimerIgnoresManualSwitchesAndStopsAfterCompletedChapter() = checkPlayback(false, episodeTimerOnly = true)

    @Test
    fun onlineNotificationUsesSystemStyleWithSingleChapterButtonPair() = checkPlayback(false, notificationOnly = true)

    private fun checkPlayback(firstPlayOnly: Boolean, interruptionsOnly: Boolean = false, episodeTimerOnly: Boolean = false, notificationOnly: Boolean = false) = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ListeningDownloads(context).clear()
        val repository = TingshuRepository.get(context)
        val fixture = File(context.cacheDir, "test_source.jar")
        instrumentation.context.assets.open("test_source.jar").use { input -> fixture.outputStream().use(input::copyTo) }
        val server = MockWebServer()
        val failNext = java.util.concurrent.atomic.AtomicBoolean(false)
        val rejected = java.util.concurrent.atomic.AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (failNext.compareAndSet(true, false)) {
                    rejected.incrementAndGet()
                    MockResponse().setResponseCode(403)
                } else if (request.getHeader("X-Flux-Listening-Test") == "fixture") {
                    MockResponse().setHeader("Content-Type", "audio/wav").setBody(Buffer().write(silentWav()))
                } else {
                    MockResponse().setResponseCode(403)
                }
        }
        server.start()
        var activity: TingshuPlayerActivity? = null
        val monitor = instrumentation.addMonitor(TingshuPlayerActivity::class.java.name, null, false)
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
            repository.saveProgress(book.key, ListeningProgress())
            // 由测试框架从前台启动页面，兼容厂商对后台打开窗口的限制。
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "am start -W -f 0x10008000 -n ${context.packageName}/${TingshuPlayerActivity::class.java.name} --es book ${book.key} --ei index 0",
                ),
            ).bufferedReader().use { it.readText() }
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 15_000) as? TingshuPlayerActivity
            assertTrue(activity != null)
            awaitState { it.playing && it.index == 0 && it.duration > 0 }
            // 首次开始播放即保存章节与时长，不依赖暂停或五秒后的周期保存。
            withTimeout(2_000) {
                while (repository.progress(book.key).episodeUrl != book.episodes[0].url) delay(20)
            }
            assertTrue(repository.progress(book.key).duration > 0)
            assertEquals("fixture", server.takeRequest(10, TimeUnit.SECONDS)!!.getHeader("X-Flux-Listening-Test"))
            if (firstPlayOnly) return@runBlocking
            lateinit var future: ListenableFuture<MediaController>
            instrumentation.runOnMainSync {
                future = MediaController.Builder(
                    context, SessionToken(context, ComponentName(context, TingshuPlaybackService::class.java)),
                ).buildAsync()
            }
            val connected = future.get(10, TimeUnit.SECONDS)
            controller = connected
            if (notificationOnly) {
                val manager = context.getSystemService(android.app.NotificationManager::class.java)
                withTimeout(10_000) {
                    while (manager.activeNotifications.isEmpty()) delay(50)
                }
                val notification = manager.activeNotifications.first().notification
                assertTrue(notification.bigContentView == null)
                assertEquals(3, notification.actions.size)
                assertEquals(1, notification.actions.count { it.title == "上一章" })
                assertEquals(1, notification.actions.count { it.title == "下一章" })
                assertTrue(notification.actions.none { it.title.toString().contains("15秒") })
                instrumentation.runOnMainSync {
                    assertTrue(!connected.isCommandAvailable(androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM))
                    assertTrue(!connected.isCommandAvailable(androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM))
                }
                notification.actions.first { it.title == "下一章" }.actionIntent.send()
                awaitState { it.playing && it.index == 1 }
                notification.actions.first { it.title == "上一章" }.actionIntent.send()
                awaitState { it.playing && it.index == 0 }
                return@runBlocking
            }
            if (episodeTimerOnly) {
                instrumentation.runOnMainSync {
                    connected.sendCustomCommand(SessionCommand(ListeningPlayback.SLEEP, Bundle.EMPTY), Bundle().apply { putInt("episodes", 2) })
                    connected.sendCustomCommand(SessionCommand(ListeningPlayback.NEXT, Bundle.EMPTY), Bundle.EMPTY)
                }
                awaitState { it.playing && it.index == 1 && it.sleepEpisodes == 2 }
                instrumentation.runOnMainSync { connected.sendCustomCommand(SessionCommand(ListeningPlayback.PREVIOUS, Bundle.EMPTY), Bundle.EMPTY) }
                awaitState { it.playing && it.index == 0 && it.sleepEpisodes == 2 }
                instrumentation.runOnMainSync {
                    connected.seekTo(28_000)
                    connected.setPlaybackSpeed(4f)
                }
                awaitState { it.playing && it.index == 1 && it.sleepEpisodes == 1 }
                instrumentation.runOnMainSync { connected.sendCustomCommand(SessionCommand(ListeningPlayback.PREVIOUS, Bundle.EMPTY), Bundle.EMPTY) }
                awaitState { it.playing && it.index == 0 && it.sleepEpisodes == 1 }
                instrumentation.runOnMainSync {
                    connected.sendCustomCommand(SessionCommand(ListeningPlayback.SKIP, Bundle.EMPTY), Bundle().apply { putInt("outro", 3) })
                    connected.seekTo(26_000)
                }
                awaitState { !it.playWhenReady && it.index == 0 && it.sleepEpisodes == 0 }
                assertTrue(repository.progress(book.key).position >= 27_000)
                instrumentation.runOnMainSync { connected.play() }
                awaitState { it.playing && it.index == 1 }
                // 全书最后一章结束后，也须清除尚未消耗完的定时状态。
                instrumentation.runOnMainSync {
                    connected.sendCustomCommand(SessionCommand(ListeningPlayback.SKIP, Bundle.EMPTY), Bundle.EMPTY)
                    connected.sendCustomCommand(SessionCommand(ListeningPlayback.SLEEP, Bundle.EMPTY), Bundle().apply { putInt("episodes", 2) })
                    connected.seekTo(28_000)
                }
                awaitState { !it.playWhenReady && it.index == 1 && it.sleepEpisodes == 0 }
                return@runBlocking
            }
            if (interruptionsOnly) {
                instrumentation.runOnMainSync {
                    connected.seekTo(7000)
                    connected.sendCustomCommand(SessionCommand(ListeningPlayback.SLEEP, Bundle.EMPTY), Bundle().apply { putInt("seconds", 2) })
                }
                awaitState { !it.playWhenReady && !it.playing && it.sleepSeconds == 0L && it.position >= 8000 }
                val stopped = ListeningPlayback.state.value.position
                val saved = repository.progress(book.key)
                assertEquals(book.episodes[0].url, saved.episodeUrl)
                assertTrue(kotlin.math.abs(saved.position - stopped) < 300)
                // 从持久化记录重新选书，验证续播不回到定时前的位置。
                instrumentation.runOnMainSync {
                    connected.sendCustomCommand(
                        SessionCommand(ListeningPlayback.SELECT, Bundle.EMPTY),
                        Bundle().apply {
                            putString("book", book.key)
                            putInt("index", 0)
                            putLong("position", saved.position)
                        },
                    )
                }
                awaitState { it.playing && it.position >= saved.position }
                simulateTransientFocusLoss(context, TingshuPlaybackService::class.java)
                awaitState { !it.playing && !it.playWhenReady }
                instrumentation.runOnMainSync {
                    val platform = android.media.session.MediaController(context, SessionToken::class.java.getDeclaredMethod("getPlatformToken").apply { isAccessible = true }.invoke(connected.connectedToken) as android.media.session.MediaSession.Token)
                    assertEquals(android.media.session.PlaybackState.STATE_PAUSED, platform.playbackState?.state)
                }
                assertTrue(repository.progress(book.key).position >= saved.position)
                delay(300)
                assertTrue(!ListeningPlayback.state.value.playWhenReady)
                return@runBlocking
            }
            instrumentation.runOnMainSync { connected.pause() }
            awaitState { !it.playing && !it.loading }
            instrumentation.runOnMainSync { connected.seekTo(1500) }
            awaitState { it.position >= 1400 }
            instrumentation.runOnMainSync { activity?.recreate() }
            val previousActivity = activity
            withTimeout(15_000) {
                do {
                    delay(100)
                    instrumentation.runOnMainSync {
                        activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                            .filterIsInstance<TingshuPlayerActivity>().firstOrNull()
                    }
                } while (activity == null || activity === previousActivity)
            }
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
            // 使用系统通知实际绑定的入口，播放中返回页面不得重新选择章节或清零进度。
            instrumentation.runOnMainSync { requireNotNull(connected.sessionActivity).send() }
            instrumentation.waitForIdleSync()
            delay(500)
            assertEquals(1, ListeningPlayback.state.value.index)
            assertTrue(ListeningPlayback.state.value.position >= 1700)
            assertTrue(ListeningPlayback.state.value.playing)
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

            // 按章定时结束后再次播放应进入下一章。
            instrumentation.runOnMainSync { connected.play() }
            awaitState { it.playing && it.index == 1 }
            instrumentation.runOnMainSync {
                connected.pause()
                connected.seekTo(1800)
            }
            awaitState { !it.playing && it.position >= 1700 }
            instrumentation.runOnMainSync { connected.sendCustomCommand(SessionCommand(ListeningPlayback.REFRESH, Bundle.EMPTY), Bundle.EMPTY) }
            awaitState { !it.refreshing && it.directoryMessage != null }
            assertTrue(ListeningPlayback.state.value.position >= 1700)
            assertTrue(!ListeningPlayback.state.value.playing)

            // 地址失效时自动重新解析，并保留用户指定的收听位置。
            failNext.set(true)
            instrumentation.runOnMainSync {
                connected.sendCustomCommand(
                    SessionCommand(ListeningPlayback.SELECT, Bundle.EMPTY),
                    Bundle().apply {
                        putString("book", book.key)
                        putInt("index", 0)
                        putLong("position", 4000)
                    },
                )
            }
            awaitState { it.playing && it.index == 0 && it.position >= 3900 && rejected.get() == 1 }

            // 在线备份恢复目录与章节进度，离线文件交给系统下载并在断网后播放。
            val backup = repository.exportLibrary()
            val savedProgress = repository.progress(book.key)
            repository.saveProgress(book.key, ListeningProgress(book.episodes[1].url, 100, 30000))
            repository.restoreLibrary(backup)
            assertEquals(savedProgress, repository.progress(book.key))
            val downloads = ListeningDownloads(context)
            downloads.enqueue(repository, book, 0, allowMetered = true)
            withTimeout(30_000) { while (downloads.offlineUri(book, 0) == null) delay(200) }
            server.shutdown()
            instrumentation.runOnMainSync {
                connected.sendCustomCommand(
                    SessionCommand(ListeningPlayback.SELECT, Bundle.EMPTY),
                    Bundle().apply {
                        putString("book", book.key)
                        putInt("index", 0)
                        putLong("position", 4000)
                    },
                )
            }
            awaitState { it.playing && it.index == 0 && it.position >= 3900 }
            downloads.remove(book, 0)

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
            ListeningDownloads(context).clear()
            instrumentation.runOnMainSync { controller?.release() }
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.removeMonitor(monitor)
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

/** 注入播放器收到的焦点等待指令，避开设备允许多应用同时播放的系统设置。 */
private fun simulateTransientFocusLoss(context: android.content.Context, serviceClass: Class<*>) {
    val ready = java.util.concurrent.CompletableFuture<androidx.media3.session.MediaSessionService>()
    val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName, binder: android.os.IBinder) {
            try {
                val reference = binder.javaClass.getDeclaredField("serviceReference").apply { isAccessible = true }.get(binder) as java.lang.ref.WeakReference<*>
                ready.complete(reference.get() as androidx.media3.session.MediaSessionService)
            } catch (error: Throwable) {
                ready.completeExceptionally(error)
            }
        }
        override fun onServiceDisconnected(name: android.content.ComponentName) = Unit
    }
    check(context.bindService(android.content.Intent(context, serviceClass).setAction(androidx.media3.session.MediaSessionService.SERVICE_INTERFACE), connection, android.content.Context.BIND_AUTO_CREATE))
    try {
        val service = ready.get(5, java.util.concurrent.TimeUnit.SECONDS)
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = service.sessions.single().player
            val internal = player.javaClass.getDeclaredField("internalPlayer").apply { isAccessible = true }.get(player)
            val wait = androidx.media3.common.audio.AudioFocusManager.PLAYER_COMMAND_WAIT_FOR_CALLBACK
            internal.javaClass.getDeclaredMethod("executePlayerCommand", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(internal, wait)
        }
    } finally {
        context.unbindService(connection)
    }
}
