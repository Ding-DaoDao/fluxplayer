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
        // PLATFORM 源走独立 fetcher（B站、腾讯、芒果等），通用 API 源走 DanmakuApiClient
        if (DanmakuSource.isPlatformSource(source)) {
            return searchPlatformAnime(keyword, source)
        }
        return apiClient.searchAnime(source, keyword)
    }

    override suspend fun getEpisodes(source: DanmakuSource, anime: AnimeMatch): List<EpisodeInfo> {
        if (DanmakuSource.isPlatformSource(source)) {
            val fetcher = getPlatformFetcherBySource(source) ?: return emptyList()
            Log.d(TAG, "getEpisodes: routing to ${fetcher.name} animeId=${anime.animeId} url=${anime.url} title=${anime.title}")
            val result = fetcher.getEpisodes(anime)
            Log.d(TAG, "getEpisodes: ${fetcher.name} returned ${result.size} episodes")
            return result
        }
        return apiClient.getEpisodes(source, anime.animeId)
    }

    override suspend fun downloadAndCache(source: DanmakuSource, episode: EpisodeInfo): Uri? {
        val episodeId = episode.episodeId

        // 检查缓存
        if (downloadManager.isCached(episodeId)) {
            return downloadManager.getCacheUri(episodeId)
        }

        // PLATFORM 源走独立 fetcher：需要用视频页面 URL 下载，而非 API episodeId
        val inputStream = if (DanmakuSource.isPlatformSource(source)) {
            val fetcher = getPlatformFetcherBySource(source) ?: return null
            val videoUrl = buildPlatformEpisodeUrl(source, episode)
            Log.d(TAG, "downloadAndCache: platform source -> fetcher=${fetcher.name} url=$videoUrl")
            fetcher.fetchDanmaku(videoUrl)
        } else {
            apiClient.downloadDanmaku(source, episodeId)
        } ?: return null

        // 保存到缓存
        val file = downloadManager.saveFromStream(episodeId, inputStream)
        inputStream.close()

        return Uri.fromFile(file)
    }

    /**
     * 为平台源构造视频页面 URL，供 fetcher.fetchDanmaku 使用。
     * 优先使用 EpisodeInfo.url（API 返回的链接），否则根据 sourceId 推断。
     */
    private fun buildPlatformEpisodeUrl(source: DanmakuSource, episode: EpisodeInfo): String {
        // 优先用 API 返回的完整 URL
        val apiUrl = episode.url
        if (!apiUrl.isNullOrBlank() && (apiUrl.startsWith("http://") || apiUrl.startsWith("https://"))) {
            return apiUrl
        }

        // 根据平台类型构造 URL
        val sourceId = source.id.removePrefix("platform:")
        return when (sourceId) {
            "bilibili" -> "https://www.bilibili.com/bangumi/play/ep${episode.episodeId}"
            "tencent" -> "https://v.qq.com/x/cover/mzc00200.html"
            else -> {
                Log.w(TAG, "Cannot build URL for platform=$sourceId, url=${episode.url}")
                if (!apiUrl.isNullOrBlank()) "https://$apiUrl" else ""
            }
        }
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
