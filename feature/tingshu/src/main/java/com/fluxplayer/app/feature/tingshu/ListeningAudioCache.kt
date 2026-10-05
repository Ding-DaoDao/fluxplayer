package com.fluxplayer.app.feature.tingshu

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.dash.offline.DashDownloader
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import com.fluxplayer.app.core.tingshu.ListeningBook
import com.fluxplayer.app.core.tingshu.ListeningResource
import com.fluxplayer.app.core.tingshu.TingshuRepository
import java.io.File
import java.util.TreeSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class AudioCacheState(
    val usedBytes: Long = 0,
    val automatic: Boolean = true,
    val limitMb: Int = 512,
    val completedChapters: Int = 0,
    val download: String? = null,
    val percent: Float? = null,
    val message: String? = null,
)

/** One shared Media3 cache for online listening, complete downloads and offline playback. */
@OptIn(UnstableApi::class)
internal class ListeningAudioCache private constructor(context: Context) {
    private val context = context.applicationContext
    private val repository = TingshuRepository.get(context)
    private val prefs = context.getSharedPreferences("listening_audio_cache", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(AudioCacheState(limitMb = prefs.getInt("limitMb", 512), automatic = prefs.getBoolean("automatic", true)))
    val state = mutableState.asStateFlow()
    private val evictor = ResizableEvictor(mutableState.value.limitMb * MB)
    private val cache = SimpleCache(File(context.filesDir, "tingshu/audio-cache"), evictor, StandaloneDatabaseProvider(context))

    @Volatile private var downloadJob: Job? = null
    private var clearing = false

    init {
        scope.launch {
            while (true) {
                refresh()
                delay(1500)
            }
        }
    }

    fun dataSource(book: ListeningBook, index: Int, resource: ListeningResource, offline: Boolean = false): CacheDataSource.Factory =
        cacheFactory(book, index, resource, offline)

    private fun cacheFactory(
        book: ListeningBook,
        index: Int,
        resource: ListeningResource,
        offline: Boolean,
        requests: MutableSet<RequestRange>? = null,
        forceWrite: Boolean = false,
    ): CacheDataSource.Factory {
        if (!offline && (forceWrite || mutableState.value.automatic) && Util.inferContentType(Uri.parse(resource.url)) == C.CONTENT_TYPE_OTHER) {
            prefs.edit().putString("stream:" + chapterKey(book, index), resource.url).apply()
        }
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(resource.headers)
            .setConnectTimeoutMs(20_000).setReadTimeoutMs(20_000).setAllowCrossProtocolRedirects(true)
        val upstream = ResolvingDataSource.Factory(http) { spec ->
            val headers = runBlocking { repository.playbackHeaders(book.sourceId, spec.uri.toString()) }
            spec.withRequestHeaders(resource.headers + spec.httpRequestHeaders + headers)
        }
        return CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory(if (offline) null else upstream)
            .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
            .apply { if (!forceWrite && !mutableState.value.automatic) setCacheWriteDataSinkFactory(null) }
            .setCacheKeyFactory { spec ->
                val key = if (spec.uri.toString() == resource.url) chapterKey(book, index) else spec.key ?: spec.uri.toString()
                requests?.let { synchronized(it) { it.add(RequestRange(key, spec.position, spec.length)) } }
                key
            }
    }

    suspend fun offlineResource(book: ListeningBook, index: Int): ListeningResource? = withContext(Dispatchers.IO) {
        if (index !in book.episodes.indices) return@withContext null
        val key = chapterKey(book, index)
        val raw = prefs.getString(key, null)
        if (raw != null) {
            val record = JSONObject(raw)
            if (ready(record)) return@withContext ListeningResource(record.getString("url"), emptyMap())
            prefs.edit().remove(key).apply()
        }
        val url = prefs.getString("stream:$key", null) ?: return@withContext null
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        if (length > 0 && cache.isCached(key, 0, length)) ListeningResource(url, emptyMap()) else null
    }

    @Synchronized
    fun download(book: ListeningBook, index: Int, wholeBook: Boolean = false) {
        if (clearing || downloadJob?.isActive == true) return
        val indices = if (wholeBook) book.episodes.indices.toList() else listOf(index)
        require(indices.all { it in book.episodes.indices })
        mutableState.update { it.copy(download = book.title, percent = null, message = null) }
        downloadJob = scope.launch {
            try {
                val completed = mutableListOf<Int>()
                indices.forEachIndexed { number, chapter ->
                    mutableState.update { it.copy(download = "${book.title} · ${book.episodes[chapter].title}") }
                    downloadChapter(book, chapter) { percent ->
                        mutableState.update { it.copy(percent = percent?.let { (number * 100f + it) / indices.size }) }
                    }
                    completed.add(chapter)
                    if (wholeBook && completed.any { offlineResource(book, it) == null }) error("缓存上限不足以保存整本书，请提高上限后重试")
                }
                mutableState.update { it.copy(message = if (wholeBook) "整本书已缓存" else "本章已缓存，可离线播放") }
            } catch (e: CancellationException) {
                mutableState.update { it.copy(message = "缓存任务已取消") }
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(message = e.message ?: "缓存失败") }
            } finally {
                mutableState.update { it.copy(download = null, percent = null) }
                refresh()
            }
        }
    }

    private suspend fun downloadChapter(book: ListeningBook, index: Int, progress: (Float?) -> Unit) {
        if (offlineResource(book, index) != null) {
            progress(100f)
            return
        }
        val resource = repository.resolve(book, index)
        val requests = mutableSetOf<RequestRange>()
        val factory = cacheFactory(book, index, resource, false, requests, forceWrite = true)
        val type = Util.inferContentType(Uri.parse(resource.url))
        val item = MediaItem.fromUri(resource.url)
        val downloader: Downloader = when (type) {
            C.CONTENT_TYPE_HLS -> HlsDownloader(item, factory)
            C.CONTENT_TYPE_DASH -> DashDownloader(item, factory)
            else -> ProgressiveDownloader(item, factory)
        }
        try {
            runInterruptible(Dispatchers.IO) {
                downloader.download { _, _, percent -> progress(percent.takeIf { it >= 0 }) }
            }
        } finally {
            downloader.cancel()
        }
        val ranges = JSONArray()
        synchronized(requests) {
            requests.forEach { request ->
                val length = if (request.length == C.LENGTH_UNSET.toLong()) ContentMetadata.getContentLength(cache.getContentMetadata(request.key)) - request.position else request.length
                require(length >= 0 && cache.isCached(request.key, request.position, length)) { "缓存空间不足或音频未完整缓存" }
                ranges.put(JSONObject().put("key", request.key).put("position", request.position).put("length", length))
            }
        }
        require(ranges.length() > 0) { "没有获取到可缓存的音频" }
        val record = JSONObject().put("url", resource.url).put("ranges", ranges)
        check(prefs.edit().putString(chapterKey(book, index), record.toString()).commit())
    }

    fun setAutomatic(enabled: Boolean) {
        prefs.edit().putBoolean("automatic", enabled).apply()
        mutableState.update { it.copy(automatic = enabled) }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    suspend fun setLimit(mb: Int) = withContext(Dispatchers.IO) {
        require(mb in 64..4096)
        synchronized(cache) { evictor.resize(cache, mb * MB) }
        prefs.edit().putInt("limitMb", mb).apply()
        mutableState.update { it.copy(limitMb = mb) }
        refresh()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(this@ListeningAudioCache) {
            if (clearing) return@withContext
            clearing = true
        }
        try {
            downloadJob?.cancelAndJoin()
            cache.keys.toList().forEach(cache::removeResource)
            val edit = prefs.edit()
            prefs.all.keys.filter { it.startsWith("chapter:") || it.startsWith("stream:") }.forEach(edit::remove)
            check(edit.commit())
            mutableState.update { it.copy(message = "音频缓存已清除") }
            refresh()
        } finally {
            synchronized(this@ListeningAudioCache) { clearing = false }
        }
    }

    private fun ready(record: JSONObject): Boolean = runCatching {
        val ranges = record.getJSONArray("ranges")
        ranges.length() > 0 && (0 until ranges.length()).all { i ->
            val range = ranges.getJSONObject(i)
            cache.isCached(range.getString("key"), range.getLong("position"), range.getLong("length"))
        }
    }.getOrDefault(false)

    private fun refresh() {
        val complete = prefs.all.filterKeys { it.startsWith("chapter:") }.filterValues { raw ->
            runCatching { ready(JSONObject(raw as String)) }.getOrDefault(false)
        }.keys.toMutableSet()
        prefs.all.keys.filter { it.startsWith("stream:") }.forEach { key ->
            val chapter = key.removePrefix("stream:")
            val length = ContentMetadata.getContentLength(cache.getContentMetadata(chapter))
            if (length > 0 && cache.isCached(chapter, 0, length)) complete.add(chapter)
        }
        val count = complete.size
        mutableState.update { it.copy(usedBytes = cache.cacheSpace, completedChapters = count) }
    }

    private data class RequestRange(val key: String, val position: Long, val length: Long)

    private class ResizableEvictor(private var limit: Long) : CacheEvictor {
        private var bytes = 0L
        private val spans = TreeSet<CacheSpan>(compareBy<CacheSpan> { it.lastTouchTimestamp }.thenBy { it.key }.thenBy { it.position })
        override fun requiresCacheSpanTouches(): Boolean = true
        override fun onCacheInitialized() = Unit
        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
            evict(cache, length.coerceAtLeast(0))
        }
        override fun onSpanAdded(cache: Cache, span: CacheSpan) {
            if (spans.add(span)) bytes += span.length
            evict(cache, 0)
        }
        override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
            if (spans.remove(span)) bytes -= span.length
        }
        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
            onSpanRemoved(cache, oldSpan)
            onSpanAdded(cache, newSpan)
        }
        fun resize(cache: Cache, bytes: Long) {
            limit = bytes
            evict(cache, 0)
        }
        private fun evict(cache: Cache, incoming: Long) {
            while (bytes + incoming > limit && spans.isNotEmpty()) cache.removeSpan(spans.first())
        }
    }

    companion object {
        private const val MB = 1024L * 1024

        @Volatile private var instance: ListeningAudioCache? = null
        fun get(context: Context): ListeningAudioCache = instance ?: synchronized(this) {
            instance ?: ListeningAudioCache(context).also { instance = it }
        }
        private fun chapterKey(book: ListeningBook, index: Int): String {
            val id = java.security.MessageDigest.getInstance("SHA-256").digest(book.episodes[index].url.toByteArray())
                .joinToString("") { "%02x".format(it) }
            return "chapter:${book.key}:$id"
        }
    }
}
