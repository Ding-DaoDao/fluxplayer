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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudiobookSessionTest {
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
            instrumentation.runOnMainSync {
                context.startActivity(
                    Intent(context, PlayerActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        data = Uri.fromFile(first)
                        putExtra("audio_only", true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    },
                )
            }
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
            val preferences = EntryPointAccessors.fromApplication(context.applicationContext, AudioThemePreferences::class.java).preferencesRepository()
            // 关闭播放页后，服务仍发布播放状态并持久化最近收听记录。
            instrumentation.runOnMainSync {
                activity?.finish()
                local.play()
            }
            awaitCondition { LocalAudiobookPlayback.state.value.playing }
            awaitCondition { preferences.applicationPreferences.value.audiobookLastPlayedAt.containsKey(folder.absolutePath) }
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
            instrumentation.runOnMainSync {
                context.startActivity(
                    Intent(context, PlayerActivity::class.java).apply {
                        data = Uri.fromFile(File(folder, "02.wav"))
                        putExtra("audio_only", true)
                        putExtra("reopen_audiobook", true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                )
            }
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 15_000) as? PlayerActivity
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { assertTrue(!local.playWhenReady) }
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
