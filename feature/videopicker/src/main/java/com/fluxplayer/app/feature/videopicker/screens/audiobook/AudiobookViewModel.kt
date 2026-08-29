package com.fluxplayer.app.feature.videopicker.screens.audiobook

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.ui.base.DataState
import com.fluxplayer.app.feature.videopicker.model.AudioBook
import com.fluxplayer.app.feature.videopicker.model.AudioChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/** 封面图备选文件名（无扩展名，大小写敏感） */
private val COVER_NAMES = setOf("cover", "Cover")

/** 支持的图片扩展名 */
private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp")

/** 支持的音频扩展名 */
private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "wma", "opus")

@HiltViewModel
class AudiobookViewModel @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AudiobookUiState())
    val uiState = _uiState.asStateFlow()

    init {
        // 更新进度/续播状态（不触发重扫）
        viewModelScope.launch {
            preferencesRepository.applicationPreferences.collect { prefs ->
                _uiState.update {
                    it.copy(
                        rootUri = prefs.audiobookRootUri.takeIf { it.isNotBlank() },
                        resumeStates = prefs.audiobookResumeState,
                        chapterProgress = prefs.audiobookChapterProgress,
                    )
                }
            }
        }
        // 仅在 rootUri 变化时重新扫描目录
        viewModelScope.launch {
            preferencesRepository.applicationPreferences
                .map { it.audiobookRootUri }
                .distinctUntilChanged()
                .collect { rootUri ->
                    if (rootUri.isNotBlank()) scanBooks(rootUri)
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

    fun scanBooks(rootUri: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(scanState = DataState.Loading, partialBooks = emptyList()) }
            try {
                val books = withContext(Dispatchers.IO) {
                    // 并行扫描，每扫完一本立即回调发布（增量填充书架）
                    scanDirectory(rootUri) { book ->
                        _uiState.update { it.copy(partialBooks = it.partialBooks + book) }
                    }
                }
                _uiState.update { it.copy(scanState = DataState.Success(books)) }
            } catch (e: Exception) {
                _uiState.update { it.copy(scanState = DataState.Error(e)) }
            }
        }
    }

    fun refresh() {
        val root = _uiState.value.rootUri ?: return
        scanBooks(root)
    }

    /**
     * 下拉刷新：保留当前书架直到新结果到达（旧列表不闪空），
     * 完成后整体替换列表；刷新期间由 [AudiobookUiState.isRefreshing] 驱动下拉指示器。
     */
    fun refreshBooks() {
        val root = _uiState.value.rootUri ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            try {
                val books = withContext(Dispatchers.IO) {
                    scanDirectory(root, onBookScanned = {})
                }
                _uiState.update {
                    it.copy(
                        isRefreshing = false,
                        scanState = DataState.Success(books),
                        partialBooks = emptyList(),
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isRefreshing = false, scanState = DataState.Error(e)) }
            }
        }
    }

    /**
     * 扫描根目录：遍历一级子文件夹，每个子文件夹 = 一本书。
     * 并发扫描（IO 密集任务并行收益明显），每本书扫描完成即回调 [onBookScanned]。
     */
    private suspend fun scanDirectory(
        rootUriStr: String,
        onBookScanned: (AudioBook) -> Unit,
    ): List<AudioBook> {
        val rootDir = uriToFile(rootUriStr) ?: return emptyList()
        if (!rootDir.isDirectory) return emptyList()

        val folders = rootDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?: emptyList()

        return coroutineScope {
            folders.map { folder ->
                async(Dispatchers.IO) {
                    scanBookFolder(folder).also { onBookScanned(it) }
                }
            }.awaitAll()
        }
    }

    /**
     * 扫描单本书文件夹：收集音频文件 + 封面图。
     */
    private fun scanBookFolder(folder: File): AudioBook {
        val allFiles = folder.listFiles()
            ?.filter { !it.isDirectory && !it.name.startsWith(".") }
            ?: emptyList()

        // 音频文件
        val audioFiles = allFiles
            .filter { it.extension.lowercase() in AUDIO_EXTENSIONS }
            .sortedBy { it.name }

        val chapters = audioFiles.map { file ->
            AudioChapter(
                title = file.nameWithoutExtension,
                uri = file.toUri(),
                size = file.length(),
            )
        }

        // 封面图：复制到 app 缓存目录，确保 scoped storage 下可读
        val coverFile = findCoverImage(allFiles)
        val coverUri = coverFile?.let { cacheCover(it, folder.name) }

        return AudioBook(
            title = folder.name,
            folderPath = folder.absolutePath,
            coverUri = coverUri,
            chapterCount = chapters.size,
            chapters = chapters,
        )
    }

    /**
     * 将封面文件复制到 app 缓存目录，返回可被 Coil 加载的 URI。
     * crc32 文件夹路径防止不同书封面重名冲突。
     */
    private fun cacheCover(source: File, folderName: String): Uri? {
        return try {
            val ext = source.extension.takeIf { it.isNotBlank() } ?: "jpg"
            val hash = MessageDigest.getInstance("MD5")
                .digest(source.absolutePath.toByteArray())
                .take(4)
                .joinToString("") { "%02x".format(it) }
            val coverDir = File(appContext.cacheDir, "audiobook_covers")
            if (!coverDir.exists()) coverDir.mkdirs()
            val dest = File(coverDir, "${folderName}_${hash}.$ext")
            // 已存在则跳过复制
            if (!dest.exists()) {
                source.copyTo(dest, overwrite = false)
            }
            dest.toUri()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 封面检测：
     * 1. 优先找 cover/Cover.* (jpg/jpeg/png/webp/bmp)
     * 2. 没找到 → 取目录下第一个图片文件
     * 3. 都没有 → null
     */
    private fun findCoverImage(files: List<File>): File? {
        val images = files
            .filter { it.extension.lowercase() in IMAGE_EXTENSIONS }
            .sortedBy { it.name.lowercase() }

        if (images.isEmpty()) return null

        val coverFile = images.firstOrNull { it.nameWithoutExtension in COVER_NAMES }
        return coverFile ?: images.firstOrNull()
    }

    /**
     * 将根目录 URI 字符串转为 File 对象。
     * 支持两种格式：
     * 1. SAF content URI: content://com.android.externalstorage.documents/tree/primary%3A...
     * 2. 原始文件路径: /storage/emulated/0/Audiobook
     */
    private fun uriToFile(uriString: String): File? {
        return when {
            uriString.startsWith("/") -> File(uriString)
            uriString.startsWith("content://") -> contentUriToFile(uriString)
            else -> null
        }
    }

    /**
     * 尝试从 SAF content URI 提取真实文件路径。
     * 格式: content://com.android.externalstorage.documents/tree/primary%3AAudiobook
     *   → /storage/emulated/0/Audiobook
     */
    private fun contentUriToFile(uriString: String): File? {
        return try {
            val uri = Uri.parse(uriString)
            val docId = uri.lastPathSegment ?: return null
            // 处理 URL 编码（如 primary%3A → primary:）
            val decoded = Uri.decode(docId)
            // primary: 替换为 /storage/emulated/0/
            val path = decoded.replaceFirst("^[^:]+:".toRegex(), "")
            File("/storage/emulated/0/$path")
        } catch (_: Exception) {
            null
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
    val resumeStates: Map<String, String> = emptyMap(),
    /** key: "bookPath|chapterIndex", value: "positionMs|durationMs" */
    val chapterProgress: Map<String, String> = emptyMap(),
)
