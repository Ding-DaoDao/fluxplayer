package com.fluxplayer.app.feature.videopicker.screens.audiobook

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxplayer.app.core.common.LocalListeningLibrary
import com.fluxplayer.app.core.common.NaturalOrder
import com.fluxplayer.app.core.data.repository.AudiobookProgressRepository
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.ui.base.DataState
import com.fluxplayer.app.feature.videopicker.model.AudioBook
import com.fluxplayer.app.feature.videopicker.model.AudioChapter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class AudiobookViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val audiobookProgressRepository: AudiobookProgressRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AudiobookUiState())
    val uiState = _uiState.asStateFlow()

    private var scanJob: Job? = null

    init {
        // 更新进度/续播状态（不触发重扫）
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect { prefs ->
                _uiState.update {
                    it.copy(
                        rootUri = prefs.audiobookRootUri.takeIf { it.isNotBlank() },
                    )
                }
            }
        }
        viewModelScope.launch {
            var cachedPaths: List<String>? = null
            audiobookProgressRepository.progress.collect { progress ->
                val paths = (progress.resumeStates.keys + progress.lastPlayedAt.keys)
                    .sortedByDescending { progress.lastPlayedAt[it] ?: 0L }
                    .take(20)
                // 历史卡片先用磁盘索引恢复，不等待外部目录扫描；进度跳动不重复读索引。
                val recent = if (paths != cachedPaths) {
                    withContext(Dispatchers.IO) {
                        paths.mapNotNull { path ->
                            LocalListeningLibrary.find(appContext, path)?.let { book ->
                                AudioBook(book.title, book.path, book.cover, book.chapters.size, book.chapters.map { AudioChapter(it.name.substringBeforeLast('.'), it.uri, it.size) })
                            }
                        }
                    }.also { cachedPaths = paths }
                } else {
                    _uiState.value.recentLocalBooks
                }
                _uiState.update {
                    it.copy(resumeStates = progress.resumeStates, chapterProgress = progress.chapterProgress, lastPlayedAt = progress.lastPlayedAt, recentLocalBooks = recent)
                }
            }
        }
        // 仅在 rootUri 变化时重新扫描目录
        viewModelScope.launch {
            preferencesRepository.applicationPreferences
                .map { it.audiobookRootUri }
                .distinctUntilChanged()
                .collect { rootUri ->
                    if (rootUri.isNotBlank()) {
                        scanBooks(rootUri)
                    } else {
                        scanJob?.cancel()
                        _uiState.update { it.copy(scanState = DataState.Success(emptyList()), partialBooks = emptyList(), isRefreshing = false, scanError = null) }
                    }
                }
        }
    }

    fun setRootUri(uriString: String) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { prefs ->
                prefs.copy(audiobookRootUri = uriString)
            }
        }
    }

    fun reportDirectoryError(message: String) {
        _uiState.update { it.copy(scanError = message) }
    }

    fun scanBooks(rootUri: String) = scan(rootUri, false)

    fun refresh() {
        _uiState.value.rootUri?.let { scan(it, false) }
    }

    fun refreshBooks() {
        _uiState.value.rootUri?.let { scan(it, true) }
    }

    private fun scan(rootUri: String, refresh: Boolean) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            val previous = _uiState.value.scanState
            _uiState.update { it.copy(isRefreshing = refresh, scanError = null, partialBooks = emptyList(), scanState = if (refresh) previous else DataState.Loading) }
            try {
                val books = withContext(Dispatchers.IO) {
                    val root = if (rootUri.startsWith("/")) Uri.fromFile(File(rootUri)) else Uri.parse(rootUri)
                    LocalListeningLibrary.children(appContext, root)
                        .filter { it.directory && !it.name.startsWith('.') }
                        .sortedWith(compareBy(NaturalOrder.fileNameComparator) { it.name })
                        .mapNotNull { folder ->
                            val book = LocalListeningLibrary.scanBook(appContext, folder)
                            if (book.chapters.isEmpty()) return@mapNotNull null
                            audiobookProgressRepository.reconcile(book.path, book.chapters.map { it.uri.toString() })
                            AudioBook(book.title, book.path, book.cover, book.chapters.size, book.chapters.map { AudioChapter(it.name.substringBeforeLast('.'), it.uri, it.size) }).also { scanned ->
                                if (!refresh) _uiState.update { it.copy(partialBooks = it.partialBooks + scanned) }
                            }
                        }
                }
                _uiState.update { it.copy(scanState = DataState.Success(books), partialBooks = emptyList(), isRefreshing = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(isRefreshing = false, scanError = e.message ?: "无法读取书库，请重新选择目录", scanState = if (previous is DataState.Success) previous else DataState.Error(e)) }
            }
        }
    }
}

data class AudiobookUiState(
    val rootUri: String? = null,
    val scanState: DataState<List<AudioBook>> = DataState.Loading,
    /** 扫描过程中的部分结果：边扫边填充，供 UI 在 Loading 时提前展示书架 */
    val partialBooks: List<AudioBook> = emptyList(),
    /** 下拉刷新进行中（列表保持显示，顶部指示器驱动） */
    val isRefreshing: Boolean = false,
    val scanError: String? = null,
    val resumeStates: Map<String, String> = emptyMap(),
    /** key: "bookPath|chapterIndex", value: "positionMs|durationMs" */
    val chapterProgress: Map<String, String> = emptyMap(),
    /** 书籍最近播放时间（epochMillis），用于书架排序 */
    val lastPlayedAt: Map<String, Long> = emptyMap(),
    /** 历史记录的书籍快照，独立于当前书库扫描结果。 */
    val recentLocalBooks: List<AudioBook> = emptyList(),
)
