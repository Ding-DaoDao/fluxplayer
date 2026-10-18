package com.fluxplayer.app.feature.player

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.util.Consumer
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.fluxplayer.app.core.common.extensions.getMediaContentUri
import com.fluxplayer.app.core.data.extractor.ThumbnailExtractor
import com.fluxplayer.app.core.data.repository.AudiobookProgressRepository
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.model.AccentPreset
import com.fluxplayer.app.core.model.ThemeConfig
import com.fluxplayer.app.core.ui.theme.NextPlayerTheme
import com.fluxplayer.app.feature.player.extensions.registerForSuspendActivityResult
import com.fluxplayer.app.feature.player.extensions.setExtras
import com.fluxplayer.app.feature.player.extensions.uriToSubtitleConfiguration
import com.fluxplayer.app.feature.player.service.PlayerFrameCapture
import com.fluxplayer.app.feature.player.service.PlayerService
import com.fluxplayer.app.feature.player.service.addSubtitleTrack
import com.fluxplayer.app.feature.player.service.stopPlayerSession
import com.fluxplayer.app.feature.player.utils.PlayerApi
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

val LocalUseMaterialYouControls = compositionLocalOf { false }

@SuppressLint("UnsafeOptInUsageError")
@AndroidEntryPoint
class PlayerActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()
    val playerPreferences get() = viewModel.uiState.value.playerPreferences

    @javax.inject.Inject
    lateinit var thumbnailExtractor: ThumbnailExtractor

    @javax.inject.Inject
    lateinit var preferencesRepository: PreferencesRepository

    @Inject
    lateinit var audiobookProgressRepository: AudiobookProgressRepository

    private val onWindowAttributesChangedListener = CopyOnWriteArrayList<Consumer<WindowManager.LayoutParams?>>()

    private var isPlaybackFinished = false
    private var playInBackground: Boolean = false
    private var isIntentNew: Boolean = true
    private var isFinishingPlayer = false
    private var playerSurfaceView: SurfaceView? = null

    /** 听书模式：当前播放列表中所有音频文件的绝对路径列表，用于通过 mediaId 反查章节索引 */
    private var audioBookChapterPaths: List<String> = emptyList()

    /** 续播位置：在 STATE_READY 时消费，替代硬编码 delay */
    private var pendingResumePosition: Long = 0L

    /**
     * Player
     */
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private lateinit var playerApi: PlayerApi

    /**
     * Listeners
     */
    private val playbackStateListener: Player.Listener = playbackStateListener()

    private val subtitleFileSuspendLauncher = registerForSuspendActivityResult(OpenDocument())

    private val danmakuFileSuspendLauncher = registerForSuspendActivityResult(OpenDocument())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 息屏/旋转后恢复正在播放的集数 URI
        // onMediaItemTransition 会把当前集 URI 写入 intent.data（内存修改），
        // 但 Activity 重建后 intent 是原始启动 Intent，需要通过 savedState 恢复
        if (savedInstanceState != null) {
            val savedUri = savedInstanceState.getString("current_media_uri")
            if (savedUri != null) {
                intent.data = Uri.parse(savedUri)
            }
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // 淡入过渡：加载画面渐显，比默认滑动动画更自然
        overridePendingTransition(android.R.anim.fade_in, 0)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val appPrefs by preferencesRepository.applicationPreferences
                .collectAsStateWithLifecycle(initialValue = null)
            var player by remember { mutableStateOf<MediaController?>(null) }

            // 跟随用户主题偏好（亮/暗/跟随系统），听书页不再强制暗色
            val shouldUseDarkTheme: Boolean = when (appPrefs?.themeConfig) {
                ThemeConfig.SYSTEM -> isSystemInDarkTheme()
                ThemeConfig.OFF -> false
                ThemeConfig.ON -> true
                null -> isSystemInDarkTheme()
            }

            LifecycleStartEffect(Unit) {
                maybeInitControllerFuture()
                val job = lifecycleScope.launch {
                    player = awaitController()
                }
                // 取消未完成的协程，避免多次 start/stop 累积多个 await 协程；
                // 不设 player = null，保留引用避免 Crossfade 销毁 MediaPlayerScreen
                // 导致 remember 状态（IntroOutroState/弹幕渲染等）丢失
                onStopOrDispose { job.cancel() }
            }

            val danmakuList by viewModel.danmakuList.collectAsStateWithLifecycle()
            val danmakuFileUri by viewModel.danmakuFileUri.collectAsStateWithLifecycle()
            val danmakuEnabled by viewModel.danmakuEnabled.collectAsStateWithLifecycle()
            val danmakuForCurrentEpisode by viewModel.danmakuForCurrentEpisode.collectAsStateWithLifecycle()

            CompositionLocalProvider(LocalUseMaterialYouControls provides (uiState.playerPreferences?.useMaterialYouControls == true)) {
                val isAudioOnly = intent.getBooleanExtra("audio_only", false)
                val coverArtworkUri = intent.getStringExtra("cover_uri")?.let { Uri.parse(it) }
                if (isAudioOnly) {
                    val chapterPath = intent.data?.path ?: ""
                    val bookPath = com.fluxplayer.app.core.common.LocalListeningLibrary.find(this@PlayerActivity, intent.data.toString())?.path ?: chapterPath.substringBeforeLast('/')
                    val prefs by viewModel.audioSkipSettings(bookPath).collectAsStateWithLifecycle(initialValue = 0 to 0)
                    val chapterProgress by remember(bookPath) { audiobookProgressRepository.chapterProgress(bookPath) }
                        .distinctUntilChanged()
                        .collectAsStateWithLifecycle(initialValue = emptyMap())

                    // 拦截系统返回键：听书返回后转入后台无感播放（不停止），进度在退出时保存
                    BackHandler {
                        exitPlayerKeepPlaying()
                    }

                    NextPlayerTheme(
                        darkTheme = shouldUseDarkTheme,
                        accentPreset = appPrefs?.accentPreset ?: AccentPreset.Default,
                    ) {
                        val mp = player
                        // 从路径提取书名，供 loading 界面使用
                        val bookTitle = com.fluxplayer.app.core.common.LocalListeningLibrary.find(this@PlayerActivity, bookPath)?.title ?: bookPath.substringAfterLast('/').takeIf { it.isNotEmpty() }
                        Crossfade(targetState = mp, label = "playerTransition") { currentPlayer ->
                            if (currentPlayer == null) {
                                // player 未就绪时显示 loading 界面，避免黑屏
                                AudioLoadingScreen(
                                    coverArtworkUri = coverArtworkUri,
                                    title = bookTitle,
                                )
                            } else {
                                AudioPlaybackScreen(
                                    player = currentPlayer,
                                    coverArtworkUri = coverArtworkUri,
                                    bookPath = bookPath,
                                    bookName = bookTitle,
                                    chapterNames = viewModel.audioChapterNames,
                                    chapterPaths = audioBookChapterPaths,
                                    chapterProgress = chapterProgress,
                                    introSkipSeconds = prefs.first,
                                    outroSkipSeconds = prefs.second,
                                    onSkipSettingsChanged = { intro, outro ->
                                        lifecycleScope.launch {
                                            preferencesRepository.updateApplicationPreferences { p ->
                                                val newMap = p.audiobookSkipSettings.toMutableMap()
                                                newMap[bookPath] = "$intro,$outro"
                                                p.copy(audiobookSkipSettings = newMap)
                                            }
                                        }
                                    },
                                    onSaveResume = { providedIndex, pos, dur ->
                                        val playedAt = LocalAudiobookPlayback.state.value.takeIf { it.bookPath == bookPath }?.lastPlayedAt ?: 0L
                                        lifecycleScope.launch {
                                            val bkPath = bookPath
                                            // 保存回调提供的章节标识，避免切章后把上一章进度写入下一章。
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                                // 页面保存上一章的完成度，不覆盖服务保存的当前续播位置。
                                                audiobookProgressRepository.save(bkPath, providedIndex, pos, dur, lastPlayedAt = playedAt, chapterUri = audioBookChapterPaths.getOrNull(providedIndex)?.let { localListeningUri(it).toString() }, updateResume = false)
                                            }
                                        }
                                    },
                                    onBackClick = { exitPlayerKeepPlaying() },
                                    onSpeedChanged = { speed ->
                                        lifecycleScope.launch {
                                            preferencesRepository.updateApplicationPreferences { p ->
                                                p.copy(audiobookPlaybackSpeed = speed)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                } else {
                    // 视频页保持深色观影体系（黑底），但跟随用户的主题色与引擎设置，
                    // 使弹幕/设置等面板的强调色与主界面一致
                    NextPlayerTheme(
                        darkTheme = true,
                        accentPreset = appPrefs?.accentPreset ?: AccentPreset.Default,
                    ) {
                        MediaPlayerScreen(
                            player = player,
                            viewModel = viewModel,
                            playerPreferences = uiState.playerPreferences ?: return@NextPlayerTheme,
                            danmakuList = danmakuList,
                            danmakuFileUri = danmakuFileUri,
                            danmakuEnabled = danmakuEnabled,
                            danmakuForCurrentEpisode = danmakuForCurrentEpisode,
                            onSurfaceView = { sv -> playerSurfaceView = sv },
                            onSelectSubtitleClick = {
                                lifecycleScope.launch {
                                    val uri = subtitleFileSuspendLauncher.launch(
                                        arrayOf(
                                            MimeTypes.APPLICATION_SUBRIP,
                                            MimeTypes.APPLICATION_TTML,
                                            MimeTypes.TEXT_VTT,
                                            MimeTypes.TEXT_SSA,
                                            MimeTypes.BASE_TYPE_APPLICATION + "/octet-stream",
                                            MimeTypes.BASE_TYPE_TEXT + "/*",
                                        ),
                                    ) ?: return@launch
                                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    maybeInitControllerFuture()
                                    controllerFuture?.await()?.addSubtitleTrack(uri)
                                }
                            },
                            onSubtitleFileSelected = { uri ->
                                lifecycleScope.launch {
                                    maybeInitControllerFuture()
                                    controllerFuture?.await()?.addSubtitleTrack(uri)
                                }
                            },
                            onDanmakuPickFile = {
                                lifecycleScope.launch {
                                    val uri = danmakuFileSuspendLauncher.launch(
                                        arrayOf("text/xml", "application/json", "*/*"),
                                    ) ?: return@launch
                                    try {
                                        contentResolver.takePersistableUriPermission(
                                            uri,
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                        )
                                    } catch (e: SecurityException) {
                                        Log.w("PlayerActivity", "URI does not support persistable permission", e)
                                    }
                                    viewModel.loadDanmaku(this@PlayerActivity, uri)
                                }
                            },
                            onDanmakuLocalFileSelected = { uri ->
                                lifecycleScope.launch {
                                    viewModel.loadDanmaku(this@PlayerActivity, uri)
                                }
                            },
                            onBackClick = { finishAndStopPlayerSession() },
                        )
                    }
                }
            }
        }

        playerApi = PlayerApi(this)
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            // maybeInitControllerFuture 在首次启动时创建 future，恢复时 no-op
            maybeInitControllerFuture()
            // await 在首次启动时等待连接，恢复时 future 已完成立即返回
            mediaController = awaitController()

            mediaController?.run {
                updateKeepScreenOnFlag()
                addListener(playbackStateListener)
                startPlayback()
            }
        }
    }

    override fun onStop() {
        mediaController?.run {
            viewModel.playWhenReady = playWhenReady
            removeListener(playbackStateListener)
        }
        // 听书（audio_only）始终允许后台/息屏播放（类似音乐 App），不受"后台播放"设置限制
        val isAudioOnly = intent.getBooleanExtra("audio_only", false)
        val shouldPlayInBackground = playInBackground || playerPreferences?.autoBackgroundPlay == true || isAudioOnly
        if (subtitleFileSuspendLauncher.isAwaitingResult || !shouldPlayInBackground) {
            mediaController?.pause()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            finish()
            if (!shouldPlayInBackground) {
                mediaController?.stopPlayerSession()
            }
        }

        // 不在此处调用 viewModel.onPlayerExit() —— 息屏/切后台只是临时暂停，
        // 恢复后弹幕上下文和切集自动加载仍应有效。
        // onPlayerExit() 移到 finishAndStopPlayerSession() 中，只在真正退出时调用。

        // 不在此处释放 controllerFuture —— 保持连接可避免 onStart() 重建 controller
        // 导致 Crossfade 销毁 MediaPlayerScreen 和 Compose remember 状态丢失。

        super.onStop()
    }

    private suspend fun awaitController(): MediaController? {
        return try {
            controllerFuture?.await()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e("PlayerActivity", "播放器连接失败", error)
            controllerFuture?.let(MediaController::releaseFuture)
            controllerFuture = null
            if (!isFinishing) {
                android.widget.Toast.makeText(this, "播放器连接失败，请重新打开章节", android.widget.Toast.LENGTH_LONG).show()
                finish()
            }
            null
        }
    }

    private fun maybeInitControllerFuture() {
        if (controllerFuture == null) {
            val sessionToken = SessionToken(applicationContext, ComponentName(applicationContext, PlayerService::class.java))
            controllerFuture = MediaController.Builder(applicationContext, sessionToken).buildAsync()
        }
    }

    private fun startPlayback() {
        val activeItem = mediaController?.currentMediaItem
        if (intent.getBooleanExtra("reopen_audiobook", false) && localListeningBookPath(activeItem) != null) {
            // 通知携带的章节可能已过时，返回页面时始终使用服务当前章节，不重载或跳转进度。
            mediaController?.let { controller ->
                intent.data = localListeningUri(activeItem!!.mediaId)
                intent.putExtra("audio_only", true)
                val items = List(controller.mediaItemCount) { controller.getMediaItemAt(it) }
                audioBookChapterPaths = items.map { it.mediaId }
                viewModel.audioChapterNames = items.map { it.mediaMetadata.title?.toString().orEmpty() }
                viewModel.audioChapterCount = items.size
            }
            pendingResumePosition = 0L
            isIntentNew = false
            return
        }
        val uri = intent.data ?: return

        val returningFromBackground = !isIntentNew && mediaController?.currentMediaItem != null
        val isNewUriTheCurrentMediaItem = mediaController?.currentMediaItem?.localConfiguration?.uri.toString() == uri.toString()

        if (returningFromBackground || isNewUriTheCurrentMediaItem) {
            // 从后台恢复：媒体源已准备完毕，直接恢复播放即可。
            // 不调用 prepare() —— 云盘下载直链有时效性，re-prepare 可能因 URL 过期而失败。
            mediaController?.playWhenReady = viewModel.playWhenReady
            return
        }

        isIntentNew = false

        lifecycleScope.launch {
            playVideo(uri)
        }
    }

    private suspend fun playVideo(uri: Uri) {
        val isAudioOnly = intent.getBooleanExtra("audio_only", false)

        // 纯音频模式：扫描目录获取章节列表
        // 不通过 Intent 传 chapter_uris — Binder 事务有大小限制，
        // 大章节列表（~500KB+）会触发 TransactionTooLargeException
        if (isAudioOnly) {
            val coverArtworkUri = intent.getStringExtra("cover_uri")?.let(Uri::parse)
            val mediaItems = withContext(Dispatchers.IO) { localListeningItems(this@PlayerActivity, uri, coverArtworkUri) }
            if (mediaItems.isEmpty()) return
            val startIndex = mediaItems.indexOfFirst { localListeningUri(it.mediaId) == uri }.coerceAtLeast(0)
            val chapterNames = mediaItems.map { it.mediaMetadata.title?.toString().orEmpty() }
            audioBookChapterPaths = mediaItems.map { it.mediaId }

            mediaController?.run {
                setMediaItems(mediaItems, startIndex, C.TIME_UNSET)
                playWhenReady = viewModel.playWhenReady
                prepare()
            }

            // 续播：player 就绪后在 onPlaybackStateChanged(STATE_READY) 中 seek，不再用 delay
            val resumePos = intent.getLongExtra("start_position_ms", 0L)
            if (resumePos > 0) {
                pendingResumePosition = resumePos
            }
            // 存储到 viewModel 供 composer 读取
            viewModel.audioChapterNames = chapterNames
            viewModel.audioChapterCount = mediaItems.size
            return
        }

        // 视频模式：withContext(Default) 处理 MediaStore 查询 + 目录扫描
        withContext(Dispatchers.Default) {
            val mediaContentUri = getMediaContentUri(uri)
            val playlist = playerApi.getPlaylist().takeIf { it.isNotEmpty() }
                ?: mediaContentUri?.let { mediaUri ->
                    viewModel.getPlaylistFromUri(mediaUri)
                        .map { it.uriString }
                        .toMutableList()
                        .apply {
                            if (!contains(mediaUri.toString())) {
                                add(index = 0, element = mediaUri.toString())
                            }
                        }
                } ?: listOf(uri.toString())

            // 计算播放列表父目录，用于片头片尾持久化（同目录所有剧集共享）
            viewModel.resolveParentDirFromPlaylist(playlist)

            val mediaItemIndexToPlay = playlist.indexOfFirst {
                it == (mediaContentUri ?: uri).toString()
            }.takeIf { it >= 0 } ?: 0
            val defaultTitle = playerApi.title
            val mediaItems = playlist.mapIndexed { index, uriString ->
                MediaItem.Builder().apply {
                    val itemUri = Uri.parse(uriString)
                    setUri(itemUri)
                    setMediaId(uriString)
                    val isCurrentItem = index == mediaItemIndexToPlay
                    setMediaMetadata(
                        MediaMetadata.Builder().apply {
                            setTitle(
                                when {
                                    isCurrentItem && !playerApi.title.isNullOrEmpty() -> playerApi.title
                                    isCurrentItem -> playerApi.title
                                    else -> defaultTitle
                                },
                            )
                            if (isCurrentItem) {
                                setExtras(positionMs = playerApi.position?.toLong())
                            }
                        }.build(),
                    )
                    if (isCurrentItem) {
                        val apiSubs = playerApi.getSubs().map { subtitle ->
                            uriToSubtitleConfiguration(
                                uri = subtitle.uri,
                                subtitleEncoding = playerPreferences?.subtitleTextEncoding ?: "",
                                isSelected = subtitle.isSelected,
                            )
                        }
                        setSubtitleConfigurations(apiSubs)
                    }
                }.build()
            }

            withContext(Dispatchers.Main) {
                mediaController?.run {
                    setMediaItems(mediaItems, mediaItemIndexToPlay, playerApi.position?.toLong() ?: C.TIME_UNSET)
                    playWhenReady = viewModel.playWhenReady
                    prepare()
                }
            }
        } // end withContext(Dispatchers.Default)
    } // end playVideo

    private fun playbackStateListener() = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            super.onMediaItemTransition(mediaItem, reason)
            intent.data = mediaItem?.localConfiguration?.uri
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
            updateKeepScreenOnFlag()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)
            // 续播：player 就绪后立即 seek 到断点位置（替代旧版 delay(500) hack）
            if (playbackState == Player.STATE_READY && pendingResumePosition > 0) {
                mediaController?.seekTo(pendingResumePosition)
                pendingResumePosition = 0L
            }
            when (playbackState) {
                Player.STATE_ENDED -> {
                    isPlaybackFinished = mediaController?.playbackState == Player.STATE_ENDED
                    finishAndStopPlayerSession()
                }

                else -> {}
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            super.onPlayWhenReadyChanged(playWhenReady, reason)

            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) {
                if (mediaController?.repeatMode != Player.REPEAT_MODE_OFF) return
                isPlaybackFinished = true
                finishAndStopPlayerSession()
            }
        }
    }

    override fun finish() {
        if (playerApi.shouldReturnResult) {
            val result = playerApi.getResult(
                isPlaybackFinished = isPlaybackFinished,
                duration = mediaController?.duration ?: C.TIME_UNSET,
                position = mediaController?.currentPosition ?: C.TIME_UNSET,
            )
            setResult(RESULT_OK, result)
        }
        super.finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.data != null) {
            setIntent(intent)
            /**
             * 重建 PlayerApi，读取新 Intent 的 extras（playlist 等）。
             * 必须做，因为 PlayerApi 在构造时将 activity.intent.extras 缓存为不可变 val，
             * setIntent(intent) 不会自动更新 PlayerApi 内部的 extras 引用。
             * 不重建会导致 singleTask 复用 Activity 时，playerApi.getPlaylist()
             * 返回上一个 directory 的播放列表，造成片头片尾 key 泄露。
             */
            playerApi = PlayerApi(this)
            isIntentNew = true
            if (mediaController != null) {
                startPlayback()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 保存当前正在播放的媒体 URI，以便息屏/旋转重建后恢复
        // intent.data 由 onMediaItemTransition 回调实时更新为当前集的 URI
        intent.data?.toString()?.let { outState.putString("current_media_uri", it) }
    }

    override fun onDestroy() {
        mediaController?.removeListener(playbackStateListener)
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        mediaController = null
        super.onDestroy()
    }

    private fun updateKeepScreenOnFlag() {
        if (mediaController?.isPlaying == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * 听书续播：请求后台服务保存当前章节和位置，并等待保存完成。
     */
    private suspend fun saveAudiobookResumeState() {
        // 由服务读取真实位置并等待落盘，避免页面缓存覆盖定时暂停后的记录。
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            mediaController?.sendCustomCommand(
                com.fluxplayer.app.feature.player.service.CustomCommands.SAVE_AUDIOBOOK_PROGRESS.sessionCommand,
                Bundle.EMPTY,
            )?.await()
        }
    }

    /**
     * 听书专用：返回后转入后台无感播放。
     * 只保存进度并关闭页面，不 stop、不清空播放列表——
     * 播放继续运行在 PlayerService，由系统通知栏媒体控制接管。
     */
    private fun exitPlayerKeepPlaying() {
        if (isFinishingPlayer) return
        isFinishingPlayer = true
        lifecycleScope.launch {
            if (intent.getBooleanExtra("audio_only", false)) {
                saveAudiobookResumeState()
            }
            viewModel.onPlayerExit()
            controllerFuture?.run {
                MediaController.releaseFuture(this)
                controllerFuture = null
            }
            finish()
        }
    }

    private fun finishAndStopPlayerSession() {
        if (isFinishingPlayer) return
        isFinishingPlayer = true
        lifecycleScope.launch {
            // 听书续播：保存进度（播放列表随后会被清空，必须在此先保存）
            if (intent.getBooleanExtra("audio_only", false)) {
                saveAudiobookResumeState()
            }
            // 退出时通过 PixelCopy 截取 SurfaceView 当前帧作为缩略图
            val sv = playerSurfaceView
            val uri = mediaController?.currentMediaItem?.mediaId
            if (uri == null) {
                Log.w("PlayerActivity", "thumbnail capture SKIPPED: mediaId is null")
            } else if (preferencesRepository.persistedApplicationPreferences.first().videoIncognito) {
                Log.d("PlayerActivity", "视频无痕模式：跳过历史缩略图保存")
            } else if (sv == null) {
                Log.w("PlayerActivity", "thumbnail capture SKIPPED: playerSurfaceView is null (uri=$uri)")
            } else if (sv.width <= 0 || sv.height <= 0) {
                Log.w("PlayerActivity", "thumbnail capture SKIPPED: SurfaceView size invalid ${sv.width}x${sv.height}")
            } else {
                try {
                    // 直接分配缩略图尺寸的位图（PixelCopy 会缩放到目标尺寸），
                    // 避免按 SurfaceView 原始分辨率分配大位图（4K 约 33MB）
                    val thumbW = 320
                    val thumbH = (sv.height.toFloat() / sv.width * thumbW).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
                    val copyResult = suspendCancellableCoroutine { cont ->
                        PixelCopy.request(
                            sv,
                            bitmap,
                            { result -> cont.resume(result) {} },
                            Handler(Looper.getMainLooper()),
                        )
                    }
                    if (copyResult == PixelCopy.SUCCESS) {
                        val path = withContext(Dispatchers.Default) {
                            thumbnailExtractor.saveDirect(uri, bitmap)
                        }
                        if (path != null) {
                            PlayerFrameCapture.put(uri, path)
                            Log.d("PlayerActivity", "thumbnail captured & cached: uri=$uri path=$path")
                        } else {
                            Log.w("PlayerActivity", "thumbnail capture FAILED: saveDirect returned null (uri=$uri)")
                        }
                    } else {
                        Log.w("PlayerActivity", "thumbnail capture FAILED: PixelCopy result=$copyResult (uri=$uri)")
                        bitmap.recycle()
                    }
                } catch (e: Exception) {
                    // 截图失败不影响退出
                    Log.w("PlayerActivity", "thumbnail capture EXCEPTION: ${e.javaClass.simpleName}: ${e.message} (uri=$uri)")
                }
            }
            // 音频模式：只停播放+清空列表，保留 Service 热连接（下次播放秒开）
            // 视频模式：走完整清理流程（含 DB 位置记录）
            // 先通知 ViewModel 退出，清理弹幕上下文和设置 isExiting
            viewModel.onPlayerExit()
            if (intent.getBooleanExtra("audio_only", false)) {
                mediaController?.stop()
                mediaController?.clearMediaItems()
            } else {
                mediaController?.stopPlayerSession()
            }
            // 真正退出时断开 controller 连接
            controllerFuture?.run {
                MediaController.releaseFuture(this)
                controllerFuture = null
            }
            finish()
        }
    }

    override fun onWindowAttributesChanged(params: WindowManager.LayoutParams?) {
        super.onWindowAttributesChanged(params)
        for (listener in onWindowAttributesChangedListener) {
            listener.accept(params)
        }
    }

    fun addOnWindowAttributesChangedListener(listener: Consumer<WindowManager.LayoutParams?>) {
        onWindowAttributesChangedListener.add(listener)
    }

    fun removeOnWindowAttributesChangedListener(listener: Consumer<WindowManager.LayoutParams?>) {
        onWindowAttributesChangedListener.remove(listener)
    }

    /**
     * 通过当前播放的 mediaId 在 chapterPaths 中反查真实章节索引。
     * 兜底使用 providedIndex（来自 currentMediaItemIndex，切集过渡期可能为 C.INDEX_UNSET=-1）。
     */
    private fun resolveChapterIndex(providedIndex: Int): Int {
        val currentMediaId = mediaController?.currentMediaItem?.mediaId
        if (currentMediaId == null) {
            Log.d("PlayerActivity", "resolveChapterIndex: mediaId is null, fallback=$providedIndex")
            return providedIndex.coerceAtLeast(0)
        }
        val pathIndex = audioBookChapterPaths.indexOf(currentMediaId)
        Log.d("PlayerActivity", "resolveChapterIndex: mediaId=$currentMediaId, pathIndex=$pathIndex, paths=${audioBookChapterPaths.take(3)}, fallback=$providedIndex")
        if (pathIndex >= 0) return pathIndex
        return providedIndex.coerceAtLeast(0)
    }
}
