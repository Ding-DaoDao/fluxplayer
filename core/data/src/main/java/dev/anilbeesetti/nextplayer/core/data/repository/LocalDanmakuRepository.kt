package dev.anilbeesetti.nextplayer.core.data.repository

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.anilbeesetti.nextplayer.core.data.danmaku.DanmakuApiClient
import dev.anilbeesetti.nextplayer.core.data.danmaku.DanmakuDownloadManager
import dev.anilbeesetti.nextplayer.core.model.AnimeMatch
import dev.anilbeesetti.nextplayer.core.model.DanmakuSource
import dev.anilbeesetti.nextplayer.core.model.EpisodeInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 弹幕仓库实现。
 */
@Singleton
class LocalDanmakuRepository @Inject constructor(
    @ApplicationContext context: Context,
) : DanmakuRepository {

    private val apiClient = DanmakuApiClient()
    private val downloadManager = DanmakuDownloadManager(context)

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

    override fun isCached(episodeId: Int): Boolean {
        return downloadManager.isCached(episodeId)
    }

    override fun getCacheUri(episodeId: Int): Uri? {
        return downloadManager.getCacheUri(episodeId)
    }

    override fun clearCache() {
        downloadManager.clearAll()
    }
}
