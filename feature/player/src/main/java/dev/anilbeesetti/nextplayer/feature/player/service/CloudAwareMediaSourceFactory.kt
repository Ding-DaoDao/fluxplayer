package dev.anilbeesetti.nextplayer.feature.player.service

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import dev.anilbeesetti.nextplayer.core.common.CloudAwareCacheKeyRegistry
import dev.anilbeesetti.nextplayer.core.common.CloudUriScheme
import dev.anilbeesetti.nextplayer.core.data.cache.PlaybackCacheManager
import dev.anilbeesetti.nextplayer.core.data.cloud.CloudUriResolver

class CloudAwareMediaSourceFactory(
    private val authAwareFactory: DataSource.Factory,
    private val cloudUriResolver: CloudUriResolver,
    private val cacheKeyRegistry: CloudAwareCacheKeyRegistry? = null,
    private val playbackCacheManager: PlaybackCacheManager? = null,
) : MediaSource.Factory {

    private var cachedFactory = DefaultMediaSourceFactory(authAwareFactory)
    private val nonCachedFactory = DefaultMediaSourceFactory(authAwareFactory)
    private var storedDrmSessionManagerProvider: DrmSessionManagerProvider? = null
    private var storedLoadErrorHandlingPolicy: LoadErrorHandlingPolicy? = null
    private var simpleCache: SimpleCache? = null

    fun updateCacheSettings(maxSizeBytes: Long) {
        val manager = playbackCacheManager ?: return
        val cache: SimpleCache = manager.getCache(maxSizeBytes)
        simpleCache = cache
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(authAwareFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val registry = cacheKeyRegistry
        if (registry != null) {
            cacheDataSourceFactory.setCacheKeyFactory { spec ->
                val resolvedUrl = spec.uri.toString()
                val originalUri = registry.getOriginalUri(resolvedUrl)
                if (originalUri != null) {
                    // 复合缓存键 = cloud URI + URL 路径
                    // 不同画质的 URL 路径不同 → 缓存键不同 → 互不干扰
                    // 同画质跨会话 CDN 域名可能变但路径不变 → 缓存可复用
                    val urlPath = try {
                        Uri.parse(resolvedUrl).path ?: resolvedUrl
                    } catch (_: Exception) {
                        resolvedUrl
                    }
                    "$originalUri|$urlPath"
                } else {
                    spec.key ?: resolvedUrl
                }
            }
        }
        cachedFactory = DefaultMediaSourceFactory(cacheDataSourceFactory)
        storedDrmSessionManagerProvider?.let { cachedFactory.setDrmSessionManagerProvider(it) }
        storedLoadErrorHandlingPolicy?.let { cachedFactory.setLoadErrorHandlingPolicy(it) }
    }

    fun disableCache() {
        cachedFactory = nonCachedFactory
    }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration?.uri ?: Uri.EMPTY
        Log.d(TAG, "createMediaSource: uri=$uri, fragment=${uri.fragment}, mediaId=${mediaItem.mediaId}")

        // 云盘 URI (cloud://) 需要先解析为真实的 HTTP URL
        if (CloudUriScheme.isCloudUri(uri)) {
            val resolvedUri = kotlinx.coroutines.runBlocking {
                cloudUriResolver.resolve(uri)
            }
            if (resolvedUri == null) {
                Log.e(TAG, "Failed to resolve cloud URI: $uri, falling back to default")
                return nonCachedFactory.createMediaSource(mediaItem)
            }
            Log.d(TAG, "Resolved $uri -> $resolvedUri")

            // 注册缓存键映射（解析后 URL -> 原始 cloud URI）
            cacheKeyRegistry?.register(resolvedUri.toString(), uri.toString())

            val resolvedMediaItem = mediaItem.buildUpon()
                .setUri(resolvedUri)
                .setMediaId(mediaItem.mediaId)
                .build()
            return cachedFactory.createMediaSource(resolvedMediaItem)
        }

        // 非云盘 URI：本地文件使用 nonCachedFactory，网络文件使用 cachedFactory
        val factory = if (uri.scheme in listOf("http", "https")) {
            // 如果 mediaId 是云盘 URI，注册缓存键映射
            cacheKeyRegistry?.let { registry ->
                val mediaIdUri = Uri.parse(mediaItem.mediaId)
                if (CloudUriScheme.isCloudUri(mediaIdUri)) {
                    registry.register(uri.toString(), mediaItem.mediaId)
                }
            }
            cachedFactory
        } else {
            nonCachedFactory
        }
        return factory.createMediaSource(mediaItem)
    }

    override fun getSupportedTypes(): IntArray {
        return cachedFactory.supportedTypes
    }

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        storedDrmSessionManagerProvider = drmSessionManagerProvider
        cachedFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
        nonCachedFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        storedLoadErrorHandlingPolicy = loadErrorHandlingPolicy
        cachedFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        nonCachedFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    companion object {
        private const val TAG = "CloudAwareMediaSourceFactory"
    }
}
