package com.fluxplayer.app.core.data.cache

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackCacheManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var cache: SimpleCache? = null
    private var cacheMaxSize: Long = -1

    private val databaseProvider by lazy { StandaloneDatabaseProvider(context) }

    val cacheDir: File
        get() = File(context.cacheDir, "playback_cache").also { it.mkdirs() }

    fun getCache(maxSizeBytes: Long): SimpleCache {
        cache?.let { existing ->
            if (cacheMaxSize == maxSizeBytes) return existing
            existing.release()
        }
        // Evict old cache if disk is insufficient (leave at least equal space to requested size)
        if (cacheDir.exists() && cacheDir.usableSpace < maxSizeBytes) {
            clearCache()
        }
        val newCache = createCache(maxSizeBytes)
        cache = newCache
        cacheMaxSize = maxSizeBytes
        return newCache
    }

    private fun createCache(maxSizeBytes: Long): SimpleCache {
        val evictor = if (maxSizeBytes == Long.MAX_VALUE) {
            NoOpCacheEvictor()
        } else {
            LeastRecentlyUsedCacheEvictor(maxSizeBytes)
        }
        return SimpleCache(cacheDir, evictor, databaseProvider)
    }

    fun clearCache() {
        cache?.release()
        cache = null
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
        }
    }

    fun getCacheSize(): Long {
        cache?.let { return it.cacheSpace }
        if (!cacheDir.exists()) return 0
        return try {
            cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (e: Exception) {
            0
        }
    }

    fun release() {
        cache?.release()
        cache = null
    }
}
