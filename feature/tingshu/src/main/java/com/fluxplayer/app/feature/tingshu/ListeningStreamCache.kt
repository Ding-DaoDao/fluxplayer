package com.fluxplayer.app.feature.tingshu

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.security.MessageDigest
import java.util.TreeSet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object ListeningStreamCache {
    private const val GB = 1024L * 1024 * 1024
    val capacities = listOf(GB / 2, GB, 2 * GB, 5 * GB, 10 * GB, 20 * GB, 50 * GB, Long.MAX_VALUE)
    private val enabledState = MutableStateFlow(true)
    private val limitState = MutableStateFlow(2 * GB)
    val enabled = enabledState.asStateFlow()
    val limit = limitState.asStateFlow()
    private var initialized = false

    @Volatile private var cacheUnavailable = false
    private var cache: SimpleCache? = null
    private var evictor: ResizableEvictor? = null

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        val preferences = context.getSharedPreferences("listening_stream_cache", Context.MODE_PRIVATE)
        enabledState.value = preferences.getBoolean("enabled", true)
        limitState.value = preferences.getLong("limit", 2 * GB).takeIf { it in capacities } ?: (2 * GB)
        initialized = true
    }

    @Synchronized
    fun setEnabled(context: Context, value: Boolean) {
        initialize(context)
        context.getSharedPreferences("listening_stream_cache", Context.MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
        enabledState.value = value
    }

    @Synchronized
    fun setLimit(context: Context, bytes: Long) {
        require(bytes in capacities)
        initialize(context)
        context.getSharedPreferences("listening_stream_cache", Context.MODE_PRIVATE).edit().putLong("limit", bytes).apply()
        limitState.value = bytes
        cache?.let { current ->
            // 与缓存回调使用同一把锁，调整容量时不关闭正在播放的数据源。
            synchronized(current) { evictor?.resize(current, bytes) }
        }
    }

    @Synchronized
    private fun cache(context: Context): SimpleCache {
        initialize(context)
        return cache ?: run {
            val policy = ResizableEvictor(limitState.value)
            SimpleCache(File(context.filesDir, "listening_stream_cache"), policy, StandaloneDatabaseProvider(context.applicationContext))
                .also {
                    evictor = policy
                    cache = it
                }
        }
    }

    fun size(context: Context): Long = cache(context).cacheSpace

    fun clear(context: Context) {
        val current = cache(context)
        // 保留缓存实例和文件锁，避免清理后播放线程继续访问已释放实例。
        synchronized(current) { current.keys.toList().forEach(current::removeResource) }
    }

    fun factory(context: Context, upstream: DataSource.Factory, namespace: String): DataSource.Factory {
        val appContext = context.applicationContext
        initialize(appContext)
        return DataSource.Factory {
            object : DataSource {
                private val listeners = mutableListOf<TransferListener>()
                private var delegate: DataSource? = null

                override fun addTransferListener(transferListener: TransferListener) {
                    listeners += transferListener
                    delegate?.addTransferListener(transferListener)
                }

                override fun open(dataSpec: DataSpec): Long {
                    val path = dataSpec.uri.path.orEmpty().lowercase()
                    // 清单随直播和鉴权更新，始终重新获取；分片和普通音频则按完整地址分别缓存。
                    val cacheable = enabledState.value && !cacheUnavailable && dataSpec.uri.scheme in setOf("http", "https") &&
                        dataSpec.httpMethod == DataSpec.HTTP_METHOD_GET && !path.endsWith(".m3u8") && !path.endsWith(".mpd")
                    val source = if (cacheable) {
                        try {
                            CacheDataSource.Factory().setCache(cache(appContext)).setUpstreamDataSourceFactory(upstream)
                                .setCacheKeyFactory { spec -> digest("$namespace\u0000${spec.key ?: spec.uri}") }
                                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR).createDataSource()
                        } catch (_: Exception) {
                            // 缓存不可用时仍允许直接播放。
                            cacheUnavailable = true
                            upstream.createDataSource()
                        }
                    } else {
                        upstream.createDataSource()
                    }
                    delegate = source
                    listeners.forEach(source::addTransferListener)
                    return try {
                        source.open(dataSpec)
                    } catch (error: Cache.CacheException) {
                        cacheUnavailable = true
                        runCatching { source.close() }
                        val fallback = upstream.createDataSource()
                        delegate = fallback
                        listeners.forEach(fallback::addTransferListener)
                        fallback.open(dataSpec)
                    }
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
                    checkNotNull(delegate).read(buffer, offset, length)
                } catch (error: Cache.CacheException) {
                    // 播放器重试时绕过故障缓存，避免反复读取同一个损坏的文件。
                    cacheUnavailable = true
                    throw error
                }
                override fun getUri(): Uri? = delegate?.uri
                override fun getResponseHeaders(): Map<String, List<String>> = delegate?.responseHeaders.orEmpty()
                override fun close() {
                    try {
                        delegate?.close()
                    } finally {
                        delegate = null
                    }
                }
            }
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private class ResizableEvictor(private var maxBytes: Long) : CacheEvictor {
        private val spans = TreeSet<CacheSpan>(compareBy<CacheSpan> { it.lastTouchTimestamp }.thenBy { it.key }.thenBy { it.position })
        private var bytes = 0L

        fun resize(cache: Cache, limit: Long) {
            maxBytes = limit
            trim(cache, 0)
        }

        override fun requiresCacheSpanTouches() = true
        override fun onCacheInitialized() = Unit
        override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
            if (length != C.LENGTH_UNSET.toLong()) trim(cache, length)
        }

        override fun onSpanAdded(cache: Cache, span: CacheSpan) {
            if (spans.add(span)) bytes += span.length
            trim(cache, 0)
        }

        override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
            if (spans.remove(span)) bytes -= span.length
        }

        override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
            onSpanRemoved(cache, oldSpan)
            onSpanAdded(cache, newSpan)
        }

        private fun trim(cache: Cache, incoming: Long) {
            while (spans.isNotEmpty() && bytes > maxBytes - incoming) cache.removeSpan(spans.first())
        }
    }
}
