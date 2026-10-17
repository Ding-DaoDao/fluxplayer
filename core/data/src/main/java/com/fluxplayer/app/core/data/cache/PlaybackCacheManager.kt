package com.fluxplayer.app.core.data.cache

import android.content.Context
import androidx.media3.common.C
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.TreeSet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var cache: SimpleCache? = null
    private var evictor: ResizablePlaybackCacheEvictor? = null

    private val databaseProvider by lazy { StandaloneDatabaseProvider(context) }

    val cacheDir: File
        get() = File(context.cacheDir, "playback_cache").also { it.mkdirs() }

    @Synchronized
    fun getCache(maxSizeBytes: Long): SimpleCache {
        require(maxSizeBytes > 0)
        cache?.let { existing ->
            synchronized(existing) { evictor?.resize(existing, maxSizeBytes) }
            return existing
        }
        val policy = ResizablePlaybackCacheEvictor(maxSizeBytes)
        return SimpleCache(cacheDir, policy, databaseProvider).also {
            evictor = policy
            cache = it
        }
    }

    @Synchronized
    fun clearCache() {
        val current = cache
        if (current == null) {
            SimpleCache.delete(cacheDir, databaseProvider)
        } else {
            // 保留数据源正在使用的实例和目录锁，只清除缓存内容。
            synchronized(current) { current.keys.toList().forEach(current::removeResource) }
        }
    }

    @Synchronized
    fun getCacheSize(): Long {
        cache?.let { return it.cacheSpace }
        if (!cacheDir.exists()) return 0
        return try {
            cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (e: Exception) {
            0
        }
    }

    @Synchronized
    fun release() {
        cache?.release()
        cache = null
        evictor = null
    }
}

/** 调整容量时沿用原有缓存实例，避免正在播放的数据源引用失效。 */
private class ResizablePlaybackCacheEvictor(private var maxBytes: Long) : CacheEvictor {
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
