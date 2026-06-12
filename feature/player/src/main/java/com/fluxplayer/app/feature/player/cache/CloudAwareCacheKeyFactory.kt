package com.fluxplayer.app.feature.player.cache

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory
import com.fluxplayer.app.core.common.CloudAwareCacheKeyRegistry

class CloudAwareCacheKeyFactory(
    private val registry: CloudAwareCacheKeyRegistry,
) : CacheKeyFactory {
    override fun buildCacheKey(dataSpec: DataSpec): String {
        val resolvedUrl = dataSpec.uri.toString()
        return registry.getOriginalUri(resolvedUrl) ?: resolvedUrl
    }
}
