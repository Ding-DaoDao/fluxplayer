package com.fluxplayer.app.core.ui.cache

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** 共享听书封面缓存，与视频缩略图和书源状态独立。 */
object BookCoverCache {
    const val LIMIT_BYTES = 128L * 1024 * 1024
    private val loaders = mutableMapOf<Boolean, ImageLoader>()
    private var state: MutableStateFlow<Boolean>? = null

    @Synchronized
    fun enabled(context: Context) = (state ?: MutableStateFlow(context.getSharedPreferences("book_cover_cache", Context.MODE_PRIVATE).getBoolean("enabled", true)).also { state = it }).asStateFlow()

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("book_cover_cache", Context.MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply()
        this.enabled(context)
        state!!.value = enabled
    }

    @Synchronized
    private fun disk(context: Context): DiskCache = com.fluxplayer.app.core.tingshu.ListeningCoverStore.disk(context)

    @Synchronized
    fun imageLoader(context: Context, enabled: Boolean = BookCoverCache.enabled(context).value): ImageLoader = loaders.getOrPut(enabled) {
        SingletonImageLoader.get(context).newBuilder()
            .diskCache(disk(context))
            .memoryCache(MemoryCache.Builder().maxSizeBytes(32L * 1024 * 1024).build())
            .diskCachePolicy(if (enabled) CachePolicy.ENABLED else CachePolicy.READ_ONLY)
            .build()
    }

    suspend fun size(context: Context): Long = withContext(Dispatchers.IO) { disk(context).size }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        disk(context).clear()
        synchronized(this@BookCoverCache) { loaders.values.forEach { it.memoryCache?.clear() } }
    }
}

@Composable
fun rememberBookCoverImageLoader(): ImageLoader {
    val context = LocalContext.current
    val enabled by BookCoverCache.enabled(context).collectAsStateWithLifecycle()
    return remember(context, enabled) { BookCoverCache.imageLoader(context, enabled) }
}
