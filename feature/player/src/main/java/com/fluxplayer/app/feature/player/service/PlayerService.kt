package com.fluxplayer.app.feature.player.service

import android.app.PendingIntent
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
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import com.fluxplayer.app.core.common.extensions.deleteFiles
import com.fluxplayer.app.core.common.CloudUriScheme
import com.fluxplayer.app.core.common.Pan123FallbackCache
import com.fluxplayer.app.core.data.cloud.CloudUriResolver
import com.fluxplayer.app.core.data.cache.PlaybackCacheManager
import com.fluxplayer.app.core.common.CloudAwareCacheKeyRegistry
import com.fluxplayer.app.core.common.extensions.subtitleCacheDir
import com.fluxplayer.app.core.data.repository.MediaRepository
import com.fluxplayer.app.core.data.repository.PlaybackHistoryRepository
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.model.DecoderPriority
import com.fluxplayer.app.core.model.LoopMode
import com.fluxplayer.app.core.model.PlayerPreferences
import com.fluxplayer.app.core.model.Resume
import com.fluxplayer.app.core.ui.R as coreUiR
import com.fluxplayer.app.feature.player.PlayerActivity
import com.fluxplayer.app.feature.player.extensions.addAdditionalSubtitleConfiguration
import com.fluxplayer.app.feature.player.extensions.audioTrackIndex
import com.fluxplayer.app.feature.player.extensions.copy
import com.fluxplayer.app.feature.player.extensions.getManuallySelectedTrackIndex
import com.fluxplayer.app.feature.player.extensions.introMs
import com.fluxplayer.app.feature.player.extensions.outroMs
import com.fluxplayer.app.feature.player.extensions.playbackSpeed
import com.fluxplayer.app.feature.player.extensions.positionMs
import com.fluxplayer.app.feature.player.extensions.setIsScrubbingModeEnabled
import com.fluxplayer.app.feature.player.extensions.subtitleDelayMilliseconds
import com.fluxplayer.app.feature.player.extensions.subtitleSpeed
import com.fluxplayer.app.feature.player.extensions.subtitleTrackIndex
import com.fluxplayer.app.feature.player.extensions.switchTrack
import com.fluxplayer.app.feature.player.extensions.uriToSubtitleConfiguration
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleDelayMilliseconds
import io.github.anilbeesetti.nextlib.media3ext.renderer.subtitleSpeed
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
@AndroidEntryPoint
class PlayerService : MediaSessionService() {

    private val serviceScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    /** 持久化写入专用 scope，不受 serviceScope 取消影响 */
    private val saveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var loadControl: DefaultLoadControl

    /** 播放状态持久化助手 */
    private lateinit var persistence: PlaybackPersistence

    /** 媒体项元数据增强助手 */
    private lateinit var mediaItemEnricher: MediaItemEnricher

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

    /**
     * 标记：当前 setPlaybackSpeed() 调用是否为系统自动恢复倍速（初始化/切集/重入），
     * 而非用户主动改变倍速。用于防止 onPlaybackParametersChanged 在恢复阶段
     * 用默认倍速覆盖 DB 中已保存的目录级倍速。
     */
    private var isRestoringPlaybackSpeed = false

    /**
     * 标记：当前会话是否为纯音频（听书）会话。在 onSetMediaItems/onAddMediaItems
     * 里根据 mediaItems 判断。听书章节必须自动连播，不受视频"自动连播"偏好的影响。
     */
    @Volatile
    private var isAudioSession = false

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
            // 听书章节始终自动连播；视频会话按"自动连播"偏好（此处运行在主线程）
            // MediaSession 暴露的是 Player 接口，pauseAtEndOfMediaItems 是 ExoPlayer 专属属性
            (mediaSession?.player as? ExoPlayer)?.pauseAtEndOfMediaItems =
                if (isAudioSession) false else !playerPreferences.autoplay
            // 睡眠定时（按集数）：达到 起始集 + N 时暂停并清除定时
            val sleepState = AudioSleepTimer.state.value
            if (sleepState.remainingEpisodes > 0 && sleepState.startEpisodeIndex >= 0) {
                val index = mediaSession?.player?.currentMediaItemIndex ?: -1
                if (index >= 0 && index >= sleepState.startEpisodeIndex + sleepState.remainingEpisodes) {
                    AudioSleepTimer.cancel()
                    mediaSession?.player?.pause()
                }
            }
            loadArtworkForCurrentMediaItem()
            // 听书会话：同步刷新通知栏点击的 PendingIntent，携带听书上下文
            updateSessionActivityForAudiobook()
            val meta = mediaItem?.mediaMetadata
            Log.d(TAG, "onMediaItemTransition: mediaId=${mediaItem?.mediaId}, " +
                "positionMs=${meta?.positionMs}, playbackSpeed=${meta?.playbackSpeed}, " +
                "introMs=${meta?.introMs}, outroMs=${meta?.outroMs}, " +
                "sessionSpeed=$sessionPlaybackSpeed")
            mediaItem?.mediaMetadata?.let { metadata ->
                mediaSession?.player?.run {
                    // 从 DB 恢复该视频的上次倍速，覆盖全局默认值
                    metadata.playbackSpeed?.let { speed ->
                        sessionPlaybackSpeed = speed
                    }
                    playerSpecificSubtitleDelayMilliseconds = metadata.subtitleDelayMilliseconds ?: 0L
                    playerSpecificSubtitleSpeed = metadata.subtitleSpeed ?: 1f
                    isRestoringPlaybackSpeed = true
                    setPlaybackSpeed(sessionPlaybackSpeed)
                    isRestoringPlaybackSpeed = false
                }

                // 计算 seek 目标：片头跳过 vs 续播位置，取较大值
                val introMs = metadata.introMs?.takeIf { it > 0 }
                val resumePos = metadata.positionMs?.takeIf { playerPreferences.resume == Resume.YES }
                val seekTargetMs = when {
                    introMs != null && resumePos != null -> maxOf(introMs, resumePos)
                    introMs != null -> introMs
                    resumePos != null -> resumePos
                    else -> null
                }
                Log.d(TAG, "onMediaItemTransition seek: intro=$introMs resume=$resumePos " +
                    "resumeEnabled=${playerPreferences.resume} target=$seekTargetMs")
                seekTargetMs?.let { target ->
                    mediaSession?.player?.seekTo(target)
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
                    persistence.savePosition(oldMediaItem.mediaId, updatedPosition)
                    persistence.recordHistory(
                        mediaItem = oldMediaItem,
                        position = updatedPosition.takeIf { it != C.TIME_UNSET }
                            ?: oldPosition.positionMs,
                    )
                }

                DISCONTINUITY_REASON_REMOVE -> {
                    val durationMs = oldMediaItem.mediaMetadata.durationMs
                    val isAtEnd = durationMs != null && oldPosition.positionMs >= durationMs - 1000
                    persistence.savePosition(
                        oldMediaItem.mediaId,
                        if (isAtEnd) C.TIME_UNSET else oldPosition.positionMs,
                    )
                    persistence.recordHistory(oldMediaItem, oldPosition.positionMs)
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

            // 系统自动恢复倍速（初始化/切集/重入）时不写入 DB，
            // 防止在 STATE_IDLE 等过渡阶段用默认倍速覆盖已保存的目录级倍速。
            if (!isRestoringPlaybackSpeed) {
                val mediaId = currentMediaItem.mediaId
                // 用 saveScope 保证不被 serviceScope 取消（与拆分前行为一致）
                saveScope.launch {
                    val dirKey = mediaItemEnricher.computeDirKey(mediaId)
                    if (dirKey.isNotEmpty()) {
                        persistence.savePlaybackSpeed(dirKey, playbackSpeed)
                    }
                }
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
                // 不再在此处调用 setPlaybackSpeed。倍速恢复统一由
                // onMediaItemTransition 处理（从 DB 的目录级 key 读取已保存倍速），
                // 此处调用会导致在 STATE_IDLE 过渡阶段用默认倍速触发
                // onPlaybackParametersChanged，进而覆盖 DB 中已保存的目录级倍速。
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
                persistence.savePosition(
                    uri = player.currentMediaItem?.mediaId ?: return@run,
                    position = player.currentPosition,
                )
            }
            // 暂停时才记录播放历史（播放时不记录）
            if (isPlaying) return
            val player = mediaSession?.player ?: return
            // 播放器 IDLE 状态时由 STOP_PLAYER_SESSION 统一处理，避免重复覆盖
            if (player.playbackState == Player.STATE_IDLE) return
            val currentMediaItem = player.currentMediaItem ?: return
            persistence.recordHistory(currentMediaItem, player.currentPosition)
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
            
            // 获取当前播放的媒体项信息
            val currentMediaItem = mediaSession?.player?.currentMediaItem
            val currentUri = currentMediaItem?.localConfiguration?.uri
            Log.e(TAG, "onPlayerError: currentMediaItem=${currentMediaItem?.mediaId}")
            Log.e(TAG, "onPlayerError: currentMediaItem URI=$currentUri")
            
            // pan123 HLS fallback: MP4 直链失败时自动切换到 HLS
            if (currentMediaItem != null && currentUri != null && currentUri.toString().contains("#pan123Play=true#")) {
                val provider = CloudUriScheme.getProvider(currentMediaItem.mediaId.toUri())
                val fileId = CloudUriScheme.getFileId(currentMediaItem.mediaId.toUri())
                if (provider == "pan123" && fileId != null) {
                    val hlsUrl = Pan123FallbackCache.get(fileId)
                    if (hlsUrl != null) {
                        Log.w(TAG, "onPlayerError: PAN123 HLS fallback - switching to HLS URL")
                        val newUri = hlsUrl + "#pan123Play=true#"
                        val newItem = currentMediaItem.buildUpon().setUri(newUri).build()
                        mediaSession?.player?.replaceMediaItem(
                            mediaSession?.player?.currentMediaItemIndex ?: 0,
                            newItem
                        )
                        mediaSession?.player?.prepare()
                        mediaSession?.player?.play()
                        return
                    }
                }
            }
            
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
                    handleDecoderFallback()
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
            if (mediaItems.all { persistence.isAudioFile(it.mediaId) }) {
                isAudioSession = true
                // 听书倍速独立于视频播放设置，从 DataStore 读取上次保存的倍速
                sessionPlaybackSpeed = preferencesRepository.applicationPreferences.value.audiobookPlaybackSpeed
                val updatedItems = mediaItems.map { item ->
                    item.buildUpon()
                        .setUri(Uri.fromFile(File(item.mediaId)))
                        .build()
                }
                return@future MediaSession.MediaItemsWithStartPosition(updatedItems, startIndex, startPositionMs)
            }
            isAudioSession = false
            val updatedMediaItems = mediaItemEnricher.enrich(mediaItems)
            return@future MediaSession.MediaItemsWithStartPosition(updatedMediaItems, startIndex, startPositionMs)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = serviceScope.future(Dispatchers.Default) {
            // 纯音频模式：跳过 metadata 增强，但仍需从 mediaId 重建 URI
            if (mediaItems.all { persistence.isAudioFile(it.mediaId) }) {
                isAudioSession = true
                sessionPlaybackSpeed = preferencesRepository.applicationPreferences.value.audiobookPlaybackSpeed
                val updatedItems = mediaItems.map { item ->
                    item.buildUpon()
                        .setUri(Uri.fromFile(File(item.mediaId)))
                        .build()
                }
                return@future updatedItems.toMutableList()
            }
            isAudioSession = false
            val updatedMediaItems = mediaItemEnricher.enrich(mediaItems)
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
                    val stopMediaId = stopMediaItem?.mediaId
                    Log.d(TAG, "STOP_PLAYER_SESSION: mediaId=$stopMediaId, position=$stopPosition, hasMediaItem=${stopMediaItem != null}")
                    if (stopMediaId != null) {
                        persistence.savePosition(stopMediaId, stopPosition)
                        Log.d(TAG, "STOP_PLAYER_SESSION: position saved to DB for $stopMediaId")
                    }
                    if (stopMediaItem != null) {
                        persistence.recordHistory(stopMediaItem, stopPosition)
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

        persistence = PlaybackPersistence(applicationContext, mediaRepository, playbackHistoryRepository, saveScope)
        mediaItemEnricher = MediaItemEnricher(
            context = applicationContext,
            mediaRepository = mediaRepository,
            cloudUriResolver = cloudUriResolver,
            preferencesRepository = preferencesRepository,
            imageLoader = imageLoader,
            cacheKeyRegistry = cacheKeyRegistry,
        )

        trackSelector = DefaultTrackSelector(applicationContext).apply {
            setParameters(
                buildUponParameters()
                    .setPreferredAudioLanguage(playerPreferences.preferredAudioLanguage)
                    .setPreferredTextLanguage(playerPreferences.preferredSubtitleLanguage),
            )
        }

        loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                2500,
                5000,
            )
            .setBackBuffer(5000, false)
            .setTargetBufferBytes(64 * 1024 * 1024) // 64MB cap to avoid OOM
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        mediaSourceFactory = CloudAwareMediaSourceFactory(
            authAwareFactory = AuthAwareDataSourceFactory(applicationContext),
            cacheKeyRegistry = cacheKeyRegistry,
            playbackCacheManager = playbackCacheManager,
        ).also {
            if (playerPreferences.playbackCacheEnabled) {
                it.updateCacheSettings(playerPreferences.playbackCacheMaxSize.bytes)
            }
        }

        val player = createExoPlayer(playerPreferences.decoderPriority)
        configurePlayer(player)

        buildMediaSession(player)

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

        // 睡眠定时（按分钟）倒计时：仅在播放中计时，暂停不消耗；到时暂停播放。
        // 逻辑放在 Service 内，Activity 重建（旋转/息屏）不影响定时器
        serviceScope.launch {
            while (true) {
                delay(1000)
                if (AudioSleepTimer.state.value.remainingSeconds <= 0) continue
                val currentPlayer = mediaSession?.player ?: continue
                if (currentPlayer.isPlaying && AudioSleepTimer.tickSecond()) {
                    currentPlayer.pause()
                }
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // mediaSession 可能为 null（服务已销毁或重建中），强解会导致 NPE
        val player = mediaSession?.player ?: run {
            stopSelf()
            return
        }
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // 播放会话结束，睡眠定时随之失效
        AudioSleepTimer.cancel()

        // 退出前保存当前播放状态（使用 saveScope，不受 serviceScope 取消影响）
        val currentPlayer = mediaSession?.player
        val currentUri = currentPlayer?.currentMediaItem?.mediaId
        val currentPos = currentPlayer?.currentPosition ?: 0L
        val currentSpeed = sessionPlaybackSpeed
        Log.d(TAG, "onDestroy: mediaId=$currentUri, position=$currentPos, speed=$currentSpeed, " +
            "willSave=${currentUri != null && currentPos > 0}")
        if (currentUri != null) {
            if (currentPos > 0) {
                persistence.savePosition(currentUri, currentPos)
                Log.d(TAG, "onDestroy: position saved to DB for $currentUri")
            }
            if (currentSpeed != 1.0f) {
                saveScope.launch {
                    val dirKey = mediaItemEnricher.computeDirKey(currentUri)
                    if (dirKey.isNotEmpty()) {
                        persistence.savePlaybackSpeed(dirKey, currentSpeed)
                        Log.d(TAG, "onDestroy: speed saved to DB for dirKey=$dirKey")
                    }
                }
            }
        }

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

    /**
     * 创建 ExoPlayer 实例，接受 [decoderPriority] 以支持解码失败时软件解码降级重试。
     */
    private fun createExoPlayer(decoderPriority: DecoderPriority): ExoPlayer {
        val renderersFactory = NextRenderersFactory(applicationContext)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                when (decoderPriority) {
                    DecoderPriority.DEVICE_ONLY -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                    DecoderPriority.PREFER_DEVICE -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    DecoderPriority.PREFER_APP -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                },
            )

        return ExoPlayer.Builder(applicationContext)
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
    }

    /** 配置播放器通用参数（监听器、播放模式、倍速等），用于初始化或降级重建。 */
    private fun configurePlayer(player: ExoPlayer) {
        with(player) {
            addListener(playbackStateListener)
            pauseAtEndOfMediaItems = !playerPreferences.autoplay
            repeatMode = when (playerPreferences.loopMode) {
                LoopMode.OFF -> Player.REPEAT_MODE_OFF
                LoopMode.ONE -> Player.REPEAT_MODE_ONE
                LoopMode.ALL -> Player.REPEAT_MODE_ALL
            }
            sessionPlaybackSpeed = playerPreferences.defaultPlaybackSpeed
            isRestoringPlaybackSpeed = true
            setPlaybackSpeed(sessionPlaybackSpeed)
            isRestoringPlaybackSpeed = false
        }
    }

    /** 构建 MediaSession，用于初始化或降级重建。 */
    private fun buildMediaSession(player: ExoPlayer) {
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
                        // 上一章/下一章（听书章节或播放列表切换）；播放器不支持时自动隐藏
                        CommandButton.Builder(ICON_UNDEFINED)
                            .setCustomIconResId(coreUiR.drawable.ic_skip_prev)
                            .setDisplayName(getString(coreUiR.string.player_controls_previous))
                            .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                            .setEnabled(true)
                            .build(),
                        CommandButton.Builder(ICON_UNDEFINED)
                            .setCustomIconResId(coreUiR.drawable.ic_skip_next)
                            .setDisplayName(getString(coreUiR.string.player_controls_next))
                            .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                            .setEnabled(true)
                            .build(),
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
    }

    /**
     * 听书（纯音频）会话时，把通知栏点击的 PendingIntent 更新为携带听书上下文，
     * 保证 Activity 已被返回销毁后，从通知栏点回仍以听书 UI 打开
     * （audio_only + 当前章节 URI + 封面）。视频会话保持默认（无 extra），
     * 由 PlayerActivity 按会话状态自行恢复。
     */
    private fun updateSessionActivityForAudiobook() {
        if (!isAudioSession) return
        val player = mediaSession?.player ?: return
        val currentItem = player.currentMediaItem ?: return
        val intent = Intent(this, PlayerActivity::class.java).apply {
            data = currentItem.localConfiguration?.uri
            putExtra("audio_only", true)
            currentItem.mediaMetadata.artworkUri?.toString()?.let { putExtra("cover_uri", it) }
        }
        mediaSession?.setSessionActivity(
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
    }

    /**
     * 解码器初始化失败时的降级处理。
     * 如果当前不是软件解码优先，则强制切换到 FFmpeg 软件解码重建播放器重试。
     * 常见触发场景：Dolby Vision / HDR 内容在低端设备/平板上硬件解码失败。
     *
     * 注意：此方法从 Player.Listener 回调中触发，重建逻辑投递到主线程执行以避免
     * 在监听器回调内部 release 播放器。
     */
    private fun handleDecoderFallback() {
        val currentPlayer = mediaSession?.player ?: return

        if (playerPreferences.decoderPriority == DecoderPriority.PREFER_APP) {
            Log.e(TAG, "handleDecoderFallback: already using software decoder, giving up")
            return
        }

        Log.w(TAG, "handleDecoderFallback: retrying with FFmpeg software decoder")

        // 保存当前播放状态（必须在 release 前捕获）
        val savedItems = (0 until currentPlayer.mediaItemCount).map { currentPlayer.getMediaItemAt(it) }
        val savedIndex = currentPlayer.currentMediaItemIndex
        val savedPosition = currentPlayer.currentPosition
        val wasPlaying = currentPlayer.playWhenReady

        // 投递到主线程执行 player/MediaSession 的重建，避免在监听器线程中 release
        serviceScope.launch {
            // 持久化切换为软件解码优先
            preferencesRepository.updatePlayerPreferences {
                it.copy(decoderPriority = DecoderPriority.PREFER_APP)
            }

            // 清理旧播放器和 MediaSession
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            currentPlayer.removeListener(playbackStateListener)
            currentPlayer.stop()
            currentPlayer.release()
            mediaSession?.release()
            mediaSession = null

            // 用软件解码优先重建播放器
            val newPlayer = createExoPlayer(DecoderPriority.PREFER_APP)
            configurePlayer(newPlayer)
            buildMediaSession(newPlayer)

            // 恢复播放
            if (savedItems.isNotEmpty()) {
                newPlayer.setMediaItems(savedItems, savedIndex, savedPosition)
                newPlayer.playWhenReady = wasPlaying
                newPlayer.prepare()
            }

            Log.w(TAG, "handleDecoderFallback: player rebuilt with PREFER_APP, " +
                "items=${savedItems.size}, index=$savedIndex, position=$savedPosition")
        }
    }

    private fun loadArtworkForCurrentMediaItem() {
        artworkLoadJob?.cancel()
        artworkLoadJob = serviceScope.launch(Dispatchers.Main) {
            val player = mediaSession?.player ?: return@launch
            val currentMediaItem = player.currentMediaItem ?: return@launch
            if (currentMediaItem.mediaMetadata.artworkData != null) return@launch

            val artworkUri = mediaItemEnricher.loadArtworkForMediaItem(currentMediaItem) ?: return@launch

            val updatedPlayer = mediaSession?.player ?: return@launch
            val updatedMediaItem = updatedPlayer.currentMediaItem ?: return@launch
            if (updatedMediaItem.mediaId != currentMediaItem.mediaId) return@launch

            updatedPlayer.replaceMediaItem(
                updatedPlayer.currentMediaItemIndex,
                updatedMediaItem.buildUpon()
                    .setMediaMetadata(
                        updatedMediaItem.mediaMetadata.buildUpon()
                            .setArtworkUri(artworkUri)
                            .build(),
                    )
                    .build(),
            )
        }
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
