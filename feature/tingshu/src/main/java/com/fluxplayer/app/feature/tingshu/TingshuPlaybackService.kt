package com.fluxplayer.app.feature.tingshu

import android.app.PendingIntent
import android.content.Intent
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
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.fluxplayer.app.core.tingshu.ListeningAudioPreferencesProvider
import com.fluxplayer.app.core.tingshu.ListeningBook
import com.fluxplayer.app.core.tingshu.ListeningCoverStore
import com.fluxplayer.app.core.tingshu.ListeningDownloads
import com.fluxplayer.app.core.tingshu.ListeningErrors
import com.fluxplayer.app.core.tingshu.ListeningProgress
import com.fluxplayer.app.core.tingshu.ListeningResource
import com.fluxplayer.app.core.tingshu.TingshuRepository
import com.fluxplayer.app.feature.player.ListeningNotificationArtwork
import com.fluxplayer.app.feature.player.listeningBitmapLoader
import com.fluxplayer.app.feature.player.listeningNotificationButtons
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

data class ListeningPlaybackState(
    val book: ListeningBook? = null,
    val index: Int = 0,
    val position: Long = 0,
    val duration: Long = 0,
    val playing: Boolean = false,
    val playWhenReady: Boolean = false,
    val lastPlayedAt: Long = 0,
    val loading: Boolean = false,
    val resolving: Boolean = false,
    val refreshing: Boolean = false,
    val directoryMessage: String? = null,
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
    const val REFRESH = "tingshu.refresh"

    /** 已知书籍立即成为当前选择，不等待服务连接或书源解析完成。 */
    fun selecting(book: ListeningBook, index: Int, position: Long) {
        mutableState.value = state.value.copy(
            book = book, index = index, position = position.coerceAtLeast(0), duration = 0,
            playing = false, playWhenReady = true, lastPlayedAt = System.currentTimeMillis(),
            loading = true, resolving = true, error = null, refreshing = false, directoryMessage = null,
            introSkipSeconds = 0, outroSkipSeconds = 0,
        )
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
class TingshuPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var repository: TingshuRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var resolveJob: Job? = null
    private var refreshJob: Job? = null
    private var activeBook: ListeningBook? = null
    private var activeIndex = 0
    private var sleepSeconds = 0L
    private val preferences by lazy { getSharedPreferences("tingshu_playback", MODE_PRIVATE) }
    private var introSkipSeconds = 0
    private var outroSkipSeconds = 0
    private var sleepEpisodes = 0
    private var preload: Deferred<ListeningResource>? = null
    private var preloadKey: String? = null
    private var preloadIndex = -1
    private var preloadAt = 0L
    private var retryCount = 0
    private var nextAfterSleep = false
    private var speedSaveJob: Job? = null
    private var artworkJob: Job? = null
    private var artworkKey: String? = null
    private var artworkBytes: ByteArray = ListeningNotificationArtwork.bytes
    private var destroying = false
    private var requireAudioFocus = true

    override fun onCreate() {
        super.onCreate()
        repository = TingshuRepository.get(this)
        com.fluxplayer.app.feature.player.ListeningHistorySessions.register(
            this,
            key = { ListeningPlayback.state.value.book?.key ?: activeBook?.key },
            reset = {
                resolveJob?.cancel()
                refreshJob?.cancel()
                artworkJob?.cancel()
                preload?.cancel()
                activeBook = null
                player.pause()
                player.clearMediaItems()
                player.stop()
                sleepSeconds = 0
                sleepEpisodes = 0
                nextAfterSleep = false
                ListeningPlayback.mutableState.value = ListeningPlaybackState()
            },
        )
        val audioFocusPreferences = (application as? ListeningAudioPreferencesProvider)?.listeningRequireAudioFocus ?: flowOf(true)
        val headsetPreferences = (application as? ListeningAudioPreferencesProvider)?.listeningPauseOnHeadsetDisconnect ?: flowOf(true)
        val initialPreferences = runBlocking(Dispatchers.IO) { audioFocusPreferences.first() to headsetPreferences.first() }
        requireAudioFocus = initialPreferences.first
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                requireAudioFocus,
            )
            setHandleAudioBecomingNoisy(initialPreferences.second)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (ListeningPlayback.state.value.resolving) return
                    updateState()
                    if (playbackState == Player.STATE_ENDED) {
                        saveProgress()
                        val book = activeBook ?: return
                        if (activeIndex == book.episodes.lastIndex) {
                            sleepEpisodes = 0
                            sleepSeconds = 0
                            player.pause()
                            updateState()
                            return
                        }
                        if (sleepEpisodes > 0 && --sleepEpisodes == 0) {
                            nextAfterSleep = activeIndex + 1 < book.episodes.size
                            player.pause()
                            updateState()
                            return
                        }
                        if (activeIndex + 1 < book.episodes.size) select(book.key, activeIndex + 1, 0)
                    }
                }

                override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                    speedSaveJob?.cancel()
                    speedSaveJob = scope.launch {
                        delay(300)
                        preferences.edit().putFloat("speed", playbackParameters.speed).apply()
                    }
                    updateState()
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (ListeningPlayback.state.value.resolving) return
                    if (isPlaying) {
                        ListeningPlayback.mutableState.value = ListeningPlayback.state.value.copy(lastPlayedAt = System.currentTimeMillis())
                        val playedAt = ListeningPlayback.state.value.lastPlayedAt
                        activeBook?.let { book -> scope.launch { repository.markPlayed(book, playedAt) } }
                    }
                    updateState()
                    // 开始播放就保存当前章节，避免首次记录等待周期保存才补齐进度。
                    saveProgress()
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (playWhenReady) {
                        com.fluxplayer.app.feature.player.PlaybackCoordinator.shared.claim(this@TingshuPlaybackService) {
                            player.pause()
                        }
                    }
                    if (ListeningPlayback.state.value.resolving) return
                    if (playWhenReady && ListeningPlayback.state.value.error != null) {
                        val state = ListeningPlayback.state.value
                        state.book?.let { book -> if (state.index in book.episodes.indices) select(book.key, state.index, state.position) }
                        return
                    }
                    if (playWhenReady && nextAfterSleep) {
                        nextAfterSleep = false
                        activeBook?.let { select(it.key, activeIndex + 1, 0) }
                        return
                    }
                    updateState()
                    if (!playWhenReady) saveProgress()
                }

                override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                    // 音频焦点被抢占后明确暂停，通知栏显示继续播放并保存中断位置。
                    if (requireAudioFocus && playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS) player.pause()
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    if (ListeningPlayback.state.value.resolving) return
                    val status = generateSequence<Throwable>(error) { it.cause }.take(12)
                        .filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()?.responseCode
                    val book = activeBook
                    val recoverable = error.errorCode in setOf(
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    ) || status in setOf(401, 403, 410, 429, 500, 502, 503, 504)
                    if (book != null && recoverable && retryCount < 2 && player.playWhenReady) {
                        val position = player.currentPosition.coerceAtLeast(0)
                        retryCount++
                        select(book.key, activeIndex, position, retry = true)
                        return
                    }
                    ListeningPlayback.mutableState.value = ListeningPlayback.state.value.copy(
                        loading = false,
                        playing = false,
                        error = ListeningErrors.describe(error, "音频播放失败", status),
                    )
                }
            })
        }
        player.setPlaybackSpeed(preferences.getFloat("speed", 1f))
        scope.launch {
            audioFocusPreferences.distinctUntilChanged().collect { required ->
                requireAudioFocus = required
                player.setAudioAttributes(player.audioAttributes, required)
            }
        }
        scope.launch {
            headsetPreferences.distinctUntilChanged().collect(player::setHandleAudioBecomingNoisy)
        }
        session = MediaSession.Builder(this, player)
            .setId("fluxplayer.tingshu")
            .setShowPlayButtonIfPlaybackIsSuppressed(true)
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
                        .add(SessionCommand(ListeningPlayback.PREVIOUS, Bundle.EMPTY))
                        .add(SessionCommand(ListeningPlayback.REFRESH, Bundle.EMPTY)).build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands)
                        // 在线播放器只装载当前章节，章节切换统一使用书籍目录命令。
                        .setAvailablePlayerCommands(
                            MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                                .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                                .remove(Player.COMMAND_SEEK_TO_NEXT)
                                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM).build(),
                        )
                        .build()
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
                        ListeningPlayback.REFRESH -> {
                            if (controller.packageName != packageName) return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED))
                            refreshDirectory()
                        }
                        else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .setBitmapLoader(listeningBitmapLoader(this))
            .setMediaButtonPreferences(
                listeningNotificationButtons(
                    SessionCommand(ListeningPlayback.PREVIOUS, Bundle.EMPTY),
                    SessionCommand(ListeningPlayback.NEXT, Bundle.EMPTY),
                ),
            )
            .setSessionActivity(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, TingshuPlayerActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("reopen", true),
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
                val book = activeBook
                if (book != null && player.isPlaying && player.duration > 0 &&
                    player.duration - player.currentPosition <= 60_000 && activeIndex + 1 < book.episodes.size &&
                    (preloadKey != book.key || preloadIndex != activeIndex + 1)
                ) {
                    preload?.cancel()
                    preloadKey = book.key
                    preloadIndex = activeIndex + 1
                    preloadAt = System.currentTimeMillis()
                    val index = preloadIndex
                    preload = scope.async { repository.resolve(book, index) }
                }
                if (player.isPlaying && outroSkipSeconds > 0 && player.duration > 0 && player.currentPosition >= (player.duration - outroSkipSeconds * 1000L).coerceAtLeast(0)) {
                    if (sleepEpisodes > 0 && --sleepEpisodes == 0) {
                        nextAfterSleep = activeBook?.let { activeIndex + 1 < it.episodes.size } == true
                        player.pause()
                    } else {
                        activeBook?.let {
                            if (activeIndex + 1 < it.episodes.size) {
                                select(it.key, activeIndex + 1, 0)
                            } else {
                                sleepEpisodes = 0
                                sleepSeconds = 0
                                player.pause()
                                updateState()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun select(key: String, index: Int, position: Long, retry: Boolean = false) {
        saveProgress()
        resolveJob?.cancel()
        refreshJob?.cancel()
        if (!retry) retryCount = 0
        nextAfterSleep = false
        val selected = repository.recentBooks.value.firstOrNull { it.key == key }
            ?: ListeningPlayback.state.value.book?.takeIf { it.key == key }
        if (selected != null) {
            ListeningPlayback.selecting(selected, index, position)
        } else {
            ListeningPlayback.mutableState.value = ListeningPlayback.state.value.copy(
                book = null, index = index, position = position, duration = 0, playing = false, playWhenReady = true,
                loading = true, resolving = true, error = null, lastPlayedAt = System.currentTimeMillis(),
                refreshing = false, directoryMessage = null,
            )
        }
        player.pause()
        resolveJob = scope.launch {
            try {
                if (retry) delay(retryCount * 1000L)
                val book = repository.book(key)
                require(index in book.episodes.indices) { "章节不存在" }
                ListeningPlayback.mutableState.value = ListeningPlayback.state.value.copy(book = book, index = index, position = position, loading = true, resolving = true)
                val ready = preload.takeIf { !retry && preloadKey == key && preloadIndex == index && System.currentTimeMillis() - preloadAt < 90_000 }
                val offline = ListeningDownloads(this@TingshuPlaybackService).offlineUri(book, index)
                val resource = if (offline != null) {
                    ListeningResource(offline.toString(), emptyMap())
                } else if (ready != null) {
                    try {
                        ready.await()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        repository.resolve(book, index)
                    }
                } else {
                    repository.resolve(book, index)
                }
                preload?.cancel()
                preload = null
                preloadKey = null
                repository.markPlayed(book, ListeningPlayback.state.value.lastPlayedAt)
                // 缓存未命中时才访问书源，并保留每个请求的鉴权头。
                val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(resource.headers)
                    .setConnectTimeoutMs(20_000).setReadTimeoutMs(20_000).setAllowCrossProtocolRedirects(true)
                val dataSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(this@TingshuPlaybackService, http)) { spec ->
                    val headers = if (spec.uri.scheme in setOf("http", "https")) runBlocking { repository.playbackHeaders(book.sourceId, spec.uri.toString()) } else emptyMap()
                    spec.withRequestHeaders(resource.headers + spec.httpRequestHeaders + headers)
                }
                val episode = book.episodes[index]
                val cachedDataSource = ListeningStreamCache.factory(this@TingshuPlaybackService, dataSource, "${book.key}\u0000${episode.url}")
                val factory = DefaultMediaSourceFactory(cachedDataSource)
                val item = MediaItem.Builder().setUri(resource.url)
                    .setMediaId("tingshu://${book.key}/$index")
                    .setMediaMetadata(
                        notificationMetadata(book, episode.title),
                    ).build()
                activeBook = book
                activeIndex = index
                introSkipSeconds = preferences.getInt("${book.key}.intro", 0)
                outroSkipSeconds = preferences.getInt("${book.key}.outro", 0)
                player.setMediaSource(factory.createMediaSource(item), position.coerceAtLeast(introSkipSeconds * 1000L))
                ListeningPlayback.mutableState.value = ListeningPlayback.state.value.copy(resolving = false, error = null)
                player.prepare()
                player.play()
                loadNotificationArtwork(book)
                updateResumeIntent()
                updateState()
            } catch (error: Throwable) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                ListeningPlayback.mutableState.value = ListeningPlayback.state.value.copy(
                    loading = false, resolving = false, playing = false, error = ListeningErrors.describe(error, "播放地址解析失败"),
                )
            }
        }
    }

    private fun notificationMetadata(book: ListeningBook, title: String): MediaMetadata = MediaMetadata.Builder()
        .setTitle(title).setAlbumTitle(book.title).setArtist(book.title)
        .setArtworkData(
            if (artworkKey == "${book.sourceId}:${book.coverUrl}") artworkBytes else ListeningNotificationArtwork.bytes,
            MediaMetadata.PICTURE_TYPE_FRONT_COVER,
        ).build()

    /** 通知只接收实际图片数据，书源内部引用与鉴权由应用异步解析。 */
    private fun loadNotificationArtwork(book: ListeningBook) {
        val key = "${book.sourceId}:${book.coverUrl}"
        if (artworkKey == key) return
        artworkJob?.cancel()
        artworkKey = key
        artworkBytes = ListeningNotificationArtwork.bytes
        if (book.coverUrl.isBlank()) return
        artworkJob = scope.launch {
            val bytes = try {
                val original = ListeningCoverStore.imageBytes(this@TingshuPlaybackService, book.sourceId, book.coverUrl, repository)
                withContext(Dispatchers.IO) { ListeningNotificationArtwork.thumbnail(original) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ListeningNotificationArtwork.bytes
            }
            if (artworkKey != key) return@launch
            artworkBytes = bytes
            val active = activeBook ?: return@launch
            if ("${active.sourceId}:${active.coverUrl}" != key) return@launch
            val item = player.currentMediaItem ?: return@launch
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                item.buildUpon().setMediaMetadata(
                    item.mediaMetadata.buildUpon().setArtworkUri(null)
                        .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build(),
                ).build(),
            )
        }
    }

    private fun updateState() {
        if (activeBook == null) return
        val selected = ListeningPlayback.state.value
        if (selected.resolving || selected.error != null || selected.book?.key != activeBook?.key || selected.index != activeIndex) return
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
        updateResumeIntent()
        if (player.duration <= 0) return
        repository.saveProgress(
            book.key,
            ListeningProgress(book.episodes[activeIndex].url, player.currentPosition, player.duration),
            ListeningPlayback.state.value.lastPlayedAt,
        )
    }

    private fun updateResumeIntent() {
        if (destroying) return
        val book = activeBook ?: return
        session.setSessionActivity(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, TingshuPlayerActivity::class.java).putExtra("reopen", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("book", book.key).putExtra("index", activeIndex).putExtra("position", player.currentPosition.coerceAtLeast(0)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }

    private fun refreshDirectory() {
        if (resolveJob?.isActive == true || refreshJob?.isActive == true) return
        val book = activeBook ?: return
        val episode = book.episodes[activeIndex].url
        ListeningPlayback.mutableState.value = ListeningPlayback.state.value.copy(refreshing = true, directoryMessage = null)
        refreshJob = scope.launch {
            try {
                val refreshed = repository.refreshBook(book)
                val index = refreshed.episodes.indexOfFirst { it.url == episode }
                require(index >= 0) { "原章节已从目录中移除，当前音频仍可继续收听，请重新选择章节" }
                activeBook = refreshed
                activeIndex = index
                player.currentMediaItem?.let { item ->
                    // 仅替换元数据，保持当前音频和进度，通知同步最新章节名称。
                    player.replaceMediaItem(
                        0,
                        item.buildUpon().setMediaId("tingshu://${refreshed.key}/$index")
                            .setMediaMetadata(
                                notificationMetadata(refreshed, refreshed.episodes[index].title),
                            ).build(),
                    )
                }
                loadNotificationArtwork(refreshed)
                preload?.cancel()
                preload = null
                preloadKey = null
                val renamed = refreshed.episodes.count { latest -> book.episodes.any { it.url == latest.url && it.title != latest.title } }
                ListeningPlayback.mutableState.value = ListeningPlayback.state.value.copy(
                    refreshing = false,
                    directoryMessage = "目录已更新：共 ${refreshed.episodes.size} 集，新增 ${(refreshed.episodes.size - book.episodes.size).coerceAtLeast(0)} 集，名称更新 $renamed 集",
                )
                updateResumeIntent()
                updateState()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                ListeningPlayback.mutableState.value = ListeningPlayback.state.value.copy(refreshing = false, directoryMessage = ListeningErrors.describe(error, "目录更新失败"))
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveProgress()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        com.fluxplayer.app.feature.player.ListeningHistorySessions.unregister(this)
        com.fluxplayer.app.feature.player.PlaybackCoordinator.shared.release(this)
        destroying = true
        preferences.edit().putFloat("speed", player.playbackParameters.speed).apply()
        saveProgress()
        scope.cancel()
        session.release()
        player.release()
        ListeningPlayback.mutableState.value = ListeningPlaybackState()
        super.onDestroy()
    }
}
