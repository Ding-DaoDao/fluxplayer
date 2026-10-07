package com.fluxplayer.app.core.common

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 下载任务独立取消，文件完整接收后才发布到目标目录。 */
@Singleton
class CustomDownloadManager @Inject constructor() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val publishLock = Any()

    data class DownloadProgress(
        val fileName: String = "",
        val progress: Float = 0f,
        val downloadedBytes: Long = 0,
        val totalBytes: Long = 0,
        val isComplete: Boolean = false,
        val filePath: String? = null,
        val error: String? = null,
    )

    suspend fun download(
        url: String,
        fileName: String,
        targetDir: File,
        headers: Map<String, String> = emptyMap(),
        onProgress: ((DownloadProgress) -> Unit)? = null,
    ): Result<String> = suspendCancellableCoroutine { continuation ->
        val normalizedUrl = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            else -> "https://$url"
        }
        val request = try {
            Request.Builder().url(normalizedUrl).apply {
                headers.forEach { (key, value) -> header(key, value) }
            }.build()
        } catch (error: Exception) {
            continuation.resume(Result.failure(error))
            return@suspendCancellableCoroutine
        }
        val call = client.newCall(request)
        // 取消当前协程只中断当前请求，不影响其他下载。
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resume(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                var temporaryFile: File? = null
                val result = try {
                    response.use {
                        check(response.code == 200) { "HTTP ${response.code}" }
                        val body = response.body ?: throw IOException("下载响应为空")
                        check(targetDir.isDirectory || targetDir.mkdirs()) { "无法创建下载目录" }
                        val partial = File.createTempFile("flux-download-", ".part", targetDir)
                        temporaryFile = partial
                        val totalBytes = body.contentLength()
                        var downloadedBytes = 0L
                        body.byteStream().use { input ->
                            partial.outputStream().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    if (!continuation.isActive) throw IOException("下载已取消")
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    if (!continuation.isActive) throw IOException("下载已取消")
                                    output.write(buffer, 0, count)
                                    downloadedBytes += count
                                    onProgress?.invoke(
                                        DownloadProgress(
                                            fileName = fileName,
                                            progress = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f,
                                            downloadedBytes = downloadedBytes,
                                            totalBytes = totalBytes,
                                        ),
                                    )
                                }
                            }
                        }
                        if (totalBytes >= 0 && downloadedBytes != totalBytes) throw IOException("下载文件不完整")
                        val destination = synchronized(publishLock) {
                            if (!continuation.isActive) throw IOException("下载已取消")
                            val name = sanitizeFileName(fileName)
                            var candidate = File(targetDir, name)
                            var suffix = 1
                            // 同名文件保留，避免失败或重复下载破坏已有内容。
                            while (candidate.exists()) {
                                val extension = File(name).extension.let { ext -> if (ext.isEmpty()) "" else ".$ext" }
                                candidate = File(targetDir, "${File(name).nameWithoutExtension} (${suffix++})$extension")
                            }
                            if (!partial.renameTo(candidate)) throw IOException("无法保存下载文件")
                            candidate
                        }
                        Result.success(destination.absolutePath)
                    }
                } catch (error: Exception) {
                    Result.failure(error)
                } finally {
                    temporaryFile?.delete()
                }
                // 文件已发布但结果尚未送达调用方时取消，回收本任务刚创建的文件。
                continuation.resume(result) { _, cancelledResult, _ ->
                    cancelledResult.getOrNull()?.let { File(it).delete() }
                }
            }
        })
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[/\\\\:*?\"<>|]"), "_").takeUnless { it.isBlank() || it == "." || it == ".." } ?: "download"
}
