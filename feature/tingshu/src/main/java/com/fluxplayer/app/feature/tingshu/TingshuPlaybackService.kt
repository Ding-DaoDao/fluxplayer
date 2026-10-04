package com.fluxplayer.app.feature.tingshu

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.fluxplayer.app.core.tingshu.ListeningBook
import com.fluxplayer.app.core.tingshu.ListeningProgress
import com.fluxplayer.app.core.tingshu.TingshuRepository
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

data class ListeningPlaybackState(
    val book: ListeningBook? = null,
    val index: Int = 0,
    val position: Long = 0,
    val duration: Long = 0,
    val playing: Boolean = false,
    val playWhenReady: Boolean = false,
    val lastPlayedAt: Long = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val speed: Float = 1f,
    val sleepSeconds: Long = 0,
    val sleepEpisodes: Int = 0,
    val introSkipSeconds: Int = 0,
    val outroSkipSeconds: Int = 0,
)

/** 仅供 JAR 听书使用的播放状态，不修改视频播放器的会话。 */
object ListeningPlayback {
    internal val mutableState = MutableStateFlow(ListeningPlaybackState())
    val state = mutableState.asStateFlow()
    const val SKIP = "tingshu.skip"
    const val SLEEP = "tingshu.sleep"
    const val SELECT = "tingshu.select"
    const val NEXT = "tingshu.next"
    const val PREVIOUS = "tingshu.previous"
}

@androidx.annotation.OptIn(UnstableApi::class)
class TingshuPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var repository: TingshuRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var resolveJob: Job? = null
    private var activeBook: ListeningBook? = null
    private var activeIndex = 0
    private var sleepSeconds = 0L
    private val preferences by lazy { getSharedPreferences("tingshu_playback", MODE_PRIVATE) }
    private var introSkipSeconds = 0
    private var outroSkipSeconds = 0
    private var sleepEpisodes = 0

    override fun onCreate() {
        super.onCreate()
        repository = TingshuRepository.get(this)
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateState()
                    if (playbackState == Player.STATE_ENDED) {
                        saveProgress()
                        val book = activeBook ?: return
                        if (sleepEpisodes > 0 && --sleepEpisodes == 0) {
                            player.pause()
                            updateState()
                            return
                        }
                        if (activeIndex + 1 < book.episodes.size) select(book.key, activeIndex + 1, 0)
                    }
                }

                override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                    preferences.edit().putFloat("speed", playbackParameters.speed).apply()
                    updateState()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        ListeningPlayback.mutableState.value = ListeningPlayback.state.value.copy(lastPlayedAt = System.currentTimeMillis())
                        activeBook?.let { book -> scope.launch { repository.markPlayed(book) } }
                    }
                    updateState()
                    if (!isPlaying) saveProgress()
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    updateState()
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    ListeningPlayback.mutableState.value = ListeningPlayback.state.value.copy(
                        loading = false,
                        error = "音频播放失败，可以重新选择本章重试",
                    )
                }
            })
        }
        player.setPlaybackSpeed(preferences.getFloat("speed", 1f))
        session = MediaSession.Builder(this, player)
            .setId("fluxplayer.tingshu")
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    if (controller.packageName != packageName && !controller.isTrusted) {
                        return MediaSession.ConnectionResult.reject()
                    }
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(ListeningPlayback.SLEEP, Bundle.EMPTY))
                        .add(SessionCommand(ListeningPlayback.SKIP, Bundle.EMPTY))
                        .add(SessionCommand(ListeningPlayback.SELECT, Bundle.EMPTY))
                        .add(SessionCommand(ListeningPlayback.NEXT, Bundle.EMPTY))
                        .add(SessionCommand(ListeningPlayback.PREVIOUS, Bundle.EMPTY)).build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands).build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        ListeningPlayback.SLEEP -> {
                            val seconds = args.getInt("seconds", args.getInt("minutes") * 60).coerceIn(0, 24 * 3600)
                            sleepEpisodes = args.getInt("episodes").coerceIn(0, 10)
                            sleepSeconds = if (sleepEpisodes > 0) 0 else seconds.toLong()
                            updateState()
                        }
                        ListeningPlayback.SKIP -> {
                            introSkipSeconds = args.getInt("intro").coerceIn(0, 300)
                            outroSkipSeconds = args.getInt("outro").coerceIn(0, 300)
                            activeBook?.let { preferences.edit().putInt("${it.key}.intro", introSkipSeconds).putInt("${it.key}.outro", outroSkipSeconds).apply() }
                            if (player.currentPosition < introSkipSeconds * 1000L) player.seekTo(introSkipSeconds * 1000L)
                            updateState()
                        }
                        ListeningPlayback.SELECT -> {
                            if (controller.packageName != packageName) {
                                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
                            }
                            val key = args.getString("book")
                                ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
                            select(key, args.getInt("index"), args.getLong("position"))
                        }
                        ListeningPlayback.NEXT -> activeBook?.let {
                            if (activeIndex + 1 < it.episodes.size) select(it.key, activeIndex + 1, 0)
                        }
                        ListeningPlayback.PREVIOUS -> activeBook?.let {
                            if (activeIndex > 0) select(it.key, activeIndex - 1, 0)
                        }
                        else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .setCustomLayout(
                listOf(
                    CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                        .setDisplayName("上一章")
                        .setSessionCommand(SessionCommand(ListeningPlayback.PREVIOUS, Bundle.EMPTY)).build(),
                    CommandButton.Builder(CommandButton.ICON_NEXT)
                        .setDisplayName("下一章")
                        .setSessionCommand(SessionCommand(ListeningPlayback.NEXT, Bundle.EMPTY)).build(),
                ),
            )
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0, Intent(this, TingshuPlayerActivity::class.java).putExtra("reopen", true),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build()
        scope.launch {
            var seconds = 0
            while (true) {
                delay(1000)
                if (player.isPlaying && sleepSeconds > 0 && --sleepSeconds == 0L) {
                    player.pause()
                }
                updateState()
                if (++seconds % 5 == 0) saveProgress()
                if (player.isPlaying && outroSkipSeconds > 0 && player.duration > 0 && player.currentPosition >= (player.duration - outroSkipSeconds * 1000L).coerceAtLeast(0)) {
                    if (sleepEpisodes > 0 && --sleepEpisodes == 0) {
                        player.pause()
                    } else {
                        activeBook?.let { if (activeIndex + 1 < it.episodes.size) select(it.key, activeIndex + 1, 0) else player.pause() }
                    }
                }
            }
        }
    }

    private fun select(key: String, index: Int, position: Long) {
        saveProgress()
        resolveJob?.cancel()
        player.pause()
        player.clearMediaItems()
        activeBook = null
        ListeningPlayback.mutableState.value = ListeningPlayback.state.value.copy(loading = true, playing = false, error = null)
        resolveJob = scope.launch {
            try {
                val book = repository.book(key)
                require(index in book.episodes.indices) { "章节不存在" }
                ListeningPlayback.mutableState.value = ListeningPlaybackState(book = book, index = index, loading = true, lastPlayedAt = System.currentTimeMillis())
                val resource = repository.resolve(book, index)
                repository.markPlayed(book)
                val http = DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(resource.headers)
                    .setConnectTimeoutMs(20_000).setReadTimeoutMs(20_000)
                    .setAllowCrossProtocolRedirects(true)
                // 解析器在媒体加载线程运行，为每个音频或 HLS 分片分别获取该书源的请求头。
                val dataSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(this@TingshuPlaybackService, http)) { spec ->
                    val headers = runBlocking { repository.playbackHeaders(book.sourceId, spec.uri.toString()) }
                    spec.withRequestHeaders(resource.headers + spec.httpRequestHeaders + headers)
                }
                val factory = DefaultMediaSourceFactory(dataSource)
                val episode = book.episodes[index]
                val item = MediaItem.Builder().setUri(resource.url)
                    .setMediaId("tingshu://${book.key}/$index")
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(episode.title).setAlbumTitle(book.title)
                            .setArtworkUri(book.coverUrl.takeIf { it.isNotBlank() }?.let(Uri::parse)).build(),
                    ).build()
                activeBook = book
                activeIndex = index
                introSkipSeconds = preferences.getInt("${book.key}.intro", 0)
                outroSkipSeconds = preferences.getInt("${book.key}.outro", 0)
                player.setMediaSource(factory.createMediaSource(item), position.coerceAtLeast(introSkipSeconds * 1000L))
                player.prepare()
                player.play()
                updateState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                ListeningPlayback.mutableState.value = ListeningPlayback.state.value.copy(
                    loading = false, playing = false, error = error.message ?: "书源解析失败",
                )
            }
        }
    }

    private fun updateState() {
        if (activeBook == null) return
        ListeningPlayback.mutableState.value = ListeningPlayback.state.value.copy(
            book = activeBook,
            index = activeIndex,
            position = player.currentPosition.coerceAtLeast(0),
            duration = player.duration.coerceAtLeast(0),
            playing = player.isPlaying,
            playWhenReady = player.playWhenReady,
            speed = player.playbackParameters.speed,
            sleepSeconds = sleepSeconds,
            introSkipSeconds = introSkipSeconds,
            outroSkipSeconds = outroSkipSeconds,
            sleepEpisodes = sleepEpisodes,
            loading = player.playbackState == Player.STATE_BUFFERING,
        )
    }

    private fun saveProgress() {
        val book = activeBook ?: return
        if (player.duration <= 0) return
        repository.saveProgress(
            book.key,
            ListeningProgress(book.episodes[activeIndex].url, player.currentPosition, player.duration),
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveProgress()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        saveProgress()
        scope.cancel()
        session.release()
        player.release()
        ListeningPlayback.mutableState.value = ListeningPlaybackState()
        super.onDestroy()
    }
}
