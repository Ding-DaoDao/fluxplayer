package dev.anilbeesetti.nextplayer.core.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.anilbeesetti.nextplayer.core.data.danmaku.DanmakuApiClient
import dev.anilbeesetti.nextplayer.core.data.danmaku.DanmakuDownloadManager
import dev.anilbeesetti.nextplayer.core.data.danmaku.PlatformDanmakuFetcher
import dev.anilbeesetti.nextplayer.core.data.danmaku.PlatformDanmakuRouter
import dev.anilbeesetti.nextplayer.core.model.AnimeMatch
import dev.anilbeesetti.nextplayer.core.model.DanmakuSource
import dev.anilbeesetti.nextplayer.core.model.EpisodeInfo
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LocalDanmakuRepository"

/**
 * 弹幕仓库实现。
 */
@Singleton
class LocalDanmakuRepository @Inject constructor(
    @ApplicationContext context: Context,
) : DanmakuRepository {

    private val apiClient = DanmakuApiClient()
    private val downloadManager = DanmakuDownloadManager(context)
    private val platformRouter = PlatformDanmakuRouter()

    override suspend fun searchAnime(source: DanmakuSource, keyword: String): List<AnimeMatch> {
        return apiClient.searchAnime(source, keyword)
    }

    override suspend fun getEpisodes(source: DanmakuSource, animeId: Int): List<EpisodeInfo> {
        return apiClient.getEpisodes(source, animeId)
    }

    override suspend fun downloadAndCache(source: DanmakuSource, episodeId: Int): Uri? {
        // 检查缓存
        if (downloadManager.isCached(episodeId)) {
            return downloadManager.getCacheUri(episodeId)
        }

        // 下载
        val inputStream = apiClient.downloadDanmaku(source, episodeId) ?: return null

        // 保存到缓存
        val file = downloadManager.saveFromStream(episodeId, inputStream)
        inputStream.close()

        return Uri.fromFile(file)
    }

    override suspend fun fetchDanmakuByUrl(videoUrl: String): Uri? {
        val fetcher = platformRouter.matchFetcher(videoUrl)
        if (fetcher == null) {
            Log.w(TAG, "No fetcher matched for URL: $videoUrl")
            return null
        }
        Log.d(TAG, "Fetching danmaku via ${fetcher.name} for $videoUrl")
        val inputStream = fetcher.fetchDanmaku(videoUrl) ?: return null
        val cacheKey = generateCacheKey(videoUrl)
        return cacheDanmakuStream(inputStream, cacheKey)
    }

    override fun matchPlatformFetcher(videoUrl: String): PlatformDanmakuFetcher? {
        return platformRouter.matchFetcher(videoUrl)
    }

    override fun getAllPlatformFetchers(): List<PlatformDanmakuFetcher> {
        return platformRouter.allFetchers()
    }

    override suspend fun searchPlatformAnime(keyword: String, source: DanmakuSource): List<AnimeMatch> {
        val fetcher = getPlatformFetcherBySource(source) ?: return emptyList()
        return fetcher.search(keyword)
    }

    override fun getPlatformFetcherBySource(source: DanmakuSource): PlatformDanmakuFetcher? {
        val sourceId = source.id.removePrefix("platform:")
        return platformRouter.getFetcherBySourceId(sourceId)
    }

    override suspend fun cacheDanmakuStream(inputStream: InputStream, cacheKey: String): Uri {
        val bytes = inputStream.readBytes()
        inputStream.close()
        val episodeId = cacheKey.hashCode()
        val file = downloadManager.saveToCache(episodeId, bytes)
        return Uri.fromFile(file)
    }

    override fun isCached(episodeId: Int): Boolean {
        return downloadManager.isCached(episodeId)
    }

    override fun getCacheUri(episodeId: Int): Uri? {
        return downloadManager.getCacheUri(episodeId)
    }

    override fun clearCache() {
        downloadManager.clearAll()
    }

    private fun generateCacheKey(url: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(url.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }
}
