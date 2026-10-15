package com.fluxplayer.app.feature.videopicker.screens.audiobook

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxplayer.app.core.common.LocalListeningBook
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private var libraryRevision = 0L

    init {
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
        // 从真实持久值读取目录选择版本，应用重开只加载已保存的书架。
        viewModelScope.launch {
            preferencesRepository.persistedApplicationPreferences
                .map { it.audiobookRootUri to it.audiobookLibraryRevision }
                .distinctUntilChanged()
                .collect { (rootUri, revision) ->
                    libraryRevision = revision
                    if (rootUri.isNotBlank()) {
                        openLibrary(rootUri, revision)
                    } else {
                        scanJob?.cancel()
                        _uiState.update { it.copy(rootUri = null, scanState = DataState.Success(emptyList()), partialBooks = emptyList(), isRefreshing = false, scanError = null) }
                    }
                }
        }
    }

    fun setRootUri(uriString: String) {
        viewModelScope.launch {
            preferencesRepository.updateApplicationPreferences { prefs ->
                prefs.copy(audiobookRootUri = uriString, audiobookLibraryRevision = maxOf(System.currentTimeMillis(), prefs.audiobookLibraryRevision + 1))
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

    private fun openLibrary(rootUri: String, revision: Long) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _uiState.update { it.copy(rootUri = rootUri, scanState = DataState.Loading, partialBooks = emptyList(), scanError = null) }
            try {
                val shelf = withContext(Dispatchers.IO) {
                    val saved = LocalListeningLibrary.shelf(appContext, rootUri)
                    when {
                        saved?.revision == revision -> saved
                        revision == 0L -> saved ?: LocalListeningLibrary.migrateShelf(appContext, rootUri)
                        else -> {
                            // 记录本次选择已接收；扫描失败或进程中断后不在下次启动自动重试。
                            LocalListeningLibrary.saveShelf(appContext, rootUri, revision, saved?.books.orEmpty())
                            null
                        }
                    }
                }
                if (shelf != null) {
                    _uiState.update { it.copy(scanState = DataState.Success(shelf.books.map { book -> book.toAudioBook() }), isRefreshing = false) }
                } else {
                    performScan(rootUri, false, revision)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(scanState = DataState.Error(error), isRefreshing = false, scanError = "书库索引读取失败，请手动刷新：${error.message.orEmpty()}") }
            }
        }
    }

    private fun scan(rootUri: String, refresh: Boolean) {
        scanJob?.cancel()
        val revision = libraryRevision
        scanJob = viewModelScope.launch { performScan(rootUri, refresh, revision) }
    }

    private suspend fun performScan(rootUri: String, refresh: Boolean, revision: Long) {
        val previous = _uiState.value.scanState
        _uiState.update { it.copy(isRefreshing = refresh, scanError = null, partialBooks = emptyList(), scanState = if (refresh) previous else DataState.Loading) }
        try {
            val books = withContext(Dispatchers.IO) {
                val root = if (rootUri.startsWith("/")) Uri.fromFile(File(rootUri)) else Uri.parse(rootUri)
                val scanned = LocalListeningLibrary.children(appContext, root)
                    .filter { it.directory && !it.name.startsWith('.') }
                    .sortedWith(compareBy(NaturalOrder.fileNameComparator) { it.name })
                    .mapNotNull { folder ->
                        currentCoroutineContext().ensureActive()
                        val book = LocalListeningLibrary.scanBook(appContext, folder)
                        if (book.chapters.isEmpty()) return@mapNotNull null
                        audiobookProgressRepository.reconcile(book.path, book.chapters.map { it.uri.toString() })
                        if (!refresh) _uiState.update { it.copy(partialBooks = it.partialBooks + book.toAudioBook()) }
                        book
                    }
                currentCoroutineContext().ensureActive()
                LocalListeningLibrary.saveShelf(appContext, rootUri, revision, scanned)
                scanned.map { it.toAudioBook() }
            }
            _uiState.update { it.copy(scanState = DataState.Success(books), partialBooks = emptyList(), isRefreshing = false) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _uiState.update { it.copy(isRefreshing = false, scanError = error.message ?: "无法读取书库，请重新选择目录", scanState = if (previous is DataState.Success) previous else DataState.Error(error)) }
        }
    }

    private fun LocalListeningBook.toAudioBook() = AudioBook(
        title,
        path,
        cover,
        chapters.size,
        chapters.map { AudioChapter(it.name.substringBeforeLast('.'), it.uri, it.size) },
    )
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
