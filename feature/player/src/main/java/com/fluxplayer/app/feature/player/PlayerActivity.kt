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
import androidx.compose.runtime.Composable
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
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import com.fluxplayer.app.core.common.extensions.getMediaContentUri
import com.fluxplayer.app.core.ui.theme.NextPlayerTheme
import com.fluxplayer.app.feature.player.extensions.registerForSuspendActivityResult
import com.fluxplayer.app.feature.player.extensions.setExtras
import com.fluxplayer.app.feature.player.extensions.uriToSubtitleConfiguration
import com.fluxplayer.app.feature.player.service.PlayerService
import com.fluxplayer.app.feature.player.service.addSubtitleTrack
import com.fluxplayer.app.feature.player.service.PlayerFrameCapture
import com.fluxplayer.app.feature.player.service.stopPlayerSession
import com.fluxplayer.app.feature.player.utils.PlayerApi
import com.fluxplayer.app.core.data.extractor.ThumbnailExtractor
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.model.ComposeEngine
import com.fluxplayer.app.core.model.ThemeConfig
import com.fluxplayer.app.core.model.VideoSource
import android.util.Log
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
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
                lifecycleScope.launch {
                    player = controllerFuture?.await()
                }

                onStopOrDispose {
                    player = null
                }
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
                    val bookPath = chapterPath.substringBeforeLast('/')
                    val prefs by viewModel.audioSkipSettings(bookPath).collectAsStateWithLifecycle(initialValue = 0 to 0)
                    val chapterProgress by preferencesRepository.applicationPreferences
                        .map { p ->
                            p.audiobookChapterProgress
                                .filterKeys { it.startsWith("$bookPath|") }
                                .mapKeys { (k, _) -> k.removePrefix("$bookPath|").toIntOrNull() ?: -1 }
                                .filterKeys { it >= 0 }
                                .mapValues { (_, v) ->
                                    val parts = v.split("|")
                                    (parts.getOrNull(0)?.toLongOrNull() ?: 0L) to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
                                }
                        }
                        .collectAsStateWithLifecycle(initialValue = emptyMap())

                    // 拦截系统返回键，确保播放进度被保存
                    BackHandler {
                        finishAndStopPlayerSession()
                    }

                    NextPlayerTheme(
                        darkTheme = shouldUseDarkTheme,
                        dynamicColor = appPrefs?.useDynamicColors ?: true,
                        composeEngine = appPrefs?.composeEngine ?: ComposeEngine.MATERIAL,
                    ) {
                        val mp = player
                        // 从路径提取书名，供 loading 界面使用
                        val bookTitle = bookPath.substringAfterLast('/').takeIf { it.isNotEmpty() }
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
                                    lifecycleScope.launch {
                                        val bkPath = bookPath
                                        // 使用路径反查章节索引，currentMediaItemIndex 在切集过渡期可能为 -1
                                        val chapterIdx = resolveChapterIndex(providedIndex)
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                            preferencesRepository.updateApplicationPreferences { p ->
                                                var map = p.audiobookResumeState.toMutableMap()
                                                map[bkPath] = "$chapterIdx|$pos"
                                                var pg = p.audiobookChapterProgress.toMutableMap()
                                                pg["$bkPath|$chapterIdx"] = "$pos|$dur"
                                                p.copy(audiobookResumeState = map, audiobookChapterProgress = pg)
                                            }
                                        }
                                    }
                                },
                                onBackClick = { finishAndStopPlayerSession() },
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
                    NextPlayerTheme(darkTheme = true) {
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
                            onDanmakuPickFile = {
                                lifecycleScope.launch {
                                    val uri = danmakuFileSuspendLauncher.launch(
                                        arrayOf("text/xml", "application/json", "*/*"),
                                    ) ?: return@launch
                                    try {
                                        contentResolver.takePersistableUriPermission(
                                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
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
            maybeInitControllerFuture()
            mediaController = controllerFuture?.await()

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
        val shouldPlayInBackground = playInBackground || playerPreferences?.autoBackgroundPlay == true
        if (subtitleFileSuspendLauncher.isAwaitingResult || !shouldPlayInBackground) {
            mediaController?.pause()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            finish()
            if (!shouldPlayInBackground) {
                mediaController?.stopPlayerSession()
            }
        }

        viewModel.onPlayerExit()

        controllerFuture?.run {
            MediaController.releaseFuture(this)
            controllerFuture = null
        }
        super.onStop()
    }

    private fun maybeInitControllerFuture() {
        if (controllerFuture == null) {
            val sessionToken = SessionToken(applicationContext, ComponentName(applicationContext, PlayerService::class.java))
            controllerFuture = MediaController.Builder(applicationContext, sessionToken).buildAsync()
        }
    }

    private fun startPlayback() {
        val uri = intent.data ?: return

        val returningFromBackground = !isIntentNew && mediaController?.currentMediaItem != null
        val isNewUriTheCurrentMediaItem = mediaController?.currentMediaItem?.localConfiguration?.uri.toString() == uri.toString()

        if (returningFromBackground || isNewUriTheCurrentMediaItem) {
            mediaController?.prepare()
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
            val title = playerApi.title
            val chapterPath = uri.path ?: ""

            // 从 URI 的父目录扫描所有音频文件
            val bookDir = File(chapterPath).parentFile
            var chapterFiles = scanAudioFiles(bookDir)
            val startIndex = chapterFiles.indexOfFirst { it.absolutePath == chapterPath }
                .coerceAtLeast(0)

            // 最终安全检查：如果仍然没有文件，直接返回
            if (chapterFiles.isEmpty()) {
                Log.e("PlayerActivity", "无法找到任何音频文件: chapterPath=$chapterPath")
                return
            }

            val mediaItems = chapterFiles.mapIndexed { index, file ->
                MediaItem.Builder()
                    .setUri(Uri.fromFile(file))
                    .setMediaId(file.absolutePath)
                    .setMediaMetadata(
                        MediaMetadata.Builder().apply {
                            val itemTitle = if (index == startIndex) (title ?: file.nameWithoutExtension) else file.nameWithoutExtension
                            setTitle(itemTitle)
                        }.build(),
                    )
                    .build()
            }

            // 提取章节名列表供播放器 UI 使用
            val chapterNames = chapterFiles.map { it.nameWithoutExtension }
            // 缓存章节路径，供 finishAndStopPlayerSession 反查索引
            audioBookChapterPaths = chapterFiles.map { it.absolutePath }
            Log.d("PlayerActivity", "playVideo audio: startIndex=$startIndex, startUri=$chapterPath, paths=${audioBookChapterPaths.take(5)}${if (audioBookChapterPaths.size > 5) "..." else ""}")

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
            viewModel.audioChapterCount = chapterFiles.size
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

    private fun updateKeepScreenOnFlag() {
        if (mediaController?.isPlaying == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun finishAndStopPlayerSession() {
        if (isFinishingPlayer) return
        isFinishingPlayer = true
        lifecycleScope.launch {
            // 听书续播：通过 mediaId 反查章节索引（currentMediaItemIndex 在切集过渡期可能返回 C.INDEX_UNSET）
            if (intent.getBooleanExtra("audio_only", false)) {
                val mediaId = mediaController?.currentMediaItem?.mediaId
                val rawPos = mediaController?.currentPosition ?: 0L
                val rawDur = mediaController?.duration ?: 0L
                // C.TIME_UNSET 会转为负数，只保留有效值
                val position = rawPos.coerceAtLeast(0L)
                val duration = rawDur.coerceAtLeast(0L)
                // 从缓存路径列表中反查真实章节索引，兜底 currentMediaItemIndex
                val chapterIndex = resolveChapterIndex(
                    mediaController?.currentMediaItemIndex ?: 0,
                )
                // 只在有有效数据时保存（duration > 0 表示播放器已加载完毕）
                if (mediaId != null && duration > 0) {
                    val bookPath = File(mediaId).parent ?: ""
                    Log.d("PlayerActivity", "finish save: mediaId=$mediaId, bookPath=$bookPath, chapterIndex=$chapterIndex, position=$position, duration=$duration")
                    if (bookPath.isNotEmpty()) {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            preferencesRepository.updateApplicationPreferences { p ->
                                Log.d("PlayerActivity", "DataStore before: resumeState=${p.audiobookResumeState[bookPath]}, chapterProgress=${p.audiobookChapterProgress["$bookPath|$chapterIndex"]}")
                                val newMap = p.audiobookResumeState.toMutableMap()
                                newMap[bookPath] = "$chapterIndex|$position"
                                val pg = p.audiobookChapterProgress.toMutableMap()
                                pg["$bookPath|$chapterIndex"] = "$position|$duration"
                                val result = p.copy(audiobookResumeState = newMap, audiobookChapterProgress = pg)
                                Log.d("PlayerActivity", "DataStore after: resumeState=${result.audiobookResumeState[bookPath]}, key=$bookPath|$chapterIndex=${result.audiobookChapterProgress["$bookPath|$chapterIndex"]}")
                                result
                            }
                        }
                    }
                } else {
                    Log.w("PlayerActivity", "finish save SKIPPED: mediaId=$mediaId, duration=$duration")
                }
            }
            // 退出时通过 PixelCopy 截取 SurfaceView 当前帧作为缩略图
            val sv = playerSurfaceView
            val uri = mediaController?.currentMediaItem?.mediaId
            if (uri != null && sv != null && sv.width > 0 && sv.height > 0) {
                try {
                    val bitmap = Bitmap.createBitmap(
                        sv.width, sv.height, Bitmap.Config.ARGB_8888,
                    )
                    val copyResult = suspendCancellableCoroutine { cont ->
                        PixelCopy.request(
                            sv, bitmap,
                            { result -> cont.resume(result) {} },
                            Handler(Looper.getMainLooper()),
                        )
                    }
                    if (copyResult == PixelCopy.SUCCESS) {
                        val path = withContext(Dispatchers.Default) {
                            // 缩到 320px 宽的缩略图
                            val thumbW = 320
                            val thumbH = (bitmap.height.toFloat() / bitmap.width * thumbW).toInt().coerceAtLeast(1)
                            val thumb = Bitmap.createScaledBitmap(bitmap, thumbW, thumbH, true)
                            bitmap.recycle()
                            thumbnailExtractor.saveDirect(uri, thumb)
                        }
                        if (path != null) {
                            PlayerFrameCapture.put(uri, path)
                        }
                    } else {
                        bitmap.recycle()
                    }
                } catch (_: Exception) {
                    // 截图失败不影响退出
                }
            }
            // 音频模式：只停播放+清空列表，保留 Service 热连接（下次播放秒开）
            // 视频模式：走完整清理流程（含 DB 位置记录）
            if (intent.getBooleanExtra("audio_only", false)) {
                mediaController?.stop()
                mediaController?.clearMediaItems()
            } else {
                mediaController?.stopPlayerSession()
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

    companion object {
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "wma", "opus")


        private fun scanAudioFiles(dir: File?): List<File> {
            if (dir == null || !dir.isDirectory) return emptyList()
            return dir.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
                ?.sortedBy { it.name }
                ?: emptyList()
        }
    }
}
