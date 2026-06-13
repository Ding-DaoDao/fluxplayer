package com.fluxplayer.app.core.common

import kotlinx.coroutines.Dispatchers
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
class CustomDownloadManager @Inject constructor() {
    data class DownloadProgress(
        val fileName: String = "",
        val progress: Float = 0f,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val isComplete: Boolean = false,
        val filePath: String? = null,
        val error: String? = null
    )

    @Volatile
    private var cancelled = false

    /**
     * 下载文件到指定目标目录
     * @param url 下载 URL
     * @param fileName 文件名
     * @param targetDir 目标目录（如 /storage/emulated/0/Download/）
     * @param headers 请求头
     * @param onProgress 进度回调
     * @return 保存的文件绝对路径
     */
    suspend fun download(
        url: String,
        fileName: String,
        targetDir: File,
        headers: Map<String, String> = emptyMap(),
        onProgress: ((DownloadProgress) -> Unit)? = null
    ): Result<String> {
        cancelled = false
        return withContext(Dispatchers.IO) {
            runCatching {
                // 自动补全协议前缀：处理 //example.com 或无协议的情况
                val normalizedUrl = when {
                    url.startsWith("http://") || url.startsWith("https://") -> url
                    url.startsWith("//") -> "https:$url"
                    else -> "https://$url"
                }
                val connection = URL(normalizedUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }

                if (cancelled) throw Exception("下载已取消")

                if (connection.responseCode != 200) {
                    throw Exception("HTTP ${connection.responseCode}")
                }

                val totalBytes = connection.contentLengthLong
                targetDir.mkdirs()
                val file = File(targetDir, sanitizeFileName(fileName))
                val inputStream = connection.inputStream

                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(8192)
                    var downloadedBytes = 0L
                    var bytesRead: Int

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (cancelled) {
                            inputStream.close()
                            connection.disconnect()
                            file.delete()
                            throw Exception("下载已取消")
                        }
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
                        onProgress?.invoke(dp)
                    }
                }
                inputStream.close()
                connection.disconnect()

                val final = DownloadProgress(
                    fileName = fileName,
                    progress = 1f,
                    isComplete = true,
                    filePath = file.absolutePath,
                    downloadedBytes = file.length(),
                    totalBytes = totalBytes
                )
                onProgress?.invoke(final)
                file.absolutePath
            }
        }
    }

    fun cancel() {
        cancelled = true
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_")
}
