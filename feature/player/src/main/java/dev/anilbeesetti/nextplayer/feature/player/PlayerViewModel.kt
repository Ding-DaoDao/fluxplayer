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

    init {
        viewModelScope.launch {
            preferencesRepository.playerPreferences.collect { prefs ->
                internalUiState.update { it.copy(playerPreferences = prefs) }
                danmakuSources.value = prefs.danmakuSources
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
        currentSource = source
        _danmakuDownloadState.value = DanmakuDownloadState.Searching
        viewModelScope.launch(Dispatchers.IO) {
            val animes = danmakuRepository.searchAnime(source, keyword)
            if (animes.isEmpty()) {
                _danmakuDownloadState.value = DanmakuDownloadState.Error("未找到匹配结果")
            } else {
                _danmakuDownloadState.value = DanmakuDownloadState.SearchResult(animes)
            }
        }
    }

    /**
     * 选择动漫并获取剧集列表。
     */
    fun selectAnime(anime: AnimeMatch) {
        val source = currentSource ?: return
        _danmakuDownloadState.value = DanmakuDownloadState.Searching
        viewModelScope.launch(Dispatchers.IO) {
            val episodes = danmakuRepository.getEpisodes(source, anime.animeId)
            val state = DanmakuDownloadState.AnimeSelected(anime, episodes)
            lastAnimeInfo = state
            _danmakuDownloadState.value = state
        }
    }

    /**
     * 选择剧集并下载弹幕。
     */
    fun selectEpisode(context: Context, episode: EpisodeInfo) {
        val source = currentSource ?: return
        Log.d(TAG, "selectEpisode: episodeId=${episode.episodeId} source=${source?.baseUrl}")
        _danmakuDownloadState.value = DanmakuDownloadState.Downloading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uri = danmakuRepository.downloadAndCache(source, episode.episodeId)
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
                        } else {
                            _danmakuDownloadState.value = DanmakuDownloadState.Error("弹幕解析失败")
                        }
                    } else {
                        _danmakuDownloadState.value = DanmakuDownloadState.Error("缓存文件不存在")
                    }
                } else {
                    _danmakuDownloadState.value = DanmakuDownloadState.Error("下载失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "selectEpisode failed", e)
                _danmakuDownloadState.value = DanmakuDownloadState.Error(e.message ?: "未知错误")
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
        danmakuForCurrentEpisode.value = false
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
