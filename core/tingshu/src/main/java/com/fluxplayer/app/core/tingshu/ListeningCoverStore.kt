package com.fluxplayer.app.core.tingshu

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import coil3.disk.DiskCache
import coil3.disk.directory
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import org.json.JSONObject

data class ListeningCoverExport(
    val files: Map<String, ByteArray>,
    val warnings: List<String>,
)

/** 封面缓存与可迁移的图片文件共用标识，恢复后无需访问书源即可显示封面。 */
object ListeningCoverStore {
    const val CACHE_LIMIT_BYTES = 1024L * 1024 * 1024
    val cacheLimits = listOf(256L, 512L, 1024L, 2048L, 4096L).map { it * 1024 * 1024 }
    private var limitState: MutableStateFlow<Long>? = null
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    private const val MAX_BACKUP_BYTES = 24 * 1024 * 1024
    private var cache: DiskCache? = null
    private val revisions = MutableStateFlow(0L)
    val revision = revisions.asStateFlow()
    private val client = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

    @Synchronized
    fun configuredLimit(context: Context) = (
        limitState ?: MutableStateFlow(
            context.applicationContext.getSharedPreferences("book_cover_cache", Context.MODE_PRIVATE)
                .getLong("limit_bytes", CACHE_LIMIT_BYTES).takeIf { it in cacheLimits } ?: CACHE_LIMIT_BYTES,
        ).also { limitState = it }
        ).asStateFlow()

    fun setLimit(context: Context, bytes: Long) {
        require(bytes in cacheLimits) { "不支持的封面缓存容量" }
        context.applicationContext.getSharedPreferences("book_cover_cache", Context.MODE_PRIVATE).edit().putLong("limit_bytes", bytes).apply()
        configuredLimit(context)
        limitState!!.value = bytes
    }

    @Synchronized
    fun disk(context: Context): DiskCache = cache ?: DiskCache.Builder()
        .directory(context.applicationContext.filesDir.resolve("book-covers"))
        .maxSizeBytes(configuredLimit(context).value).build().also { cache = it }

    fun fileName(sourceId: String, url: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$sourceId:$url".toByteArray()).joinToString("") { "%02x".format(it) } + ".img"

    fun restoredFile(context: Context, sourceId: String, url: String): File? =
        context.filesDir.resolve("listening-cover-backups").resolve(fileName(sourceId, url)).takeIf { it.isFile && it.length() > 0 }

    suspend fun export(context: Context, library: String?, repository: TingshuRepository): ListeningCoverExport = withContext(Dispatchers.IO) {
        if (library == null) return@withContext ListeningCoverExport(emptyMap(), emptyList())
        val books = JSONObject(library).getJSONObject("books")
        val result = linkedMapOf<String, ByteArray>()
        val warnings = mutableListOf<String>()
        val attempted = mutableSetOf<String>()
        var total = 0L
        for (key in books.keys()) {
            val book = books.getJSONObject(key)
            val sourceId = book.getString("sourceId")
            val url = book.optString("coverUrl")
            if (url.isBlank()) continue
            val name = fileName(sourceId, url)
            if (!attempted.add(name)) continue
            try {
                val bytes = exportImage(context, sourceId, url, repository)
                require(total + bytes.size <= MAX_BACKUP_BYTES) { "封面合计超过 24 MB" }
                total += bytes.size
                result[name] = bytes
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                warnings += "${book.optString("title").ifBlank { "未命名书籍" }}：${error.message ?: "封面无法读取"}"
            }
        }
        ListeningCoverExport(result, warnings)
    }

    /** 播放通知与备份共用图片解析，支持书源内部引用和请求鉴权。 */
    suspend fun imageBytes(context: Context, sourceId: String, url: String, repository: TingshuRepository): ByteArray =
        withContext(Dispatchers.IO) { exportImage(context, sourceId, url, repository) }

    /** 各种图片来源独立尝试，坏缓存和不可用书源不能阻止其他来源回退。 */
    private suspend fun exportImage(context: Context, sourceId: String, url: String, repository: TingshuRepository): ByteArray {
        var failure: Exception? = null
        suspend fun attempt(block: suspend () -> ByteArray?): ByteArray? = try {
            block()?.also { bytes ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "封面不是有效图片" }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failure = error
            null
        }
        attempt { restoredFile(context, sourceId, url)?.inputStream()?.use(::readImage) }?.let { return it }
        attempt {
            val cache = disk(context)
            cache.openSnapshot("$sourceId:$url")?.use { cache.fileSystem.source(it.data).buffer().inputStream().use(::readImage) }
        }?.let { return it }
        attempt { repository.resolveCover(sourceId, url)?.let { load(context, it) } }?.let { return it }
        // 普通图片地址可以直接读取；脚本引用仍必须由可用书源解析。
        if (Uri.parse(url).scheme?.lowercase() in setOf("http", "https", "file", "content", "data")) {
            attempt { load(context, ListeningResource(url, emptyMap())) }?.let { return it }
        }
        throw failure ?: IllegalStateException("没有可读取的封面缓存或图片地址")
    }

    private fun load(context: Context, resource: ListeningResource): ByteArray {
        val uri = Uri.parse(resource.url)
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> client.newCall(Request.Builder().url(resource.url).apply { resource.headers.forEach { (key, value) -> header(key, value) } }.build()).execute().use {
                check(it.isSuccessful) { "封面下载失败：HTTP ${it.code}" }
                checkNotNull(it.body) { "封面响应为空" }.byteStream().use(::readImage)
            }
            "content", "file" -> checkNotNull(context.contentResolver.openInputStream(uri)) { "无法读取封面文件" }.use(::readImage)
            "data" -> {
                val payload = resource.url.substringAfter(',', "")
                val bytes = if (resource.url.substringBefore(',').contains(";base64")) Base64.decode(payload, Base64.DEFAULT) else Uri.decode(payload).toByteArray()
                require(bytes.size <= MAX_IMAGE_BYTES) { "单张封面超过 4 MB" }
                bytes
            }
            else -> error("不支持的封面地址")
        }
    }

    fun validateName(name: String) {
        require(name.matches(Regex("[a-f0-9]{64}\\.img"))) { "备份封面文件名无效" }
    }

    suspend fun restore(context: Context, files: Map<String, ByteArray>) = withContext(Dispatchers.IO) {
        require(files.values.sumOf { it.size.toLong() } <= MAX_BACKUP_BYTES) { "备份封面总大小超过 24 MB" }
        files.forEach { (name, bytes) ->
            validateName(name)
            require(bytes.size <= MAX_IMAGE_BYTES) { "备份封面过大" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "备份封面不是有效图片" }
        }
        val directory = context.filesDir.resolve("listening-cover-backups")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建封面目录" }
        files.forEach { (name, bytes) ->
            val target = directory.resolve(name)
            val temporary = File.createTempFile("cover-", ".tmp", directory)
            try {
                temporary.writeBytes(bytes)
                check(temporary.renameTo(target)) { "恢复封面失败" }
            } finally {
                temporary.delete()
            }
        }
        revisions.value += 1
    }

    private fun readImage(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_IMAGE_BYTES) { "单张封面超过 4 MB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
