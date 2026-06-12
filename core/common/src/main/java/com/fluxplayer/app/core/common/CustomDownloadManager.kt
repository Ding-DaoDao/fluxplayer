package com.fluxplayer.app.core.common

import android.content.Context
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自定义下载管理器 — 带进度回调的 HTTP 文件下载
 */
@Singleton
class CustomDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class DownloadProgress(
        val fileName: String = "",
        val progress: Float = 0f,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val isComplete: Boolean = false,
        val filePath: String? = null,
        val error: String? = null
    )

    private val _progress = MutableStateFlow(DownloadProgress())
    val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    suspend fun download(
        url: String,
        fileName: String,
        headers: Map<String, String> = emptyMap(),
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }

            if (connection.responseCode != 200) {
                throw Exception("HTTP ${connection.responseCode}")
            }

            val totalBytes = connection.contentLengthLong
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadDir.mkdirs()
            val file = File(downloadDir, sanitizeFileName(fileName))
            val inputStream = connection.inputStream

            FileOutputStream(file).use { output ->
                val buffer = ByteArray(8192)
                var downloadedBytes = 0L
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    val prog = if (totalBytes > 0) {
                        downloadedBytes.toFloat() / totalBytes.toFloat()
                    } else -1f

                    val dp = DownloadProgress(
                        fileName = fileName,
                        progress = prog.coerceIn(0f, 1f),
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes
                    )
                    _progress.value = dp
                    onProgress?.invoke(dp)
                }
            }
            inputStream.close()
            connection.disconnect()

            val final = DownloadProgress(fileName = fileName, progress = 1f, isComplete = true, filePath = file.absolutePath)
            _progress.value = final
            onProgress?.invoke(final)
            file.absolutePath
        }
    }

    fun resetProgress() { _progress.value = DownloadProgress() }

    private fun sanitizeFileName(name: String): String = name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
}
