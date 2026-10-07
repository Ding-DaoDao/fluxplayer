package com.fluxplayer.app.feature.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxplayer.app.core.common.FluxNotificationDelegate
import com.fluxplayer.app.core.common.sortedByNaturalName
import com.fluxplayer.app.core.data.cloud.CloudUriResolver
import com.fluxplayer.app.core.data.repository.DanmakuRepository
import com.fluxplayer.app.core.data.repository.MediaRepository
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.domain.GetSortedPlaylistUseCase
import com.fluxplayer.app.core.model.AnimeMatch
import com.fluxplayer.app.core.model.DanmakuDownloadState
import com.fluxplayer.app.core.model.DanmakuSource
import com.fluxplayer.app.core.model.EpisodeInfo
import com.fluxplayer.app.core.model.FluxMessageEvent
import com.fluxplayer.app.core.model.LoopMode
import com.fluxplayer.app.core.model.PlayerPreferences
import com.fluxplayer.app.core.model.Video
import com.fluxplayer.app.core.model.VideoContentScale
import com.fluxplayer.app.feature.player.danmaku.Danmaku
import com.fluxplayer.app.feature.player.danmaku.DanmakuParser
import com.fluxplayer.app.feature.player.danmaku.DanmakuSearchViewMode
import com.fluxplayer.app.feature.player.state.SubtitleOptionsEvent
import com.fluxplayer.app.feature.player.state.VideoZoomEvent
import com.fluxplayer.app.feature.player.ui.QualityOption
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
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
    private val cloudUriResolver: CloudUriResolver,
) : ViewModel() {
    // region ==================== 统一通知 ====================

    /** 通知事件委托 */
    val notifier = FluxNotificationDelegate(viewModelScope)

    /** 供 UI 层收集的通知事件流 */
    val messageEvents: SharedFlow<FluxMessageEvent> = notifier.events

    // endregion


    var playWhenReady: Boolean = true

    /** 音频章节名称列表（播放器 UI 使用） */
    var audioChapterNames: List<String> = emptyList()
    /** 音频章节总数 */
    var audioChapterCount: Int = 0

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

    /** 各弹幕源的结果缓存（sourceId -> 搜索/剧集状态），切换源时即时恢复，无需重新请求 */
    private val danmakuResultCache = mutableMapOf<String, DanmakuDownloadState>()

    /** 当前正在展示结果的弹幕源 id（Idle/Ready 时为 null） */
    private val _activeDanmakuSourceId = MutableStateFlow<String?>(null)
    val activeDanmakuSourceId = _activeDanmakuSourceId.asStateFlow()

    /** 各弹幕源已缓存的结果数量（用于标签页角标） */
    private val _danmakuSourceResultCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val danmakuSourceResultCounts = _danmakuSourceResultCounts.asStateFlow()

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

    private val danmakuLoadTask = LatestTask(viewModelScope)

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
        danmakuLoadTask.launch(
            load = {
                withContext(Dispatchers.IO) {
                    val list = DanmakuParser.loadFromUri(context, uri) ?: error("无法解析弹幕文件")
                    val file = uri.path?.let { java.io.File(it) }
                    val selection = file?.parentFile?.let { dir ->
                        DanmakuSelectionContext.LocalFile(
                            dir = dir,
                            currentFile = file,
                            allFiles = dir.listFiles()
                                ?.filter { it.extension.lowercase() in listOf("xml", "json", "bilibili") }
                                ?.sortedByNaturalName() ?: emptyList(),
                        )
                    }
                    list to selection
                }
            },
            onSuccess = { (list, selection) ->
                publishDanmaku(uri, list)
                danmakuContext = selection
                notifier.success(context.getString(R.string.danmaku_loaded_toast, list.size))
            },
            onFailure = { error -> notifier.error(error.message ?: "弹幕加载失败") },
        )
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
                // 文件追加写入移到 IO 线程，避免阻塞主线程
                withContext(Dispatchers.IO) {
                    DanmakuParser.appendToXml(context, uri, danmaku)
                }
            }
        }
    }

    // ── 弹幕 API 搜索与下载 ────────────────────────────

    /**
     * 通过 API 搜索弹幕。
     * @param source 弹幕源
     * @param keyword 搜索关键词
     */
    fun searchDanmaku(context: Context, source: DanmakuSource, keyword: String) {
        Log.d(TAG, "searchDanmaku: source=$source keyword=$keyword")
        currentSource = source
        _danmakuDownloadState.value = DanmakuDownloadState.Searching(source)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val animes = danmakuRepository.searchAnime(source, keyword)
                Log.d(TAG, "searchDanmaku: found ${animes.size} results for keyword=$keyword")
                if (animes.isEmpty()) {
                    _danmakuDownloadState.value = DanmakuDownloadState.Error(
                        context.getString(R.string.danmaku_error_no_result), source,
                    )
                } else {
                    val result = DanmakuDownloadState.SearchResult(animes, source)
                    // 缓存与 UI 状态在主线程更新，避免与标签页切换的读操作竞争
                    withContext(Dispatchers.Main) {
                        lastSearchResults = result
                        danmakuResultCache[source.id] = result
                        _activeDanmakuSourceId.value = source.id
                        _danmakuSourceResultCounts.update { it + (source.id to animes.size) }
                        _danmakuDownloadState.value = result
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "searchDanmaku failed", e)
                _danmakuDownloadState.value = DanmakuDownloadState.Error(
                    e.message ?: context.getString(R.string.danmaku_error_search_failed), source,
                )
            }
        }
    }

    /**
     * 选择动漫并获取剧集列表。
     */
    fun selectAnime(context: Context, anime: AnimeMatch) {
        val source = currentSource ?: return
        Log.d(TAG, "selectAnime: animeId=${anime.animeId} title=${anime.title} source=$source")
        _danmakuDownloadState.value = DanmakuDownloadState.Searching(source)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val episodes = danmakuRepository.getEpisodes(source, anime)
                Log.d(TAG, "selectAnime: got ${episodes.size} episodes for anime=${anime.title}")
                val state = DanmakuDownloadState.AnimeSelected(anime, episodes, source = source)
                withContext(Dispatchers.Main) {
                    lastAnimeInfo = state
                    danmakuResultCache[source.id] = state
                    _activeDanmakuSourceId.value = source.id
                    _danmakuDownloadState.value = state
                }
            } catch (e: Exception) {
                Log.e(TAG, "selectAnime failed", e)
                _danmakuDownloadState.value = DanmakuDownloadState.Error(
                    e.message ?: context.getString(R.string.danmaku_error_episodes_failed), source,
                )
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
        val animeInfo = lastAnimeInfo
        danmakuLoadTask.launch(
            load = { downloadDanmaku(context, source, episode) },
            onSuccess = { (uri, list) ->
                publishDanmaku(uri, list)
                _danmakuDownloadState.value = DanmakuDownloadState.Ready(uri.toString())
                _activeDanmakuSourceId.value = null
                notifier.success(context.getString(R.string.danmaku_loaded_toast, list.size))
                lastAnimeInfo = animeInfo?.copy(currentEpisode = episode)
                danmakuContext = animeInfo?.let {
                    DanmakuSelectionContext.Network(source, it.anime, episode, it.episodes)
                }
            },
            onFailure = { error ->
                _danmakuDownloadState.value = DanmakuDownloadState.Error(
                    error.message ?: context.getString(R.string.danmaku_error_unknown), source,
                )
            },
        )
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
            _activeDanmakuSourceId.value = it.source?.id
        } ?: run {
            _danmakuDownloadState.value = DanmakuDownloadState.Idle
            _activeDanmakuSourceId.value = null
        }
    }

    fun resetDanmakuSearch() {
        _danmakuDownloadState.value = DanmakuDownloadState.Idle
        currentSource = null
        lastAnimeInfo = null
        lastSearchResults = null
        danmakuResultCache.clear()
        _danmakuSourceResultCounts.value = emptyMap()
        _activeDanmakuSourceId.value = null
        danmakuForCurrentEpisode.value = false
        danmakuContext = null
    }

    /**
     * 弹幕搜索弹窗内返回上一步：
     * - AnimeSelected → 回到该源的搜索结果（优先取缓存，回退 lastSearchResults）
     * - SearchResult → 回到 Idle（缓存保留，切回该源仍可恢复结果）
     */
    fun navigateDanmakuBack() {
        val current = _danmakuDownloadState.value
        when {
            current is DanmakuDownloadState.AnimeSelected -> {
                val sourceId = current.source?.id ?: currentSource?.id
                val cachedResult = sourceId?.let { danmakuResultCache[it] }
                    as? DanmakuDownloadState.SearchResult
                val lastResults = lastSearchResults?.takeIf {
                    sourceId == null || it.source?.id == sourceId
                }
                when {
                    cachedResult != null -> _danmakuDownloadState.value = cachedResult
                    lastResults != null -> _danmakuDownloadState.value = lastResults
                    else -> {
                        _danmakuDownloadState.value = DanmakuDownloadState.Idle
                        _activeDanmakuSourceId.value = null
                    }
                }
                if (_danmakuDownloadState.value !is DanmakuDownloadState.Idle) {
                    _activeDanmakuSourceId.value = sourceId
                }
            }
            current is DanmakuDownloadState.SearchResult -> {
                _danmakuDownloadState.value = DanmakuDownloadState.Idle
                lastSearchResults = null
                _activeDanmakuSourceId.value = null
            }
            else -> resetDanmakuSearch()
        }
    }

    /**
     * 切换弹幕源标签页：
     * - 有缓存 → 立即恢复该源的结果/剧集列表（不联网）
     * - 无缓存且有关键词 → 按当前关键词搜索
     * - 无缓存且无关键词 → 回到 Idle
     */
    fun selectDanmakuSource(context: Context, source: DanmakuSource) {
        val cached = danmakuResultCache[source.id]
        when {
            cached != null -> {
                currentSource = source
                when (cached) {
                    is DanmakuDownloadState.SearchResult -> lastSearchResults = cached
                    is DanmakuDownloadState.AnimeSelected -> lastAnimeInfo = cached
                    else -> {}
                }
                _activeDanmakuSourceId.value = source.id
                _danmakuDownloadState.value = cached
            }
            _danmakuSearchKeyword.value.isNotBlank() -> {
                searchDanmaku(context, source, _danmakuSearchKeyword.value)
            }
            else -> {
                currentSource = source
                _danmakuDownloadState.value = DanmakuDownloadState.Idle
                _activeDanmakuSourceId.value = null
            }
        }
    }

    fun setDanmakuSearchViewMode(mode: DanmakuSearchViewMode) {
        _danmakuSearchViewMode.value = mode
    }

    fun setDanmakuLocalBrowserDir(path: String?) {
        _danmakuLocalBrowserDir.value = path
    }

    fun setDanmakuSearchKeyword(keyword: String) {
        if (keyword == _danmakuSearchKeyword.value) return
        _danmakuSearchKeyword.value = keyword
        // 关键词变化后，旧关键词的结果全部失效
        danmakuResultCache.clear()
        _danmakuSourceResultCounts.value = emptyMap()
        _activeDanmakuSourceId.value = null
        _danmakuDownloadState.value = DanmakuDownloadState.Idle
    }

    /**
     * 清空弹幕（切集时调用）。
     * 关闭显示，同时把下载状态回退到剧集列表（而非 "弹幕已加载"）。
     */
    fun clearDanmaku() {
        danmakuLoadTask.cancel()
        Log.d(TAG, "clearDanmaku: hiding danmaku (keeping list)")
        // 标记当前弹幕数据不匹配当前剧集（切集了）
        danmakuForCurrentEpisode.value = false
        // 回退到剧集列表界面，方便用户为新集选择弹幕
        lastAnimeInfo?.let {
            _danmakuDownloadState.value = it
            _activeDanmakuSourceId.value = it.source?.id
        }
        danmakuEnabled.value = false
    }

    /** 退出播放时调用，清除弹幕上下文防止 onMediaItemTransition 误触发弹幕加载 */
    fun onPlayerExit() {
        danmakuLoadTask.cancel()
        isExiting = true
        danmakuContext = null
    }

    /**
     * 切集时自动加载相邻弹幕。
     * indexStep = 新索引 - 旧索引。只在步数为 ±1（相邻切集）时自动加载，跳集时不加载。
     */
    fun onMediaItemTransition(indexStep: Int, context: Context) {
        if (isExiting) return
        danmakuLoadTask.cancel()
        // 先清空旧弹幕状态，防止异步加载窗口期旧数据残留到 DanmakuController
        _danmakuList.value = null
        danmakuEnabled.value = false
        danmakuForCurrentEpisode.value = false
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
        notifier.info(context.getString(R.string.danmaku_loading_toast, file.name))
        danmakuLoadTask.launch(
            load = {
                withContext(Dispatchers.IO) {
                    val uri = Uri.fromFile(file)
                    val list = DanmakuParser.loadFromUri(context, uri)
                    require(!list.isNullOrEmpty()) { "弹幕文件为空或无法解析" }
                    uri to list
                }
            },
            onSuccess = { (uri, list) ->
                publishDanmaku(uri, list)
                notifier.success(context.getString(R.string.danmaku_loaded_count, list.size))
            },
            onFailure = { error -> onAutomaticDanmakuFailure(error) },
        )
    }

    /** 切集自动加载与手动选择共享任务，旧结果不能覆盖当前剧集。 */
    private fun silentLoadNetworkDanmaku(context: Context, source: DanmakuSource?, episode: EpisodeInfo) {
        if (source == null) return
        notifier.info(context.getString(R.string.danmaku_loading_toast, episode.title))
        danmakuLoadTask.launch(
            load = { downloadDanmaku(context, source, episode) },
            onSuccess = { (uri, list) ->
                publishDanmaku(uri, list)
                notifier.success(context.getString(R.string.danmaku_loaded_count, list.size))
            },
            onFailure = { error -> onAutomaticDanmakuFailure(error) },
        )
    }

    private suspend fun downloadDanmaku(context: Context, source: DanmakuSource, episode: EpisodeInfo): Pair<Uri, List<Danmaku>> =
        withContext(Dispatchers.IO) {
            val uri = danmakuRepository.downloadAndCache(source, episode)
                ?: error(context.getString(R.string.danmaku_error_download_failed))
            val file = java.io.File(requireNotNull(uri.path))
            check(file.exists()) { context.getString(R.string.danmaku_error_cache_missing) }
            val list = file.inputStream().use { DanmakuParser.parseBilibiliXml(it) }
            check(list.isNotEmpty()) { context.getString(R.string.danmaku_error_parse_failed) }
            uri to list
        }

    private fun publishDanmaku(uri: Uri, list: List<Danmaku>) {
        _danmakuList.value = list
        _danmakuFileUri.value = uri
        danmakuEnabled.value = true
        danmakuForCurrentEpisode.value = true
    }

    private fun onAutomaticDanmakuFailure(error: Exception) {
        Log.e(TAG, "自动加载弹幕失败", error)
        danmakuForCurrentEpisode.value = false
        danmakuEnabled.value = false
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

    suspend fun getPlaylistFromUri(uri: Uri): List<Video> {
        return getSortedPlaylistUseCase.invoke(uri)
    }

    /**
     * 读取制定书籍的片头片尾跳过设置，返回 flow<Pair<片头秒数, 片尾秒数>>。
     */
    fun audioSkipSettings(bookPath: String) = preferencesRepository.applicationPreferences.map { prefs ->
        val entry = prefs.audiobookSkipSettings[bookPath]
        if (entry != null) {
            val parts = entry.split(",")
            val intro = parts.getOrNull(0)?.toIntOrNull() ?: 0
            val outro = parts.getOrNull(1)?.toIntOrNull() ?: 0
            intro to outro
        } else {
            0 to 0
        }
    }

    fun updateVideoZoom(uri: String, zoom: Float) {
        viewModelScope.launch {
            mediaRepository.updateMediumZoom(uri, zoom)
        }
    }

    fun updateMediumIntroOutro(uri: String, introMs: Long, outroMs: Long) {
        viewModelScope.launch {
            mediaRepository.updateMediumIntroOutro(uri, introMs, outroMs)
        }
    }

    /** 当前播放列表的父目录路径，用作片头片尾持久化的 key */
    var playlistParentPath: String = ""

    /** 根据播放列表设置父目录路径（带来源前缀，确保本地/云端互不干扰） */
    suspend fun resolveParentDirFromPlaylist(playlist: List<String>) {
        if (playlist.isEmpty()) return
        val firstUri = playlist.first()
        playlistParentPath = when {
            firstUri.startsWith("content://") || firstUri.startsWith("file://") -> {
                val localPath = mediaRepository.getVideoByUri(firstUri)?.parentPath
                    ?: firstUri.substringAfter("file://").substringBeforeLast('/')
                if (localPath.isNotEmpty()) "local:$localPath" else ""
            }
            firstUri.startsWith("cloud:") -> {
                val uri = android.net.Uri.parse(firstUri)
                val folder = com.fluxplayer.app.core.common.CloudUriScheme.getCloudFolder(uri)
                val provider = com.fluxplayer.app.core.common.CloudUriScheme.getProvider(uri) ?: ""
                if (!folder.isNullOrEmpty()) {
                    "cloud:$provider/$folder"
                } else {
                    val cloudKey = firstUri.substringBeforeLast('/')
                    if (cloudKey.isNotEmpty() && cloudKey != firstUri) "cloud:$cloudKey" else ""
                }
            }
            else -> {
                val fallback = firstUri.substringBeforeLast('/')
                if (fallback.isNotEmpty() && fallback != firstUri) "other:$fallback" else ""
            }
        }
    }

    fun updatePlayerBrightness(value: Float) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(playerBrightness = value) }
        }
    }

    fun updateSpeedPresets(presets: List<Float>) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(speedPresets = presets) }
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

    fun updateDanmakuConfig(config: com.fluxplayer.app.core.model.DanmakuConfig) {
        viewModelScope.launch {
            preferencesRepository.updatePlayerPreferences { it.copy(danmakuConfig = config) }
        }
    }

    /**
     * 刷新 123 云盘画质 URL（鉴权 token 可能过期）
     * 返回新的 QualityOption 列表，同时更新 VideoQualityCache
     */
    suspend fun refreshPan123QualityUrls(fileId: String): List<QualityOption>? {
        val freshOptions = cloudUriResolver.refreshPan123QualityUrls(fileId) ?: return null
        return freshOptions.map { QualityOption(label = it.label, uri = android.net.Uri.parse(it.url)) }
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
