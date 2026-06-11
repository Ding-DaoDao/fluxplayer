package dev.anilbeesetti.nextplayer.feature.player

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.anilbeesetti.nextplayer.core.data.repository.DanmakuRepository
import dev.anilbeesetti.nextplayer.core.data.repository.MediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.domain.GetSortedPlaylistUseCase
import dev.anilbeesetti.nextplayer.core.model.AnimeMatch
import dev.anilbeesetti.nextplayer.core.model.DanmakuDownloadState
import dev.anilbeesetti.nextplayer.core.model.DanmakuSource
import dev.anilbeesetti.nextplayer.core.model.EpisodeInfo
import dev.anilbeesetti.nextplayer.core.model.LoopMode
import dev.anilbeesetti.nextplayer.core.model.PlayerPreferences
import dev.anilbeesetti.nextplayer.core.model.Video
import dev.anilbeesetti.nextplayer.core.model.VideoContentScale
import dev.anilbeesetti.nextplayer.feature.player.danmaku.Danmaku
import dev.anilbeesetti.nextplayer.feature.player.danmaku.DanmakuParser
import dev.anilbeesetti.nextplayer.feature.player.danmaku.DanmakuSearchViewMode
import dev.anilbeesetti.nextplayer.feature.player.state.SubtitleOptionsEvent
import dev.anilbeesetti.nextplayer.feature.player.state.VideoZoomEvent
import android.util.Log
import android.widget.Toast
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "PlayerViewModel"

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val preferencesRepository: PreferencesRepository,
    private val getSortedPlaylistUseCase: GetSortedPlaylistUseCase,
    private val danmakuRepository: DanmakuRepository,
) : ViewModel() {

    var playWhenReady: Boolean = true

    private val internalUiState = MutableStateFlow(
        PlayerUiState(
            playerPreferences = preferencesRepository.playerPreferences.value,
        ),
    )
    val uiState = internalUiState.asStateFlow()

    // ── 弹幕状态 ────────────────────────────────────────

    /** 解析后的弹幕列表（null = 未加载） */
    private val _danmakuList = MutableStateFlow<List<Danmaku>?>(null)
    val danmakuList = _danmakuList.asStateFlow()

    /** 弹幕文件 URI（用于追加写入） */
    private val _danmakuFileUri = MutableStateFlow<Uri?>(null)
    val danmakuFileUri = _danmakuFileUri.asStateFlow()

    /** 弹幕渲染开关 */
    val danmakuEnabled = MutableStateFlow(false)

    /** 弹幕数据是否匹配当前剧集（切集后变 false，重新下载后变 true） */
    val danmakuForCurrentEpisode = MutableStateFlow(false)

    /** 播放速度（用于弹幕联动） */
    val playbackSpeed = MutableStateFlow(1.0f)

    // ── 弹幕 API 搜索状态 ──────────────────────────────

    /** 弹幕源列表 */
    val danmakuSources = MutableStateFlow<List<DanmakuSource>>(emptyList())

    /** 弹幕下载状态 */
    private val _danmakuDownloadState = MutableStateFlow<DanmakuDownloadState>(DanmakuDownloadState.Idle)
    val danmakuDownloadState = _danmakuDownloadState.asStateFlow()

    /** 当前选中的弹幕源 */
    private var currentSource: DanmakuSource? = null

    /** 上次选择的动漫信息（用于切集后回退到剧集列表） */
    private var lastAnimeInfo: DanmakuDownloadState.AnimeSelected? = null

    /** 上次搜索结果（用于从剧集列表退回搜索结果） */
    private var lastSearchResults: DanmakuDownloadState.SearchResult? = null

    /** 当前弹幕选择上下文（切集时用于自动加载相邻弹幕） */
    private var danmakuContext: DanmakuSelectionContext? = null

    /** 标记播放器正在退出，抑制所有切集回调 */
    private var isExiting = false

    // ── 弹幕搜索弹窗 UI 状态（跨 show/hide 持久化） ──

    private val _danmakuSearchViewMode = MutableStateFlow(DanmakuSearchViewMode.SEARCH)
    val danmakuSearchViewMode = _danmakuSearchViewMode.asStateFlow()

    private val _danmakuLocalBrowserDir = MutableStateFlow<String?>(null)
    val danmakuLocalBrowserDir = _danmakuLocalBrowserDir.asStateFlow()

    private val _danmakuSearchKeyword = MutableStateFlow("")
    val danmakuSearchKeyword = _danmakuSearchKeyword.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.playerPreferences.collect { prefs ->
                internalUiState.update { it.copy(playerPreferences = prefs) }
                danmakuSources.value = DanmakuSource.filterValid(prefs.danmakuSources)
            }
        }
    }

    /**
     * 从 URI 加载弹幕文件并解析。
     */
    fun loadDanmaku(context: Context, uri: Uri) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                DanmakuParser.loadFromUri(context, uri)
            }
            if (list != null) {
                _danmakuList.value = list
                _danmakuFileUri.value = uri
                danmakuEnabled.value = true
                danmakuForCurrentEpisode.value = true
                Toast.makeText(context, "弹幕已加载（${list.size}条）", Toast.LENGTH_SHORT).show()
                // 保存本地弹幕上下文（用于切集自动加载）
                val filePath = uri.path?.let { java.io.File(it) }
                if (filePath != null && filePath.parentFile != null) {
                    val dir = filePath.parentFile!!
                    val allDanmakuFiles = dir.listFiles { f ->
                        f.extension.lowercase() in listOf("xml", "json", "bilibili")
                    }?.sortedBy { it.name } ?: emptyList()
                    danmakuContext = DanmakuSelectionContext.LocalFile(
                        dir = dir,
                        currentFile = filePath,
                        allFiles = allDanmakuFiles,
                    )
                }
            }
        }
    }

    /**
     * 切换弹幕显示开关。
     */
    fun toggleDanmaku() {
        danmakuEnabled.value = !danmakuEnabled.value
    }

    /**
     * 用户发送一条弹幕：异步追加写入本地 XML 文件。
     * 渲染由调用方通过 DanmakuController 完成。
     */
    fun saveDanmakuToFile(context: Context, danmaku: Danmaku) {
        val uri = _danmakuFileUri.value
        if (uri != null) {
            viewModelScope.launch {
                DanmakuParser.appendToXml(context, uri, danmaku)
            }
        }
    }

    // ── 弹幕 API 搜索与下载 ────────────────────────────

    /**
     * 通过 API 搜索弹幕。
     * @param source 弹幕源
     * @param keyword 搜索关键词
     */
    fun searchDanmaku(source: DanmakuSource, keyword: String) {
        Log.d(TAG, "searchDanmaku: source=$source keyword=$keyword")
        currentSource = source
        _danmakuDownloadState.value = DanmakuDownloadState.Searching(source)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val animes = danmakuRepository.searchAnime(source, keyword)
                Log.d(TAG, "searchDanmaku: found ${animes.size} results for keyword=$keyword")
                if (animes.isEmpty()) {
                    _danmakuDownloadState.value = DanmakuDownloadState.Error("未找到匹配结果", source)
                } else {
                    val result = DanmakuDownloadState.SearchResult(animes, source)
                    lastSearchResults = result
                    _danmakuDownloadState.value = result
                }
            } catch (e: Exception) {
                Log.e(TAG, "searchDanmaku failed", e)
                _danmakuDownloadState.value = DanmakuDownloadState.Error(e.message ?: "搜索失败", source)
            }
        }
    }

    /**
     * 选择动漫并获取剧集列表。
     */
    fun selectAnime(anime: AnimeMatch) {
        val source = currentSource ?: return
        Log.d(TAG, "selectAnime: animeId=${anime.animeId} title=${anime.title} source=$source")
        _danmakuDownloadState.value = DanmakuDownloadState.Searching(source)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val episodes = danmakuRepository.getEpisodes(source, anime)
                Log.d(TAG, "selectAnime: got ${episodes.size} episodes for anime=${anime.title}")
                val state = DanmakuDownloadState.AnimeSelected(anime, episodes)
                lastAnimeInfo = state
                _danmakuDownloadState.value = state
            } catch (e: Exception) {
                Log.e(TAG, "selectAnime failed", e)
                _danmakuDownloadState.value = DanmakuDownloadState.Error(e.message ?: "获取剧集失败", source)
            }
        }
    }

    /**
     * 选择剧集并下载弹幕。
     */
    fun selectEpisode(context: Context, episode: EpisodeInfo) {
        val source = currentSource ?: return
        Log.d(TAG, "selectEpisode: episodeId=${episode.episodeId} title=${episode.title} source=${source.id} url=${episode.url}")
        _danmakuDownloadState.value = DanmakuDownloadState.Downloading(source)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uri = danmakuRepository.downloadAndCache(source, episode)
                Log.d(TAG, "selectEpisode: download result uri=$uri")
                if (uri != null) {
                    // 直接用 FileInputStream 读取本地缓存文件
                    val file = java.io.File(uri.path!!)
                    Log.d(TAG, "selectEpisode: cache file exists=${file.exists()} size=${file.length()}")
                    if (file.exists()) {
                        val list = DanmakuParser.parseBilibiliXml(file.inputStream())
                        Log.d(TAG, "selectEpisode: parsed ${list.size} danmaku items")
                        if (list.isNotEmpty()) {
                            Log.d(TAG, "selectEpisode: first 3 items: " +
                                list.take(3).joinToString { "${it.timeMs}ms '${it.text.take(20)}'" })
                            _danmakuList.value = list
                            _danmakuFileUri.value = uri
                            danmakuEnabled.value = true
                            danmakuForCurrentEpisode.value = true
                            _danmakuDownloadState.value = DanmakuDownloadState.Ready(uri.toString())
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "弹幕已加载（${list.size}条）", Toast.LENGTH_SHORT).show()
                            }
                            // 保存网络弹幕上下文（用于切集自动加载）
                            lastAnimeInfo = lastAnimeInfo?.copy(currentEpisode = episode)
                            danmakuContext = lastAnimeInfo?.let {
                                DanmakuSelectionContext.Network(
                                    source = source,
                                    anime = it.anime,
                                    currentEpisode = episode,
                                    episodes = it.episodes,
                                )
                            }
                        } else {
                            _danmakuDownloadState.value = DanmakuDownloadState.Error("弹幕解析失败", source)
                        }
                    } else {
                        _danmakuDownloadState.value = DanmakuDownloadState.Error("缓存文件不存在", source)
                    }
                } else {
                    _danmakuDownloadState.value = DanmakuDownloadState.Error("下载失败", source)
                }
            } catch (e: Exception) {
                Log.e(TAG, "selectEpisode failed", e)
                _danmakuDownloadState.value = DanmakuDownloadState.Error(e.message ?: "未知错误", source)
            }
        }
    }

    /**
     * 重置弹幕搜索状态。
     */
    /**
     * 恢复到上次搜索状态（剧集列表）。
     */
    fun restoreLastSearchState() {
        lastAnimeInfo?.let {
            _danmakuDownloadState.value = it
        } ?: run {
            _danmakuDownloadState.value = DanmakuDownloadState.Idle
        }
    }

    fun resetDanmakuSearch() {
        _danmakuDownloadState.value = DanmakuDownloadState.Idle
        currentSource = null
        lastAnimeInfo = null
        lastSearchResults = null
        danmakuForCurrentEpisode.value = false
        danmakuContext = null
    }

    /**
     * 弹幕搜索弹窗内返回上一步：
     * - AnimeSelected → 回到搜索结果列表
     * - SearchResult → 回到 Idle
     */
    fun navigateDanmakuBack() {
        val current = _danmakuDownloadState.value
        when {
            current is DanmakuDownloadState.AnimeSelected -> {
                lastSearchResults?.let {
                    _danmakuDownloadState.value = it
                } ?: run {
                    _danmakuDownloadState.value = DanmakuDownloadState.Idle
                }
            }
            current is DanmakuDownloadState.SearchResult -> {
                _danmakuDownloadState.value = DanmakuDownloadState.Idle
                lastSearchResults = null
            }
            else -> resetDanmakuSearch()
        }
    }

    fun setDanmakuSearchViewMode(mode: DanmakuSearchViewMode) {
        _danmakuSearchViewMode.value = mode
    }

    fun setDanmakuLocalBrowserDir(path: String?) {
        _danmakuLocalBrowserDir.value = path
    }

    fun setDanmakuSearchKeyword(keyword: String) {
        _danmakuSearchKeyword.value = keyword
    }

    /**
     * 是否已有弹幕数据（搜索/本地加载过）。
     */
    fun hasAnyDanmaku(): Boolean = _danmakuList.value != null

    /**
     * 清空弹幕（切集时调用）。
     * 关闭显示，同时把下载状态回退到剧集列表（而非 "弹幕已加载"）。
     */
    fun clearDanmaku() {
        Log.d(TAG, "clearDanmaku: hiding danmaku (keeping list)")
        // 标记当前弹幕数据不匹配当前剧集（切集了）
        danmakuForCurrentEpisode.value = false
        // 回退到剧集列表界面，方便用户为新集选择弹幕
        lastAnimeInfo?.let {
            _danmakuDownloadState.value = it
        }
        danmakuEnabled.value = false
    }

    /** 退出播放时调用，清除弹幕上下文防止 onMediaItemTransition 误触发弹幕加载 */
    fun onPlayerExit() {
        isExiting = true
        danmakuContext = null
    }

    /**
     * 切集时自动加载相邻弹幕。
     * indexStep = 新索引 - 旧索引。只在步数为 ±1（相邻切集）时自动加载，跳集时不加载。
     */
    fun onMediaItemTransition(indexStep: Int, context: Context) {
        if (isExiting) return
        val ctx = danmakuContext ?: run {
            Log.d(TAG, "onMediaItemTransition: no danmaku context, clearing")
            clearDanmaku()
            return
        }
        Log.d(TAG, "onMediaItemTransition: indexStep=$indexStep, ctx=$ctx")

        // 跳集（步数绝对值 > 1）不自动加载弹幕
        if (kotlin.math.abs(indexStep) != 1) {
            Log.d(TAG, "onMediaItemTransition: skip auto-load (jumped $indexStep steps)")
            clearDanmaku()
            return
        }

        val isForward = indexStep > 0
        when (ctx) {
            is DanmakuSelectionContext.LocalFile -> {
                val currentIndex = ctx.allFiles.indexOf(ctx.currentFile)
                val nextIndex = if (isForward) currentIndex + 1 else currentIndex - 1
                if (nextIndex in ctx.allFiles.indices) {
                    val nextFile = ctx.allFiles[nextIndex]
                    danmakuContext = ctx.copy(currentFile = nextFile)
                    silentLoadLocalDanmaku(context, nextFile)
                } else {
                    Log.d(TAG, "onMediaItemTransition: local file index out of range ($nextIndex)")
                }
            }
            is DanmakuSelectionContext.Network -> {
                val currentIndex = ctx.episodes.indexOf(ctx.currentEpisode)
                val nextIndex = if (isForward) currentIndex + 1 else currentIndex - 1
                if (nextIndex in ctx.episodes.indices) {
                    val nextEpisode = ctx.episodes[nextIndex]
                    danmakuContext = ctx.copy(currentEpisode = nextEpisode)
                    silentLoadNetworkDanmaku(context, ctx.source, nextEpisode)
                } else {
                    Log.d(TAG, "onMediaItemTransition: episode index out of range ($nextIndex)")
                }
            }
        }
    }

    /**
     * 静默加载本地弹幕文件（切集自动加载时调用）。
     * 不改变搜索 UI 状态，显示 Toast 提示。
     */
    private fun silentLoadLocalDanmaku(context: Context, file: java.io.File) {
        Log.d(TAG, "silentLoadLocalDanmaku: ${file.name}")
        viewModelScope.launch {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "正在加载 ${file.name}", Toast.LENGTH_SHORT).show()
            }
            val list = withContext(Dispatchers.IO) {
                DanmakuParser.loadFromUri(context, Uri.fromFile(file))
            }
            if (list != null && list.isNotEmpty()) {
                _danmakuList.value = list
                _danmakuFileUri.value = Uri.fromFile(file)
                danmakuEnabled.value = true
                danmakuForCurrentEpisode.value = true
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "已加载 ${list.size} 条弹幕", Toast.LENGTH_SHORT).show()
                }
            } else {
                Log.d(TAG, "silentLoadLocalDanmaku: failed to load ${file.name}")
            }
        }
    }

    /**
     * 静默下载并加载网络弹幕（切集自动加载时调用）。
     * 不改变搜索 UI 状态，显示 Toast 提示。
     */
    private fun silentLoadNetworkDanmaku(context: Context, source: DanmakuSource?, episode: EpisodeInfo) {
        if (source == null) return
        Log.d(TAG, "silentLoadNetworkDanmaku: episode=${episode.title} (${episode.episodeId})")
        viewModelScope.launch {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "正在加载 ${episode.title}", Toast.LENGTH_SHORT).show()
            }
            try {
                val uri = withContext(Dispatchers.IO) {
                    danmakuRepository.downloadAndCache(source, episode)
                }
                if (uri != null) {
                    val file = java.io.File(uri.path!!)
                    if (file.exists()) {
                        val list = withContext(Dispatchers.IO) {
                            DanmakuParser.parseBilibiliXml(file.inputStream())
                        }
                        if (list.isNotEmpty()) {
                            _danmakuList.value = list
                            _danmakuFileUri.value = uri
                            danmakuEnabled.value = true
                            danmakuForCurrentEpisode.value = true
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "已加载 ${list.size} 条弹幕", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "silentLoadNetworkDanmaku failed", e)
            }
        }
    }

    /**
     * 通过平台源搜索弹幕。
     */
    fun searchPlatformDanmaku(source: DanmakuSource, keyword: String) {
        currentSource = source
        _danmakuDownloadState.value = DanmakuDownloadState.Searching(source)
        viewModelScope.launch(Dispatchers.IO) {
            val animes = danmakuRepository.searchPlatformAnime(keyword, source)
            if (animes.isEmpty()) {
                _danmakuDownloadState.value = DanmakuDownloadState.Error("未找到匹配结果", source)
            } else {
                val result = DanmakuDownloadState.SearchResult(animes, source)
                lastSearchResults = result
                _danmakuDownloadState.value = result
            }
        }
    }

    /**
     * 通过视频 URL 抓取弹幕（平台源自动匹配）。
     */
    fun fetchDanmakuByUrl(context: Context, videoUrl: String) {
        _danmakuDownloadState.value = DanmakuDownloadState.Downloading(DanmakuSource.DANDANPLAY)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uri = danmakuRepository.fetchDanmakuByUrl(videoUrl)
                if (uri != null) {
                    val file = java.io.File(uri.path!!)
                    if (file.exists()) {
                        val list = DanmakuParser.parseBilibiliXml(file.inputStream())
                        if (list.isNotEmpty()) {
                            _danmakuList.value = list
                            _danmakuFileUri.value = uri
                            danmakuEnabled.value = true
                            danmakuForCurrentEpisode.value = true
                            _danmakuDownloadState.value = DanmakuDownloadState.Ready(uri.toString())
                            withContext(Dispatchers.Main) {
                                Toast.makeText(context, "弹幕已加载（${list.size}条）", Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }
                    }
                }
                _danmakuDownloadState.value = DanmakuDownloadState.Error("平台弹幕抓取失败", DanmakuSource.DANDANPLAY)
            } catch (e: Exception) {
                Log.e(TAG, "fetchDanmakuByUrl failed", e)
                _danmakuDownloadState.value = DanmakuDownloadState.Error(e.message ?: "未知错误", DanmakuSource.DANDANPLAY)
            }
        }
    }

    /**
     * 更新播放速度。
     */
    fun updatePlaybackSpeed(speed: Float) {
        playbackSpeed.value = speed
    }

    /**
     * 处理清晰度选择。
     */
    fun onQualitySelected(option: dev.anilbeesetti.nextplayer.feature.player.ui.QualityOption) {
        // 清晰度切换由外部通过 Player 直接 seekTo 实现
        // 此处保留为占位，具体实现在 MediaPlayerScreen 中处理
    }

    /**
     * 更新弹幕源列表。
     */
    fun updateDanmakuSources(sources: List<DanmakuSource>) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences {
                it.copy(danmakuSources = sources)
            }
            danmakuSources.value = sources
        }
    }

    /**
     * 更新弹幕缓存映射（已下载的 episodeId → 本地路径）。
     */
    private fun updateCacheMap(episodeId: Int, localPath: String) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { prefs ->
                prefs.copy(
                    danmakuCacheMap = prefs.danmakuCacheMap + (episodeId.toString() to localPath),
                )
            }
        }
    }

    suspend fun getPlaylistFromUri(uri: Uri): List<Video> {
        return getSortedPlaylistUseCase.invoke(uri)
    }

    fun updateVideoZoom(uri: String, zoom: Float) {
        viewModelScope.launch {
            mediaRepository.updateMediumZoom(uri, zoom)
        }
    }

    fun updatePlayerBrightness(value: Float) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(playerBrightness = value) }
        }
    }

    fun updateVideoContentScale(contentScale: VideoContentScale) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(playerVideoZoom = contentScale) }
        }
    }

    fun setLoopMode(loopMode: LoopMode) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(loopMode = loopMode) }
        }
    }

    fun onVideoZoomEvent(event: VideoZoomEvent) {
        when (event) {
            is VideoZoomEvent.ContentScaleChanged -> {
                updateVideoContentScale(event.contentScale)
            }
            is VideoZoomEvent.ZoomChanged -> {
                updateVideoZoom(event.mediaItem.mediaId, event.zoom)
            }
        }
    }

    fun onSubtitleOptionEvent(event: SubtitleOptionsEvent) {
        when (event) {
            is SubtitleOptionsEvent.DelayChanged -> {
                updateSubtitleDelay(event.mediaItem.mediaId, event.delay)
            }
            is SubtitleOptionsEvent.SpeedChanged -> {
                updateSubtitleSpeed(event.mediaItem.mediaId, event.speed)
            }
        }
    }

    fun updateDanmakuConfig(config: dev.anilbeesetti.nextplayer.core.model.DanmakuConfig) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(danmakuConfig = config) }
        }
    }

    private fun updateSubtitleDelay(uri: String, delay: Long) {
        viewModelScope.launch {
            mediaRepository.updateSubtitleDelay(uri, delay)
        }
    }

    private fun updateSubtitleSpeed(uri: String, speed: Float) {
        viewModelScope.launch {
            mediaRepository.updateSubtitleSpeed(uri, speed)
        }
    }
}

@Stable
data class PlayerUiState(
    val playerPreferences: PlayerPreferences? = null,
)

sealed interface PlayerEvent
