package com.fluxplayer.app.core.data.cloud

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink

/** 上传文件暂存在缓存目录，分段读取，避免将大视频完整载入内存。 */
class CloudUploadSource(
    val file: File,
    val name: String,
    val mimeType: String = "application/octet-stream",
    val onProgress: (Long, Long) -> Unit = { _, _ -> },
) {
    val size: Long get() = file.length()

    suspend fun digest(algorithm: String, start: Long = 0, end: Long = size): ByteArray = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val digest = MessageDigest.getInstance(algorithm)
        RandomAccessFile(file, "r").use { input ->
            input.seek(start)
            val buffer = ByteArray(64 * 1024)
            var remaining = end - start
            while (remaining > 0) {
                context.ensureActive()
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(count > 0) { "上传文件读取中断" }
                digest.update(buffer, 0, count)
                remaining -= count
            }
        }
        digest.digest()
    }

    suspend fun hash(algorithm: String, start: Long = 0, end: Long = size): String =
        digest(algorithm, start, end).joinToString("") { "%02x".format(it) }

    suspend fun send(
        client: OkHttpClient,
        url: String,
        start: Long,
        end: Long,
        headers: Map<String, String> = emptyMap(),
    ): String {
        require(start >= 0 && end >= start && end <= size) { "上传分段范围无效" }
        repeat(3) { attempt ->
            try {
                return sendOnce(client, url, start, end, headers)
            } catch (error: java.io.IOException) {
                currentCoroutineContext().ensureActive()
                if (attempt == 2) throw error
                kotlinx.coroutines.delay((attempt + 1) * 1000L)
            }
        }
        error("上传分段失败")
    }

    private suspend fun sendOnce(
        client: OkHttpClient,
        url: String,
        start: Long,
        end: Long,
        headers: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        val body = object : RequestBody() {
            override fun contentType() = headers["Content-Type"]?.toMediaType()
            override fun contentLength(): Long = end - start
            override fun writeTo(sink: BufferedSink) {
                RandomAccessFile(file, "r").use { input ->
                    input.seek(start)
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    while (sent < end - start) {
                        context.ensureActive()
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), end - start - sent).toInt())
                        check(count > 0) { "上传文件读取中断" }
                        sink.write(buffer, 0, count)
                        sent += count
                        onProgress(start + sent, size)
                    }
                }
            }
        }
        val request = Request.Builder().url(url).put(body).apply {
            headers.forEach { (key, value) -> header(key, value) }
        }.build()
        client.newCall(request).execute().use { response ->
            if (response.code in setOf(408, 429, 500, 502, 503, 504)) throw java.io.IOException("上传分段暂不可用（HTTP ${response.code}）")
            check(response.isSuccessful) { "上传分段失败（HTTP ${response.code}）" }
            response.header("ETag").orEmpty()
        }
    }
}
