package com.fluxplayer.app.feature.player.service

import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.util.Log
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION
import androidx.media3.common.Player.DISCONTINUITY_REASON_REMOVE
import androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.CommandButton
import androidx.media3.session.CommandButton.ICON_UNDEFINED
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import com.fluxplayer.app.core.common.extensions.deleteFiles
import com.fluxplayer.app.core.common.extensions.fromUri
import com.fluxplayer.app.core.common.CloudUriScheme
import com.fluxplayer.app.core.common.CloudPlaylistCache
import com.fluxplayer.app.core.common.extensions.getFilenameFromUri
import com.fluxplayer.app.core.data.cloud.CloudUriResolver
import com.fluxplayer.app.core.data.cache.PlaybackCacheManager
import com.fluxplayer.app.core.common.CloudAwareCacheKeyRegistry
import com.fluxplayer.app.core.common.extensions.getLocalSubtitles
import com.fluxplayer.app.core.common.extensions.getPath
import com.fluxplayer.app.core.common.extensions.subtitleCacheDir
import com.fluxplayer.app.core.data.repository.MediaRepository
import com.fluxplayer.app.core.data.repository.PlaybackHistoryRepository
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.model.DecoderPriority
import com.fluxplayer.app.core.model.LoopMode
import com.fluxplayer.app.core.model.PlayerPreferences
import com.fluxplayer.app.core.model.Resume
import com.fluxplayer.app.core.model.VideoSource
import com.fluxplayer.app.core.ui.R as coreUiR
import com.fluxplayer.app.feature.player.PlayerActivity
import com.fluxplayer.app.feature.player.R
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.fluxplayer.app.feature.player.extensions.addAdditionalSubtitleConfiguration
import com.fluxplayer.app.feature.player.extensions.audioTrackIndex
import com.fluxplayer.app.feature.player.extensions.copy
import com.fluxplayer.app.feature.player.extensions.getManuallySelectedTrackIndex
import com.fluxplayer.app.feature.player.extensions.playbackSpeed
import com.fluxplayer.app.feature.player.extensions.positionMs
import com.fluxplayer.app.feature.player.extensions.setExtras
import com.fluxplayer.app.feature.player.extensions.setIsScrubbingModeEnabled
import com.fluxplayer.app.feature.player.extensions.subtitleDelayMilliseconds
import com.fluxplayer.app.feature.player.extensions.subtitleSpeed
import com.fluxplayer.app.feature.player.extensions.subtitleTrackIndex
import com.fluxplayer.app.feature.player.extensions.switchTrack
import com.fluxplayer.app.feature.player.extensions.uriToSubtitleConfiguration
import com.fluxplayer.app.feature.player.extensions.videoZoom
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleDelayMilliseconds
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleSpeed
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlayerService : MediaSessionService() {

    private val serviceScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mediaSession: MediaSession? = null
    private var artworkLoadJob: Job? = null

    @Inject
    lateinit var preferencesRepository: PreferencesRepository

    @Inject
    lateinit var cloudUriResolver: CloudUriResolver

    @Inject
    lateinit var mediaRepository: MediaRepository

    @Inject
    lateinit var playbackHistoryRepository: PlaybackHistoryRepository

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var playbackCacheManager: PlaybackCacheManager

    @Inject
    lateinit var cacheKeyRegistry: CloudAwareCacheKeyRegistry

    private lateinit var mediaSourceFactory: CloudAwareMediaSourceFactory

    private val playerPreferences: PlayerPreferences
        get() = preferencesRepository.playerPreferences.value

    private val customCommands = CustomCommands.asSessionCommands()

    private var isMediaItemReady = false

    /**
     * 当前播放器会话内的倍速。会话开始时初始化为全局默认倍速，用户修改倍速时更新，
     * 切集/切清晰度时重新应用，Service 销毁后随实例消失（下次进入重新取全局默认值）。
     * 这样可在未退出播放界面的情况下切集时保留用户设置的倍速。
     */
    private var sessionPlaybackSpeed: Float = 1.0f

    private var loudnessEnhancer: LoudnessEnhancer? = null

    companion object {
        private const val TAG = "PlayerService"
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "wma", "opus")
    }
    private var currentVolumeGain: Int = 0

    private val playbackStateListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            super.onMediaItemTransition(mediaItem, reason)
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
            isMediaItemReady = false
            loadArtworkForCurrentMediaItem()
            mediaItem?.mediaMetadata?.let { metadata ->
                mediaSession?.player?.run {
                    playerSpecificSubtitleDelayMilliseconds = metadata.subtitleDelayMilliseconds ?: 0L
                    playerSpecificSubtitleSpeed = metadata.subtitleSpeed ?: 1f
                    // 切集时重新应用会话倍速，避免 Media3 未保留 PlaybackParameters 或被 STATE_IDLE 重置。
                    setPlaybackSpeed(sessionPlaybackSpeed)
                }

                metadata.positionMs?.takeIf { playerPreferences.resume == Resume.YES }?.let {
                    mediaSession?.player?.seekTo(it)
                }
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            super.onPositionDiscontinuity(oldPosition, newPosition, reason)
            val oldMediaItem = oldPosition.mediaItem ?: return

            when (reason) {
                DISCONTINUITY_REASON_SEEK,
                DISCONTINUITY_REASON_AUTO_TRANSITION,
                -> {
                    if (newPosition.mediaItem == null || oldMediaItem == newPosition.mediaItem) return

                    val updatedPosition = oldPosition.positionMs.takeIf { reason == DISCONTINUITY_REASON_SEEK } ?: C.TIME_UNSET
                    mediaSession?.player?.replaceMediaItem(
                        oldPosition.mediaItemIndex,
                        oldMediaItem.copy(positionMs = updatedPosition),
                    )
                    serviceScope.launch {
                        mediaRepository.updateMediumPosition(
                            uri = oldMediaItem.mediaId,
                            position = updatedPosition,
                        )
                    }
                    serviceScope.launch {
                        recordPlaybackHistory(
                            mediaItem = oldMediaItem,
                            position = updatedPosition.takeIf { it != C.TIME_UNSET }
                                ?: oldPosition.positionMs,
                        )
                    }
                }

                DISCONTINUITY_REASON_REMOVE -> {
                    serviceScope.launch {
                        val durationMs = oldMediaItem.mediaMetadata.durationMs
                        val isAtEnd = durationMs != null && oldPosition.positionMs >= durationMs - 1000
                        mediaRepository.updateMediumPosition(
                            uri = oldMediaItem.mediaId,
                            position = if (isAtEnd) C.TIME_UNSET else oldPosition.positionMs,
                        )
                    }
                    serviceScope.launch {
                        recordPlaybackHistory(
                            mediaItem = oldMediaItem,
                            position = oldPosition.positionMs,
                        )
                    }
                }

                else -> return
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            super.onTracksChanged(tracks)
            if (!isMediaItemReady && tracks.groups.isNotEmpty()) {
                isMediaItemReady = true

                if (!playerPreferences.rememberSelections) return
                mediaSession?.player?.mediaMetadata?.audioTrackIndex?.let {
                    mediaSession?.player?.switchTrack(C.TRACK_TYPE_AUDIO, it)
                }
                mediaSession?.player?.mediaMetadata?.subtitleTrackIndex?.let {
                    mediaSession?.player?.switchTrack(C.TRACK_TYPE_TEXT, it)
                }
            }
        }

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            super.onTrackSelectionParametersChanged(parameters)
            val player = mediaSession?.player ?: return
            val currentMediaItem = player.currentMediaItem ?: return

            val audioTrackIndex = player.getManuallySelectedTrackIndex(C.TRACK_TYPE_AUDIO)
            val subtitleTrackIndex = player.getManuallySelectedTrackIndex(C.TRACK_TYPE_TEXT)

            if (audioTrackIndex != null) {
                serviceScope.launch {
                    mediaRepository.updateMediumAudioTrack(
                        uri = currentMediaItem.mediaId,
                        audioTrackIndex = audioTrackIndex,
                    )
                }
            }

            if (subtitleTrackIndex != null) {
                serviceScope.launch {
                    mediaRepository.updateMediumSubtitleTrack(
                        uri = currentMediaItem.mediaId,
                        subtitleTrackIndex = subtitleTrackIndex,
                    )
                }
            }

            player.replaceMediaItem(
                player.currentMediaItemIndex,
                currentMediaItem.copy(
                    audioTrackIndex = audioTrackIndex,
                    subtitleTrackIndex = subtitleTrackIndex,
                ),
            )
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            super.onPlaybackParametersChanged(playbackParameters)
            val player = mediaSession?.player ?: return
            val currentMediaItem = player.currentMediaItem ?: return
            val playbackSpeed = playbackParameters.speed

            // 追踪当前会话倍速，切集/切清晰度时重新应用（而不是重置为全局默认）。
            sessionPlaybackSpeed = playbackSpeed

            serviceScope.launch {
                mediaRepository.updateMediumPlaybackSpeed(
                    uri = currentMediaItem.mediaId,
                    playbackSpeed = playbackSpeed,
                )
            }
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                currentMediaItem.copy(playbackSpeed = playbackSpeed),
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)

            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                mediaSession?.player?.trackSelectionParameters = TrackSelectionParameters.DEFAULT
                // 切清晰度(setMediaItems→STATE_IDLE)或自动播完切下一集(STATE_ENDED)时，
                // 保留会话倍速而非重置为全局默认，符合"未退出播放界面时保留用户倍速"的预期。
                mediaSession?.player?.setPlaybackSpeed(sessionPlaybackSpeed)
            }

            if (playbackState == Player.STATE_READY) {
                mediaSession?.player?.let {
                    serviceScope.launch {
                        mediaRepository.updateMediumLastPlayedTime(
                            uri = it.currentMediaItem?.mediaId ?: return@launch,
                            lastPlayedTime = System.currentTimeMillis(),
                        )
                    }
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            super.onPlayWhenReadyChanged(playWhenReady, reason)

            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                if (mediaSession?.player?.repeatMode != Player.REPEAT_MODE_OFF) {
                    mediaSession?.player?.seekTo(0)
                    mediaSession?.player?.play()
                    return
                }
                mediaSession?.run {
                    player.clearMediaItems()
                    player.stop()
                }
                stopSelf()
            }
        }

        override fun onRenderedFirstFrame() {
            super.onRenderedFirstFrame()
            val player = mediaSession?.player ?: return
            val currentMediaItem = player.currentMediaItem ?: return
            // Update the media metadata duration so that it will be used later in position discontinuity handling
            player.replaceMediaItem(
                player.currentMediaItemIndex,
                currentMediaItem.copy(durationMs = player.duration.coerceAtLeast(0))
            )
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
            mediaSession?.run {
                serviceScope.launch {
                    mediaRepository.updateMediumPosition(
                        uri = player.currentMediaItem?.mediaId ?: return@launch,
                        position = player.currentPosition,
                    )
                }
            }
            // 暂停时才记录播放历史（播放时不记录）
            if (isPlaying) return
            val player = mediaSession?.player ?: return
            // 播放器 IDLE 状态时由 STOP_PLAYER_SESSION 统一处理，避免重复覆盖
            if (player.playbackState == Player.STATE_IDLE) return
            val currentMediaItem = player.currentMediaItem ?: return
            serviceScope.launch {
                recordPlaybackHistory(
                    mediaItem = currentMediaItem,
                    position = player.currentPosition,
                )
            }
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            super.onRepeatModeChanged(repeatMode)
            serviceScope.launch {
                preferencesRepository.updatePlayerPreferences {
                    it.copy(
                        loopMode = when (repeatMode) {
                            Player.REPEAT_MODE_OFF -> LoopMode.OFF
                            Player.REPEAT_MODE_ONE -> LoopMode.ONE
                            Player.REPEAT_MODE_ALL -> LoopMode.ALL
                            else -> LoopMode.OFF
                        },
                    )
                }
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            super.onAudioSessionIdChanged(audioSessionId)
            if (!playerPreferences.enableVolumeBoost) return
            if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) return
            try {
                loudnessEnhancer?.release()
                loudnessEnhancer = LoudnessEnhancer(audioSessionId)
                if (currentVolumeGain > 0) {
                    setEnhancerTargetGain(currentVolumeGain)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                loudnessEnhancer = null
            }
        }

        /** 捕获播放错误并详细记录 */
        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)
            Log.e(TAG, "========== onPlayerError ==========")
            Log.e(TAG, "onPlayerError: errorCode=${error.errorCode}, errorCodeName=${error.errorCodeName}")
            Log.e(TAG, "onPlayerError: message=${error.message}")
            Log.e(TAG, "onPlayerError: cause=${error.cause}")
            
            // 获取当前播放的媒体项信息
            val currentMediaItem = mediaSession?.player?.currentMediaItem
            Log.e(TAG, "onPlayerError: currentMediaItem=${currentMediaItem?.mediaId}")
            Log.e(TAG, "onPlayerError: currentMediaItem URI=${currentMediaItem?.localConfiguration?.uri}")
            
            // 记录详细的错误信息
            when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> {
                    Log.e(TAG, "onPlayerError: Network connection failed")
                }
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> {
                    Log.e(TAG, "onPlayerError: File not found")
                }
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> {
                    Log.e(TAG, "onPlayerError: Decoder init failed")
                }
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> {
                    Log.e(TAG, "onPlayerError: Malformed container")
                }
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> {
                    Log.e(TAG, "onPlayerError: Malformed manifest")
                }
                else -> {
                    Log.e(TAG, "onPlayerError: Other error code=${error.errorCode}")
                }
            }
            
            // 打印完整的堆栈跟踪
            Log.e(TAG, "onPlayerError: stack trace:", error)
            Log.e(TAG, "========== onPlayerError END ==========")
        }
    }

    private fun setEnhancerTargetGain(gain: Int) {
        val enhancer = loudnessEnhancer ?: return

        try {
            enhancer.setTargetGain(gain)
            enhancer.enabled = gain > 0
            currentVolumeGain = enhancer.targetGain.toInt()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val connectionResult = super.onConnect(session, controller)
            return MediaSession.ConnectionResult.accept(
                connectionResult.availableSessionCommands
                    .buildUpon()
                    .addSessionCommands(customCommands)
                    .build(),
                connectionResult.availablePlayerCommands,
            )
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = serviceScope.future(Dispatchers.Default) {
            // 纯音频模式：跳过 DB 查询、字幕扫描、artwork 加载
            // 仍需从 mediaId 重建 URI（localConfiguration 可能在 IPC 传输中丢失）
            if (mediaItems.all { isAudioFile(it.mediaId) }) {
                // 听书倍速独立于视频播放设置，从 DataStore 读取上次保存的倍速
                sessionPlaybackSpeed = preferencesRepository.applicationPreferences.value.audiobookPlaybackSpeed
                val updatedItems = mediaItems.map { item ->
                    item.buildUpon()
                        .setUri(Uri.fromFile(File(item.mediaId)))
                        .build()
                }
                return@future MediaSession.MediaItemsWithStartPosition(updatedItems, startIndex, startPositionMs)
            }
            val updatedMediaItems = updatedMediaItemsWithMetadata(mediaItems)
            return@future MediaSession.MediaItemsWithStartPosition(updatedMediaItems, startIndex, startPositionMs)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = serviceScope.future(Dispatchers.Default) {
            // 纯音频模式：跳过 metadata 增强，但仍需从 mediaId 重建 URI
            if (mediaItems.all { isAudioFile(it.mediaId) }) {
                sessionPlaybackSpeed = preferencesRepository.applicationPreferences.value.audiobookPlaybackSpeed
                val updatedItems = mediaItems.map { item ->
                    item.buildUpon()
                        .setUri(Uri.fromFile(File(item.mediaId)))
                        .build()
                }
                return@future updatedItems.toMutableList()
            }
            val updatedMediaItems = updatedMediaItemsWithMetadata(mediaItems)
            return@future updatedMediaItems.toMutableList()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> = serviceScope.future {
            val command = CustomCommands.fromSessionCommand(customCommand)
                ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)

            when (command) {
                CustomCommands.ADD_SUBTITLE_TRACK -> {
                    val subtitleUri = args.getString(CustomCommands.SUBTITLE_TRACK_URI_KEY)?.toUri()
                        ?: return@future SessionResult(SessionError.ERROR_BAD_VALUE)

                    val newSubConfiguration = uriToSubtitleConfiguration(
                        uri = subtitleUri,
                        subtitleEncoding = playerPreferences.subtitleTextEncoding,
                    )
                    mediaSession?.player?.let { player ->
                        val currentMediaItem = player.currentMediaItem ?: return@let
                        val textTracks = player.currentTracks.groups.filter {
                            it.type == C.TRACK_TYPE_TEXT && it.isSupported
                        }

                        mediaRepository.updateMediumPosition(
                            uri = currentMediaItem.mediaId,
                            position = player.currentPosition,
                        )
                        mediaRepository.updateMediumSubtitleTrack(
                            uri = currentMediaItem.mediaId,
                            subtitleTrackIndex = textTracks.size,
                        )
                        mediaRepository.addExternalSubtitleToMedium(
                            uri = currentMediaItem.mediaId,
                            subtitleUri = subtitleUri,
                        )
                        player.addAdditionalSubtitleConfiguration(newSubConfiguration)
                    }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.SET_SKIP_SILENCE_ENABLED -> {
                    val enabled = args.getBoolean(CustomCommands.SKIP_SILENCE_ENABLED_KEY)
                    mediaSession?.player?.playerSpecificSkipSilenceEnabled = enabled
                    mediaSession?.sessionExtras = Bundle().apply {
                        putBoolean(CustomCommands.SKIP_SILENCE_ENABLED_KEY, enabled)
                    }
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_SKIP_SILENCE_ENABLED -> {
                    val enabled = mediaSession?.player?.playerSpecificSkipSilenceEnabled ?: false
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putBoolean(CustomCommands.SKIP_SILENCE_ENABLED_KEY, enabled)
                        },
                    )
                }

                CustomCommands.SET_IS_SCRUBBING_MODE_ENABLED -> {
                    val enabled = args.getBoolean(CustomCommands.IS_SCRUBBING_MODE_ENABLED_KEY)
                    mediaSession?.player?.setIsScrubbingModeEnabled(enabled)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.IS_LOUDNESS_GAIN_SUPPORTED -> {
                    val isSupported = loudnessEnhancer != null
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putBoolean(CustomCommands.IS_LOUDNESS_GAIN_SUPPORTED_KEY, isSupported)
                        },
                    )
                }

                CustomCommands.SET_LOUDNESS_GAIN -> {
                    val gain = args.getInt(CustomCommands.LOUDNESS_GAIN_KEY, 0)
                    setEnhancerTargetGain(gain)
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_LOUDNESS_GAIN -> {
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putInt(CustomCommands.LOUDNESS_GAIN_KEY, currentVolumeGain)
                        },
                    )
                }

                CustomCommands.GET_SUBTITLE_DELAY -> {
                    val subtitleDelay = mediaSession?.player?.playerSpecificSubtitleDelayMilliseconds ?: 0
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putLong(CustomCommands.SUBTITLE_DELAY_KEY, subtitleDelay)
                        },
                    )
                }

                CustomCommands.SET_SUBTITLE_DELAY -> {
                    val subtitleDelay = args.getLong(CustomCommands.SUBTITLE_DELAY_KEY)
                    mediaSession?.player?.playerSpecificSubtitleDelayMilliseconds = subtitleDelay
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.GET_SUBTITLE_SPEED -> {
                    val subtitleSpeed = mediaSession?.player?.playerSpecificSubtitleSpeed ?: 0f
                    return@future SessionResult(
                        SessionResult.RESULT_SUCCESS,
                        Bundle().apply {
                            putFloat(CustomCommands.SUBTITLE_SPEED_KEY, subtitleSpeed)
                        },
                    )
                }

                CustomCommands.SET_SUBTITLE_SPEED -> {
                    val subtitleSpeed = args.getFloat(CustomCommands.SUBTITLE_SPEED_KEY)
                    mediaSession?.player?.playerSpecificSubtitleSpeed = subtitleSpeed
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }

                CustomCommands.STOP_PLAYER_SESSION -> {
                    val stopMediaItem = mediaSession?.player?.currentMediaItem
                    val stopPosition = mediaSession?.player?.currentPosition ?: 0L
                    mediaSession?.run {
                        serviceScope.launch {
                            mediaRepository.updateMediumPosition(
                                uri = player.currentMediaItem?.mediaId ?: return@launch,
                                position = player.currentPosition,
                            )
                        }
                    }
                    if (stopMediaItem != null) {
                        recordPlaybackHistory(
                            mediaItem = stopMediaItem,
                            position = stopPosition,
                        )
                    }
                    mediaSession?.run {
                        player.clearMediaItems()
                        player.stop()
                    }
                    stopSelf()
                    return@future SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onCreate() {
        super.onCreate()
        val renderersFactory = NextRenderersFactory(applicationContext)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                when (playerPreferences.decoderPriority) {
                    DecoderPriority.DEVICE_ONLY -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                    DecoderPriority.PREFER_DEVICE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    DecoderPriority.PREFER_APP -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                },
            )

        val trackSelector = DefaultTrackSelector(applicationContext).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage(playerPreferences.preferredAudioLanguage)
                    .setPreferredTextLanguage(playerPreferences.preferredSubtitleLanguage),
            )
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,      // 15000: 最少缓冲 15 秒即可开始播放
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,      // 60000: 最大缓冲 60 秒
                2500,    // 起播缓冲 2.5 秒（云盘流媒体加速起播）
                5000,    // 重缓冲后 5 秒恢复（原 10 秒，减少卡顿等待）
            )
            .setBackBuffer(5000, false)
            .setTargetBufferBytes(DefaultLoadControl.DEFAULT_TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        mediaSourceFactory = CloudAwareMediaSourceFactory(
            authAwareFactory = AuthAwareDataSourceFactory(applicationContext),
            cloudUriResolver = cloudUriResolver,
            cacheKeyRegistry = cacheKeyRegistry,
            playbackCacheManager = playbackCacheManager,
        ).also {
            if (playerPreferences.playbackCacheEnabled) {
                it.updateCacheSettings(playerPreferences.playbackCacheMaxSize.bytes)
            }
        }

        val player = ExoPlayer.Builder(applicationContext)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                playerPreferences.requireAudioFocus,
            )
            .setHandleAudioBecomingNoisy(playerPreferences.pauseOnHeadsetDisconnect)
            .build()
            .also {
                it.addListener(playbackStateListener)
                it.pauseAtEndOfMediaItems = !playerPreferences.autoplay
                it.repeatMode = when (playerPreferences.loopMode) {
                    LoopMode.OFF -> Player.REPEAT_MODE_OFF
                    LoopMode.ONE -> Player.REPEAT_MODE_ONE
                    LoopMode.ALL -> Player.REPEAT_MODE_ALL
                }
                // 会话倍速以全局默认值起步，之后由用户的修改驱动（见 onPlaybackParametersChanged）。
                sessionPlaybackSpeed = playerPreferences.defaultPlaybackSpeed
                it.setPlaybackSpeed(sessionPlaybackSpeed)
            }

        try {
            mediaSession = MediaSession.Builder(this, player).apply {
                setSessionActivity(
                    PendingIntent.getActivity(
                        this@PlayerService,
                        0,
                        Intent(this@PlayerService, PlayerActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                setCallback(mediaSessionCallback)
                setCustomLayout(
                    listOf(
                        CommandButton.Builder(ICON_UNDEFINED)
                            .setCustomIconResId(coreUiR.drawable.ic_close)
                            .setDisplayName(getString(coreUiR.string.stop_player_session))
                            .setSessionCommand(CustomCommands.STOP_PLAYER_SESSION.sessionCommand)
                            .setEnabled(true)
                            .build(),
                    ),
                )
            }.build()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 监听缓存开关变化，实时更新 MediaSourceFactory
        serviceScope.launch {
            preferencesRepository.playerPreferences
                .map { it.playbackCacheEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) {
                        mediaSourceFactory.updateCacheSettings(
                            preferencesRepository.playerPreferences.value.playbackCacheMaxSize.bytes,
                        )
                    } else {
                        mediaSourceFactory.disableCache()
                        playbackCacheManager.clearCache()
                        cacheKeyRegistry.clear()
                    }
                }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player!!
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        artworkLoadJob?.cancel()
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        mediaSession?.run {
            player.clearMediaItems()
            player.stop()
            player.removeListener(playbackStateListener)
            player.release()
            release()
            mediaSession = null
        }
        playbackCacheManager.release()
        subtitleCacheDir.deleteFiles()
        serviceScope.cancel()
    }

    private suspend fun updatedMediaItemsWithMetadata(
        mediaItems: List<MediaItem>,
    ): List<MediaItem> = supervisorScope {
        mediaItems.map { mediaItem ->
            async {
                val mediaId = mediaItem.mediaId
                val uri = mediaId.toUri()

                // Pre-resolve cloud URIs on background thread to avoid blocking ExoPlayer start
                val resolvedUri = if (CloudUriScheme.isCloudUri(uri)) {
                    try {
                        val resolved = cloudUriResolver.resolve(uri)
                        if (resolved != null) {
                            cacheKeyRegistry?.register(resolved.toString(), uri.toString())
                            Log.d(TAG, "Pre-resolved cloud URI: $uri -> $resolved")
                        }
                        resolved
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to pre-resolve cloud URI: $uri", e)
                        null
                    }
                } else {
                    null
                }

                val video = mediaRepository.getVideoByUri(uri = mediaId)
                val videoState = mediaRepository.getVideoState(uri = mediaId)

                val externalSubs = videoState?.externalSubs ?: emptyList()
                val localSubs = (videoState?.path ?: getPath(uri))?.let {
                    File(it).getLocalSubtitles(
                        context = this@PlayerService,
                        excludeSubsList = externalSubs,
                    )
                } ?: emptyList()

                val existingSubConfigurations = mediaItem.localConfiguration?.subtitleConfigurations ?: emptyList()
                val subConfigurations = (localSubs + externalSubs).map { subtitleUri ->
                    uriToSubtitleConfiguration(
                        uri = subtitleUri,
                        subtitleEncoding = playerPreferences.subtitleTextEncoding,
                    )
                }

                // Use placeholder artwork initially - actual artwork will be loaded in background
                val artworkUri = getDefaultArtworkUri()

                val title = mediaItem.mediaMetadata.title
                    ?: video?.nameWithExtension
                    ?: getTitleForUri(uri)
                val positionMs = mediaItem.mediaMetadata.positionMs ?: videoState?.position
                val videoScale = mediaItem.mediaMetadata.videoZoom ?: videoState?.videoScale
                val playbackSpeed = mediaItem.mediaMetadata.playbackSpeed ?: videoState?.playbackSpeed
                val audioTrackIndex = mediaItem.mediaMetadata.audioTrackIndex ?: videoState?.audioTrackIndex
                val subtitleTrackIndex = mediaItem.mediaMetadata.subtitleTrackIndex ?: videoState?.subtitleTrackIndex
                val subtitleDelay = mediaItem.mediaMetadata.subtitleDelayMilliseconds ?: videoState?.subtitleDelayMilliseconds
                val subtitleSpeed = mediaItem.mediaMetadata.subtitleSpeed ?: videoState?.subtitleSpeed

                mediaItem.buildUpon().apply {
                    // Use pre-resolved HTTP URL so ExoPlayer starts buffering immediately
                    if (resolvedUri != null) {
                        setUri(resolvedUri)
                        setMediaId(mediaId)
                    }
                    setSubtitleConfigurations(existingSubConfigurations + subConfigurations)
                    setMediaMetadata(
                        MediaMetadata.Builder().apply {
                            setTitle(title)
                            setArtworkUri(artworkUri)
                            setExtras(
                                positionMs = positionMs,
                                videoScale = videoScale,
                                playbackSpeed = playbackSpeed,
                                audioTrackIndex = audioTrackIndex,
                                subtitleTrackIndex = subtitleTrackIndex,
                                subtitleDelayMilliseconds = subtitleDelay,
                                subtitleSpeed = subtitleSpeed,
                            )
                        }.build(),
                    )
                }.build()
            }
        }.awaitAll()
    }
    
    private suspend fun recordPlaybackHistory(mediaItem: MediaItem, position: Long) {
        val uri = mediaItem.mediaId
        // 纯音频文件（本地文件 + 音频扩展名）不记录到播放历史
        if (isAudioFile(uri)) return

        val title = mediaItem.mediaMetadata.title?.toString()
            ?: getFilenameFromUri(uri.toUri())
        val duration = mediaItem.mediaMetadata.durationMs ?: 0L
        val source = VideoSource.fromUri(uri)
        // 从 PlayerFrameCapture 取出退出时截取的缩略图路径
        val preCapturedPath = PlayerFrameCapture.take(uri)
        // 计算父目录名
        val parentPath = computeParentPath(uri, source)
        playbackHistoryRepository.recordPlayback(
            uriString = uri,
            title = title,
            source = source,
            position = position,
            duration = duration,
            originalUriString = if (source == VideoSource.WEBDAV) uri else null,
            thumbnailPath = preCapturedPath,
            parentPath = parentPath,
        )
    }

    private fun isAudioFile(uri: String): Boolean {
        val ext = uri.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    private fun computeParentPath(uriString: String, source: VideoSource): String? {
        val uri = uriString.toUri()
        return when (source) {
            VideoSource.LOCAL -> {
                try {
                    File(uri.path).parentFile?.name
                } catch (_: Exception) {
                    null
                }
            }
            VideoSource.WEBDAV -> {
                // 优先从 CloudPlaylistCache 获取完整 parentPath
                val cachePath = uri.path?.let { filePath ->
                    CloudPlaylistCache.getFileMetadata("webdav", filePath)?.parentPath
                }
                if (cachePath != null) return cachePath
                // 回退：从路径提取父目录名
                val segments = uri.path?.trimEnd('/')?.split("/")?.filter { it.isNotEmpty() } ?: return null
                segments.dropLast(1).lastOrNull()
            }
            VideoSource.OPENLIST -> {
                // OpenList URI 格式：http://127.0.0.1:5244/d/path/to/file → 去掉 /d 前缀即文件 path
                val filePath = uri.path?.removePrefix("/d") ?: uri.path
                if (filePath != null) {
                    CloudPlaylistCache.getFileMetadata("openlist", filePath)?.parentPath
                } else null
            }
            VideoSource.QUARK, VideoSource.UC,
            VideoSource.ALIYUN,
            VideoSource.PAN123,
            VideoSource.CLOUD189,
            VideoSource.YUN139 -> {
                val provider = CloudUriScheme.getProvider(uri) ?: return null
                val fileId = CloudUriScheme.getFileId(uri) ?: return null
                CloudPlaylistCache.getFileMetadata(provider, fileId)?.parentPath
            }
            else -> null
        }
    }

    private fun getDefaultArtworkUri(): Uri = Uri.Builder().apply {
        val defaultArtwork = R.drawable.artwork_default
        scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        authority(resources.getResourcePackageName(defaultArtwork))
        appendPath(resources.getResourceTypeName(defaultArtwork))
        appendPath(resources.getResourceEntryName(defaultArtwork))
    }.build()

    private fun loadArtworkForCurrentMediaItem() {
        artworkLoadJob?.cancel()
        artworkLoadJob = serviceScope.launch(Dispatchers.Main) {
            val player = mediaSession?.player ?: return@launch
            val currentMediaItem = player.currentMediaItem ?: return@launch
            if (currentMediaItem.mediaMetadata.artworkData != null) return@launch

            val artworkUri = loadArtworkForMediaItem(currentMediaItem) ?: return@launch

            val updatedPlayer = mediaSession?.player ?: return@launch
            val updatedMediaItem = updatedPlayer.currentMediaItem ?: return@launch
            if (updatedMediaItem.mediaId != currentMediaItem.mediaId) return@launch

            updatedPlayer.replaceMediaItem(
                updatedPlayer.currentMediaItemIndex,
                updatedMediaItem.withArtwork(artworkUri),
            )
        }
    }
    private suspend fun loadArtworkForMediaItem(mediaItem: MediaItem): Uri? = withContext(Dispatchers.IO) {
        val uri = mediaItem.mediaId.toUri()
        return@withContext try {
            val request = ImageRequest.Builder(this@PlayerService)
                .data(uri)
                .size(512, 512)
                .build()
            imageLoader.execute(request)
            val diskCache = imageLoader.diskCache ?: return@withContext null
            return@withContext diskCache.openSnapshot(uri.toString())?.use { snapshot ->
                snapshot.data.toFile().toUri()
            }
        } catch (_: Throwable) {
            null
        }
    }
    private fun MediaItem.withArtwork(uri: Uri): MediaItem = buildUpon()
        .setMediaMetadata(
            mediaMetadata.buildUpon()
                .setArtworkUri(uri)
                .build(),
        )
        .build()

    /**
     * 为 URI 获取可读的标题，对 cloud:// URI 做特殊处理。
     */
    private fun getTitleForUri(uri: Uri): String {
        // 云盘 URI：尝试从 CloudPlaylistCache 获取缓存的视频名
        if (CloudUriScheme.isCloudUri(uri)) {
            val provider = CloudUriScheme.getProvider(uri)
            val fileId = CloudUriScheme.getFileId(uri)
            if (provider != null && fileId != null) {
                val metadata = CloudPlaylistCache.getFileMetadata(provider, fileId)
                if (metadata != null) {
                    return metadata.fileName
                }
                // fallback: provider/fileId
                return "$provider/$fileId"
            }
        }
        // 普通 URI：用 getFilenameFromUri
        return getFilenameFromUri(uri)
    }
}

@get:UnstableApi
@set:UnstableApi
private var Player.playerSpecificSkipSilenceEnabled: Boolean
    @OptIn(UnstableApi::class)
    get() = when (this) {
        is ExoPlayer -> this.skipSilenceEnabled
        else -> false
    }
    set(value) {
        when (this) {
            is ExoPlayer -> this.skipSilenceEnabled = value
        }
    }

@get:UnstableApi
@set:UnstableApi
private var Player.playerSpecificSubtitleDelayMilliseconds: Long
    @OptIn(UnstableApi::class)
    get() = when (this) {
        is ExoPlayer -> this.subtitleDelayMilliseconds
        else -> 0L
    }
    set(value) {
        when (this) {
            is ExoPlayer -> this.subtitleDelayMilliseconds = value
        }
    }

@get:UnstableApi
@set:UnstableApi
private var Player.playerSpecificSubtitleSpeed: Float
    @OptIn(UnstableApi::class)
    get() = when (this) {
        is ExoPlayer -> this.subtitleSpeed
        else -> 0f
    }
    set(value) {
        when (this) {
            is ExoPlayer -> this.subtitleSpeed = value
        }
    }
