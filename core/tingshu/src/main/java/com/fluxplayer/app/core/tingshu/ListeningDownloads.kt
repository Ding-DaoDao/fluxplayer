package com.fluxplayer.app.core.tingshu

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class ListeningDownload(val status: Int, val downloaded: Long, val size: Long, val reason: Int = 0)

/** 下载交给系统持续执行，应用重启后仍可查询状态并优先播放已完成的音频。 */
class ListeningDownloads(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = app.getSharedPreferences("listening_downloads", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS).readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()
    private val directory = File(app.getExternalFilesDir(null) ?: app.filesDir, "listening_audio").apply { mkdirs() }
    var limitBytes: Long
        get() = prefs.getLong("limit", 2L * 1024 * 1024 * 1024)
        set(value) {
            prefs.edit().putLong("limit", value.coerceAtLeast(512L * 1024 * 1024)).apply()
        }

    fun state(book: ListeningBook, index: Int): ListeningDownload? = record(book, index)?.let { data ->
        manager.query(DownloadManager.Query().setFilterById(data.getLong("id")))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            ListeningDownload(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)), cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)), cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)), cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
        }
    }

    fun offlineUri(book: ListeningBook, index: Int): Uri? {
        val data = record(book, index) ?: return null
        if (state(book, index)?.status != DownloadManager.STATUS_SUCCESSFUL) return null
        return File(directory, data.getString("file")).takeIf { it.isFile && it.length() > 0 }?.let(Uri::fromFile)
    }

    fun states(book: ListeningBook): Map<Int, ListeningDownload> {
        val records = book.episodes.indices.mapNotNull { index -> record(book, index)?.let { it.getLong("id") to index } }.toMap()
        if (records.isEmpty()) return emptyMap()
        return manager.query(DownloadManager.Query().setFilterById(*records.keys.toLongArray()))?.use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    val index = records[cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))] ?: continue
                    put(index, ListeningDownload(cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)), cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)), cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)), cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))))
                }
            }
        }.orEmpty()
    }

    suspend fun enqueue(repository: TingshuRepository, book: ListeningBook, index: Int, allowMetered: Boolean = false): Unit = withContext(Dispatchers.IO) {
        if (state(book, index)?.status in setOf(DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED) || offlineUri(book, index) != null) return@withContext
        val resource = repository.resolve(book, index)
        val headers = resource.headers + repository.playbackHeaders(book.sourceId, resource.url)
        require(!Uri.parse(resource.url).path.orEmpty().endsWith(".m3u8", true)) { "该章节使用分段流媒体，暂不支持离线下载" }
        val request = Request.Builder().url(resource.url).head().apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        val size = client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "无法检查下载文件（HTTP ${response.code}），请重试" }
            val type = response.header("Content-Type").orEmpty().lowercase()
            require(!type.contains("mpegurl") && !type.contains("text/html") && !type.contains("json")) { "该地址不是可直接下载的音频文件" }
            response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
                ?: error("书源未提供文件大小，暂无法安全安排离线下载")
        }
        synchronized(lock) {
            require(reservedBytes() + size <= limitBytes) { "离线空间不足，请清理已下载音频或提高空间上限" }
            require(directory.usableSpace > size + 50L * 1024 * 1024) { "设备剩余存储空间不足" }
            val key = key(book, index)
            record(book, index)?.let { manager.remove(it.getLong("id")) }
            val destination = File(directory, "$key.audio")
            val download = DownloadManager.Request(Uri.parse(resource.url)).setTitle("${book.title} · ${book.episodes[index].title}")
                .setDestinationUri(Uri.fromFile(destination)).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(allowMetered).setAllowedOverRoaming(false)
            headers.forEach { (name, value) -> download.addRequestHeader(name, value) }
            val id = manager.enqueue(download)
            prefs.edit().putString(key, JSONObject().put("id", id).put("file", destination.name).put("size", size).toString()).apply()
        }
    }

    fun reservedBytes(): Long = prefs.all.values.filterIsInstance<String>().sumOf { raw ->
        runCatching {
            val data = JSONObject(raw)
            manager.query(DownloadManager.Query().setFilterById(data.getLong("id")))?.use { cursor ->
                if (cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_FAILED) data.optLong("size") else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    fun remove(book: ListeningBook, index: Int) = synchronized(lock) {
        val key = key(book, index)
        record(book, index)?.let { manager.remove(it.getLong("id")) }
        prefs.edit().remove(key).apply()
    }

    fun clear() = synchronized(lock) {
        val limit = limitBytes
        prefs.all.values.filterIsInstance<String>().forEach { raw -> runCatching { manager.remove(JSONObject(raw).getLong("id")) } }
        prefs.edit().clear().putLong("limit", limit).apply()
        directory.listFiles().orEmpty().forEach { it.delete() }
    }

    private fun record(book: ListeningBook, index: Int): JSONObject? = prefs.getString(key(book, index), null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    private fun key(book: ListeningBook, index: Int): String = MessageDigest.getInstance("SHA-256").digest("${book.key}\u0000${book.episodes[index].url}".toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private val lock = Any()
    }
}
