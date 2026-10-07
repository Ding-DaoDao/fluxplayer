package com.fluxplayer.app.core.data.repository

import com.fluxplayer.app.core.common.CustomDownloadManager
import com.fluxplayer.app.core.database.dao.DownloadTaskDao
import com.fluxplayer.app.core.database.entities.DownloadStatus
import com.fluxplayer.app.core.database.entities.DownloadTaskEntity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 统一下载仓库 — 封装 CustomDownloadManager + Room 持久化
 */
@Singleton
class CloudDownloadRepository @Inject constructor(
    private val downloadTaskDao: DownloadTaskDao,
    private val customDownloadManager: CustomDownloadManager,
    private val preferencesRepository: PreferencesRepository,
) {
    companion object {
        private const val DEFAULT_DOWNLOAD_PATH = "/storage/emulated/0/Download/"

        /** 进度写库节流间隔（毫秒），避免进度回调每秒触发数十次 DB 写入 */
        private const val PROGRESS_WRITE_INTERVAL_MS = 500L
    }

    /** 下载进度事件 */
    private val _downloadEvents = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 64)
    val downloadEvents: SharedFlow<DownloadEvent> = _downloadEvents.asSharedFlow()

    sealed class DownloadEvent {
        data class Progress(
            val taskId: Long,
            val fileName: String,
            val progress: Float,
            val downloadedBytes: Long,
            val totalBytes: Long
        ) : DownloadEvent()

        data class Completed(val taskId: Long, val fileName: String, val filePath: String) :
            DownloadEvent()

        data class Failed(val taskId: Long, val fileName: String, val error: String) :
            DownloadEvent()
    }

    /** 当前是否正在下载 */
    private val activeTasks = MutableStateFlow<Set<Long>>(emptySet())
    val isDownloading: Flow<Boolean> = activeTasks.map { it.isNotEmpty() }.distinctUntilChanged()

    data class DownloadResult(val taskId: Long, val fileName: String, val filePath: String, val size: Long)

    /**
     * 获取当前下载存储路径
     */
    fun getDownloadPath(): String {
        return preferencesRepository.applicationPreferences.value.downloadPath
            .ifBlank { DEFAULT_DOWNLOAD_PATH }
    }

    /**
     * 更新下载存储路径
     */
    suspend fun setDownloadPath(path: String) {
        preferencesRepository.updateApplicationPreferences { it.copy(downloadPath = path) }
    }

    /**
     * 下载文件
     * @param url 下载 URL
     * @param fileName 文件名
     * @param headers 请求头
     * @param provider 云盘提供商标识
     * @return 已持久化的下载完成信息
     */
    suspend fun download(
        url: String,
        fileName: String,
        headers: Map<String, String> = emptyMap(),
        provider: String,
        onProgress: (DownloadEvent.Progress) -> Unit = {},
    ): DownloadResult {
        val targetDir = File(getDownloadPath())
        val task = DownloadTaskEntity(
            fileName = fileName,
            url = url,
            fileSize = 0L,
            status = DownloadStatus.DOWNLOADING,
            provider = provider,
            createdAt = System.currentTimeMillis(),
        )
        val taskId = withContext(NonCancellable) { downloadTaskDao.insert(task) }
        activeTasks.update { it + taskId }
        try {
            currentCoroutineContext().ensureActive()
            val result = coroutineScope {
                // 只保留最新进度，写库任务属于本次下载，结束前等待写入完成。
                val progressUpdates = Channel<CustomDownloadManager.DownloadProgress>(Channel.CONFLATED)
                val writer = launch {
                    for (progress in progressUpdates) {
                        downloadTaskDao.updateProgress(taskId, progress.downloadedBytes, progress.totalBytes.coerceAtLeast(0L))
                        currentCoroutineContext().ensureActive()
                        val event = DownloadEvent.Progress(taskId, fileName, progress.progress, progress.downloadedBytes, progress.totalBytes)
                        onProgress(event)
                        _downloadEvents.tryEmit(event)
                    }
                }
                var lastProgressWriteNs = 0L
                try {
                    customDownloadManager.download(
                        url = url,
                        fileName = fileName,
                        targetDir = targetDir,
                        headers = headers,
                        onProgress = { progress ->
                            val now = System.nanoTime()
                            if (lastProgressWriteNs == 0L || now - lastProgressWriteNs >= PROGRESS_WRITE_INTERVAL_MS * 1_000_000) {
                                lastProgressWriteNs = now
                                progressUpdates.trySend(progress)
                            }
                        },
                    )
                } finally {
                    progressUpdates.close()
                    writer.join()
                }
            }
            val filePath = result.getOrThrow()
            // 已获得完整文件后确保终态落库，避免退出页面留下下载中记录。
            val size = withContext(NonCancellable + Dispatchers.IO) {
                File(filePath).length().also { downloadTaskDao.complete(taskId, filePath, it, System.currentTimeMillis()) }
            }
            _downloadEvents.tryEmit(DownloadEvent.Completed(taskId, fileName, filePath))
            return DownloadResult(taskId, fileName, filePath, size)
        } catch (error: CancellationException) {
            // 已取消的协程仍需落库终态，随后继续向上传递取消信号。
            withContext(NonCancellable) { downloadTaskDao.fail(taskId, System.currentTimeMillis()) }
            throw error
        } catch (error: Exception) {
            downloadTaskDao.fail(taskId, System.currentTimeMillis())
            _downloadEvents.tryEmit(DownloadEvent.Failed(taskId, fileName, error.message ?: "下载失败"))
            throw error
        } finally {
            activeTasks.update { it - taskId }
        }
    }

    /**
     * 获取所有下载历史
     */
    fun getDownloadHistory(): Flow<List<DownloadTaskEntity>> =
        downloadTaskDao.getAllAsFlow()

    /**
     * 获取活跃下载任务
     */
    fun getActiveDownloads(): Flow<List<DownloadTaskEntity>> =
        downloadTaskDao.getActiveDownloadsAsFlow()

    /**
     * 删除下载任务
     */
    suspend fun deleteTask(taskId: Long) {
        downloadTaskDao.delete(taskId)
    }
}
