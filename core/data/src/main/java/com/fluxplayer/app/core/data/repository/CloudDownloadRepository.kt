package com.fluxplayer.app.core.data.repository

import android.util.Log
import com.fluxplayer.app.core.common.CustomDownloadManager
import com.fluxplayer.app.core.database.dao.DownloadTaskDao
import com.fluxplayer.app.core.database.entities.DownloadStatus
import com.fluxplayer.app.core.database.entities.DownloadTaskEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

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
        private const val TAG = "CloudDownloadRepo"
        private const val DEFAULT_DOWNLOAD_PATH = "/storage/emulated/0/Download/"
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
    private val _isDownloading = MutableStateFlow(false)
    val isDownloading = _isDownloading.asStateFlow()

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
     * @return 任务 ID
     */
    suspend fun download(
        url: String,
        fileName: String,
        headers: Map<String, String> = emptyMap(),
        provider: String
    ): Long {
        // 获取存储路径
        val downloadPath = getDownloadPath()
        val targetDir = File(downloadPath)

        // 写入 PENDING 记录
        val task = DownloadTaskEntity(
            fileName = fileName,
            url = url,
            fileSize = 0L,
            status = DownloadStatus.PENDING,
            provider = provider,
            createdAt = System.currentTimeMillis()
        )
        val taskId = downloadTaskDao.insert(task)
        Log.d(TAG, "创建下载任务: id=$taskId, fileName=$fileName, provider=$provider, path=$downloadPath")

        try {
            // 更新为 DOWNLOADING
            downloadTaskDao.update(task.copy(id = taskId, status = DownloadStatus.DOWNLOADING))
            _isDownloading.value = true

            val result = customDownloadManager.download(
                url = url,
                fileName = fileName,
                targetDir = targetDir,
                headers = headers,
                onProgress = { progress ->
                    runBlocking {
                        downloadTaskDao.update(
                            task.copy(
                                id = taskId,
                                status = DownloadStatus.DOWNLOADING,
                                downloadedBytes = progress.downloadedBytes,
                                fileSize = if (progress.totalBytes > 0) progress.totalBytes else 0L
                            )
                        )
                    }
                    _downloadEvents.tryEmit(
                        DownloadEvent.Progress(
                            taskId = taskId,
                            fileName = fileName,
                            progress = progress.progress,
                            downloadedBytes = progress.downloadedBytes,
                            totalBytes = progress.totalBytes
                        )
                    )
                }
            )

            result.fold(
                onSuccess = { filePath ->
                    // 更新为 COMPLETED
                    downloadTaskDao.update(
                        task.copy(
                            id = taskId,
                            status = DownloadStatus.COMPLETED,
                            filePath = filePath,
                            completedAt = System.currentTimeMillis()
                        )
                    )
                    _downloadEvents.tryEmit(
                        DownloadEvent.Completed(taskId = taskId, fileName = fileName, filePath = filePath)
                    )
                    Log.d(TAG, "下载完成: $fileName -> $filePath")
                },
                onFailure = { e ->
                    if (e is CancellationException) throw e
                    // 更新为 FAILED
                    downloadTaskDao.update(
                        task.copy(
                            id = taskId,
                            status = DownloadStatus.FAILED,
                            completedAt = System.currentTimeMillis()
                        )
                    )
                    _downloadEvents.tryEmit(
                        DownloadEvent.Failed(taskId = taskId, fileName = fileName, error = e.message ?: "下载失败")
                    )
                    Log.e(TAG, "下载失败: $fileName, error=${e.message}")
                }
            )
        } catch (e: CancellationException) {
            downloadTaskDao.update(
                task.copy(id = taskId, status = DownloadStatus.FAILED, completedAt = System.currentTimeMillis())
            )
            _downloadEvents.tryEmit(
                DownloadEvent.Failed(taskId = taskId, fileName = fileName, error = "下载已取消")
            )
            Log.d(TAG, "下载已取消: $fileName")
        } finally {
            _isDownloading.value = false
        }

        return taskId
    }

    /**
     * 取消下载
     */
    fun cancel() {
        customDownloadManager.cancel()
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
