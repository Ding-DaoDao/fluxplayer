package com.fluxplayer.app.core.data.danmaku

import android.util.Log
import java.io.InputStream

class PlatformDanmakuRouter(
    private val fetchers: List<PlatformDanmakuFetcher> = Companion.defaultFetchers(),
) {
    fun matchFetcher(url: String): PlatformDanmakuFetcher? {
        for (fetcher in fetchers) {
            if (fetcher.match(url)) {
                Log.d(TAG, "Matched ${fetcher.name} for $url")
                return fetcher
            }
        }
        Log.d(TAG, "No fetcher matched for $url")
        return null
    }

    suspend fun fetchDanmaku(url: String): InputStream? {
        val fetcher = matchFetcher(url) ?: return null
        return fetcher.fetchDanmaku(url)
    }

    fun allFetchers(): List<PlatformDanmakuFetcher> = fetchers.toList()

    fun getFetcherBySourceId(sourceId: String): PlatformDanmakuFetcher? {
        return fetchers.find { it.sourceId == sourceId }
    }

    companion object {
        private const val TAG = "PlatformDanmakuRouter"

        fun defaultFetchers(): List<PlatformDanmakuFetcher> = listOf(
            BilibiliDanmakuFetcher(),
            TencentDanmakuFetcher(),
            MgtvDanmakuFetcher(),
            YoukuDanmakuFetcher(),
            QiyiDanmakuFetcher(),
        )
    }
}
