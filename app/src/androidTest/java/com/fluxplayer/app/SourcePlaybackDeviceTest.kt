package com.fluxplayer.app

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxplayer.app.core.tingshu.ListeningErrors
import com.fluxplayer.app.core.tingshu.TingshuRepository
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 仅显式指定 liveSources 时使用设备现有书源，静音验证解析和音频准备。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class SourcePlaybackDeviceTest {
    @Test
    fun pan123ExistingBookCanPrepareAudio(): Unit = runBlocking {
        verify("jdr:pan123", useSavedBook = true)
    }

    @Test
    fun quarkBookCanLoadChaptersAndPrepareAudio(): Unit = runBlocking {
        verify("jdr:quark", useSavedBook = false)
    }

    private suspend fun verify(id: String, useSavedBook: Boolean) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveSources") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = TingshuRepository.get(context)
        withTimeout(15_000) {
            while (repository.sources.value.none { it.id == id }) delay(100)
        }
        try {
            val book = if (useSavedBook) {
                val file = File(context.filesDir, "tingshu/books").listFiles().orEmpty().firstOrNull {
                    it.extension == "json" && JSONObject(it.readText()).optString("sourceId") == id
                }
                requireNotNull(file) { "请先在设备上打开一本 123 云盘书籍" }
                repository.book(file.nameWithoutExtension)
            } else {
                val entry = repository.browseJdr(id, null).books.firstOrNull()
                requireNotNull(entry) { "当前目录没有书籍" }
                repository.detail(id, entry)
            }
            assertTrue("没有可播放章节", book.episodes.isNotEmpty())
            val resource = repository.resolve(book, 0)
            var player: ExoPlayer? = null
            var failure: PlaybackException? = null
            var ready = false
            val complete = CountDownLatch(1)
            try {
                instrumentation.runOnMainSync {
                    val dataSource = DefaultHttpDataSource.Factory().setDefaultRequestProperties(resource.headers).setAllowCrossProtocolRedirects(true)
                    player = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(dataSource)).build().apply {
                        volume = 0f
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_READY) {
                                    ready = true
                                    complete.countDown()
                                }
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                failure = error
                                complete.countDown()
                            }
                        })
                        setMediaItem(MediaItem.fromUri(resource.url))
                        prepare()
                    }
                }
                assertTrue("音频准备超时", complete.await(40, TimeUnit.SECONDS))
                failure?.let { throw it }
                assertTrue("音频未就绪", ready)
            } finally {
                instrumentation.runOnMainSync { player?.release() }
            }
        } catch (error: Exception) {
            throw AssertionError(ListeningErrors.describe(error, "$id 设备回归失败"))
        }
    }
}
