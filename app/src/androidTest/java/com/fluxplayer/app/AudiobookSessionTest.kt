package com.fluxplayer.app

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxplayer.app.feature.player.AudioThemePreferences
import com.fluxplayer.app.feature.player.LocalAudiobookPlayback
import com.fluxplayer.app.feature.player.PlayerActivity
import com.fluxplayer.app.feature.player.service.PlayerService
import com.fluxplayer.app.feature.tingshu.TingshuPlaybackService
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudiobookSessionTest {
    @Test
    fun listeningNotificationUsesSystemStyleWithoutDuplicateChapterButtons() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "listening-notification-test").apply { mkdirs() }
        val file = File(folder, "01.wav").apply { writeBytes(silentWav()) }
        lateinit var future: ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlayerService::class.java))).buildAsync()
        }
        val controller = future.get(15, TimeUnit.SECONDS)
        try {
            instrumentation.runOnMainSync {
                controller.sendCustomCommand(
                    com.fluxplayer.app.feature.player.service.CustomCommands.START_AUDIOBOOK.sessionCommand,
                    android.os.Bundle().apply { putString("chapter_uri", Uri.fromFile(file).toString()) },
                )
            }
            awaitCondition { LocalAudiobookPlayback.state.value.playing }
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            awaitCondition { manager.activeNotifications.isNotEmpty() }
            val notification = manager.activeNotifications.first().notification
            assertTrue(notification.bigContentView == null)
            assertTrue(notification.actions.size in 1..3)
            assertTrue(notification.actions.none { it.title.toString().contains("15秒") })
            instrumentation.runOnMainSync { assertTrue(controller.mediaButtonPreferences.isEmpty()) }
        } finally {
            instrumentation.runOnMainSync {
                controller.stop()
                controller.clearMediaItems()
                controller.release()
            }
            context.stopService(Intent(context, PlayerService::class.java))
            file.delete()
            folder.delete()
        }
    }

    @Test
    fun episodeTimerCountsNaturalCompletionsAndPreservesManualChapterSelection() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "listening-episode-timer").apply { mkdirs() }
        val files = List(3) { File(folder, "0${it + 1}.wav").apply { writeBytes(silentWav()) } }
        lateinit var future: ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlayerService::class.java))).buildAsync()
        }
        val controller = future.get(15, TimeUnit.SECONDS)
        try {
            instrumentation.runOnMainSync {
                controller.sendCustomCommand(
                    com.fluxplayer.app.feature.player.service.CustomCommands.START_AUDIOBOOK.sessionCommand,
                    android.os.Bundle().apply {
                        putString("chapter_uri", Uri.fromFile(files[0]).toString())
                    },
                )
            }
            awaitCondition { LocalAudiobookPlayback.state.value.playing && LocalAudiobookPlayback.state.value.index == 0 }
            instrumentation.runOnMainSync {
                com.fluxplayer.app.feature.player.service.AudioSleepTimer.startEpisodes(2, 0)
                controller.setPlaybackSpeed(4f)
                controller.seekTo(28_000)
            }
            awaitCondition { LocalAudiobookPlayback.state.value.index == 1 && com.fluxplayer.app.feature.player.service.AudioSleepTimer.state.value.remainingEpisodes == 1 }
            instrumentation.runOnMainSync {
                controller.pause()
                controller.seekToDefaultPosition(2)
            }
            awaitCondition { LocalAudiobookPlayback.state.value.index == 2 }
            assertEquals(1, com.fluxplayer.app.feature.player.service.AudioSleepTimer.state.value.remainingEpisodes)
            instrumentation.runOnMainSync {
                controller.seekTo(1, 28_000)
                controller.play()
            }
            awaitCondition { !LocalAudiobookPlayback.state.value.playWhenReady && com.fluxplayer.app.feature.player.service.AudioSleepTimer.state.value.remainingEpisodes == 0 }
            assertEquals(1, LocalAudiobookPlayback.state.value.index)
            assertTrue(LocalAudiobookPlayback.state.value.position >= 29_000)
            instrumentation.runOnMainSync { controller.play() }
            awaitCondition { LocalAudiobookPlayback.state.value.playing && LocalAudiobookPlayback.state.value.index == 2 }
            // 跳过片尾和按集数定时同时开启时，在当前章暂停并保留续播位置。
            val preferences = EntryPointAccessors.fromApplication(context, AudioThemePreferences::class.java).preferencesRepository()
            preferences.updateApplicationPreferences { it.copy(audiobookSkipSettings = it.audiobookSkipSettings + (folder.absolutePath to "0,3")) }
            instrumentation.runOnMainSync {
                controller.pause()
                controller.seekTo(0, 26_000)
                com.fluxplayer.app.feature.player.service.AudioSleepTimer.startEpisodes(1, 0)
                controller.play()
            }
            awaitCondition { !LocalAudiobookPlayback.state.value.playWhenReady && com.fluxplayer.app.feature.player.service.AudioSleepTimer.state.value.remainingEpisodes == 0 }
            assertEquals(0, LocalAudiobookPlayback.state.value.index)
            instrumentation.runOnMainSync { controller.play() }
            awaitCondition { LocalAudiobookPlayback.state.value.playing && LocalAudiobookPlayback.state.value.index == 1 }
        } finally {
            instrumentation.runOnMainSync {
                controller.stop()
                controller.clearMediaItems()
                controller.release()
            }
            context.stopService(Intent(context, PlayerService::class.java))
            files.forEach { it.delete() }
            folder.delete()
        }
    }

    @Test
    fun timerAndAudioFocusSaveCurrentChapterWithoutPreviousChapterOverwrite() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "listening-interruption-test").apply { mkdirs() }
        val chapters = List(2) { File(folder, "0${it + 1}.wav").apply { writeBytes(silentWav()) } }
        val progress = EntryPointAccessors.fromApplication(context, AudioThemePreferences::class.java).audiobookProgressRepository()
        lateinit var future: ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlayerService::class.java))).buildAsync()
        }
        val controller = future.get(15, TimeUnit.SECONDS)
        try {
            instrumentation.runOnMainSync {
                controller.sendCustomCommand(
                    com.fluxplayer.app.feature.player.service.CustomCommands.START_AUDIOBOOK.sessionCommand,
                    android.os.Bundle().apply {
                        putString("chapter_uri", Uri.fromFile(chapters[1]).toString())
                        putLong("position", 7000)
                    },
                )
            }
            awaitCondition { LocalAudiobookPlayback.state.value.playing && LocalAudiobookPlayback.state.value.index == 1 }
            instrumentation.runOnMainSync { com.fluxplayer.app.feature.player.service.AudioSleepTimer.startMinutes(2) }
            awaitCondition { !LocalAudiobookPlayback.state.value.playWhenReady && com.fluxplayer.app.feature.player.service.AudioSleepTimer.state.value.remainingSeconds == 0 }
            val stopped = LocalAudiobookPlayback.state.value.position
            val saved = kotlinx.coroutines.withTimeout(3000) {
                progress.progress.first { state -> state.resumeStates[folder.absolutePath]?.substringAfter('|')?.toLongOrNull()?.let { kotlin.math.abs(it - stopped) < 300 } == true }
            }
            assertTrue(stopped >= 8000)
            // 模拟页面迟到的上一章保存，不得改写第二章的续播记录。
            progress.save(folder.absolutePath, 0, 30_000, 30_000, chapterUri = Uri.fromFile(chapters[0]).toString(), updateResume = false)
            assertEquals(saved.resumeStates[folder.absolutePath], progress.progress.first().resumeStates[folder.absolutePath])
            instrumentation.runOnMainSync {
                controller.sendCustomCommand(
                    com.fluxplayer.app.feature.player.service.CustomCommands.START_AUDIOBOOK.sessionCommand,
                    android.os.Bundle().apply {
                        putString("chapter_uri", Uri.fromFile(chapters[1]).toString())
                        putLong("position", stopped)
                    },
                )
            }
            awaitCondition { LocalAudiobookPlayback.state.value.playing && LocalAudiobookPlayback.state.value.position >= stopped }
            simulateTransientFocusLoss(context, PlayerService::class.java)
            awaitCondition { !LocalAudiobookPlayback.state.value.playing && !LocalAudiobookPlayback.state.value.playWhenReady }
            instrumentation.runOnMainSync {
                val platform = android.media.session.MediaController(context, SessionToken::class.java.getDeclaredMethod("getPlatformToken").apply { isAccessible = true }.invoke(controller.connectedToken) as android.media.session.MediaSession.Token)
                assertEquals(android.media.session.PlaybackState.STATE_PAUSED, platform.playbackState?.state)
            }
            assertTrue(progress.progress.first().resumeStates[folder.absolutePath]!!.substringAfter('|').toLong() >= stopped)
        } finally {
            instrumentation.runOnMainSync {
                controller.stop()
                controller.clearMediaItems()
                controller.release()
            }
            context.stopService(Intent(context, PlayerService::class.java))
            chapters.forEach { it.delete() }
            folder.delete()
        }
    }

    @Test
    fun sourceServiceAndLocalAudiobookCanConnectTogether() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val folder = File(context.cacheDir, "listening-session-test").apply { mkdirs() }
        val first = File(folder, "01.wav").apply { writeBytes(silentWav()) }
        File(folder, "02.wav").writeBytes(silentWav())
        var sourceFuture: ListenableFuture<MediaController>? = null
        var localFuture: ListenableFuture<MediaController>? = null
        var activity: PlayerActivity? = null
        val monitor = instrumentation.addMonitor(PlayerActivity::class.java.name, null, false)
        try {
            instrumentation.runOnMainSync {
                sourceFuture = MediaController.Builder(context, SessionToken(context, ComponentName(context, TingshuPlaybackService::class.java))).buildAsync()
            }
            val source = sourceFuture!!.get(15, TimeUnit.SECONDS)
            assertTrue(source.isConnected)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "am start -W -n ${context.packageName}/${PlayerActivity::class.java.name} -a android.intent.action.VIEW -d ${Uri.fromFile(first)} --ez audio_only true",
                ),
            ).bufferedReader().use { it.readText() }
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 15_000) as? PlayerActivity
            assertTrue(activity != null)
            instrumentation.runOnMainSync {
                localFuture = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlayerService::class.java))).buildAsync()
            }
            val local = localFuture!!.get(15, TimeUnit.SECONDS)
            awaitCondition {
                var ready = false
                instrumentation.runOnMainSync { ready = local.playbackState == Player.STATE_READY && local.mediaItemCount == 2 }
                ready
            }
            awaitCondition { LocalAudiobookPlayback.state.value.bookPath == folder.absolutePath }
            val progress = EntryPointAccessors.fromApplication(context.applicationContext, AudioThemePreferences::class.java).audiobookProgressRepository()
            // 关闭播放页后，服务仍发布播放状态并持久化最近收听记录。
            instrumentation.runOnMainSync {
                activity?.finish()
                local.play()
            }
            awaitCondition { LocalAudiobookPlayback.state.value.playing }
            awaitCondition { runBlocking { progress.progress.first().lastPlayedAt.containsKey(folder.absolutePath) } }
            instrumentation.runOnMainSync {
                assertTrue(source.isConnected)
                assertTrue(local.isConnected)
                local.pause()
                local.seekToDefaultPosition(1)
            }
            awaitCondition {
                var second = false
                instrumentation.runOnMainSync { second = local.currentMediaItemIndex == 1 && local.playbackState == Player.STATE_READY }
                second
            }
            awaitCondition { !LocalAudiobookPlayback.state.value.playWhenReady && LocalAudiobookPlayback.state.value.index == 1 }
            instrumentation.runOnMainSync { local.seekTo(1500) }
            awaitCondition {
                var resumed = false
                instrumentation.runOnMainSync { resumed = local.currentPosition >= 1400 }
                resumed
            }
            awaitCondition { LocalAudiobookPlayback.state.value.position >= 1400 }
            // 模拟通知里的旧章节地址，后台已切到第二章时也不得重新加载第一章。
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "am start -W -n ${context.packageName}/${PlayerActivity::class.java.name} -d ${Uri.fromFile(first)} --ez audio_only true --ez reopen_audiobook true",
                ),
            ).bufferedReader().use { it.readText() }
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 15_000) as? PlayerActivity
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertTrue(!local.playWhenReady) }
            // 发送系统通知实际使用的入口，验证播放中重复返回不会跳章、重置或暂停。
            instrumentation.runOnMainSync {
                local.seekTo(5000)
                local.play()
            }
            awaitCondition { LocalAudiobookPlayback.state.value.playing && LocalAudiobookPlayback.state.value.position >= 4900 }
            instrumentation.runOnMainSync { requireNotNull(local.sessionActivity).send() }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals(1, local.currentMediaItemIndex)
                assertTrue(local.currentPosition >= 4900)
                assertTrue(local.playWhenReady)
            }
            instrumentation.runOnMainSync { MediaController.releaseFuture(localFuture!!) }
            instrumentation.runOnMainSync {
                localFuture = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlayerService::class.java))).buildAsync()
            }
            val reconnected = localFuture!!.get(15, TimeUnit.SECONDS)
            instrumentation.runOnMainSync {
                assertEquals(1, reconnected.currentMediaItemIndex)
                assertTrue(reconnected.currentPosition >= 1400)
                assertTrue(source.isConnected)
                reconnected.stop()
                reconnected.clearMediaItems()
            }
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync {
                activity?.finish()
                localFuture?.let(MediaController::releaseFuture)
                sourceFuture?.let(MediaController::releaseFuture)
            }
            context.stopService(Intent(context, PlayerService::class.java))
            context.stopService(Intent(context, TingshuPlaybackService::class.java))
            folder.listFiles()?.forEach { it.delete() }
            folder.delete()
        }
    }

    private fun awaitCondition(predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (!predicate()) {
            check(System.currentTimeMillis() < deadline) { "本地听书播放状态未就绪" }
            Thread.sleep(100)
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
