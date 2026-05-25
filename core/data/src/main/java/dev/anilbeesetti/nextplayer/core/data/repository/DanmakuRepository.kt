package dev.anilbeesetti.nextplayer.core.data.repository

import android.net.Uri
import dev.anilbeesetti.nextplayer.core.model.AnimeMatch
import dev.anilbeesetti.nextplayer.core.model.DanmakuDownloadState
import dev.anilbeesetti.nextplayer.core.model.DanmakuSource
import dev.anilbeesetti.nextplayer.core.model.EpisodeInfo

/**
 * 弹幕数据仓库 — 管理 API 搜索、下载、缓存。
 */
interface DanmakuRepository {

    /**
     * 搜索动漫。
     * @param source 弹幕源
     * @param keyword 搜索关键词
     */
    suspend fun searchAnime(source: DanmakuSource, keyword: String): List<AnimeMatch>

    /**
     * 获取剧集列表。
     * @param source 弹幕源
     * @param animeId 动漫 ID
     */
    suspend fun getEpisodes(source: DanmakuSource, animeId: Int): List<EpisodeInfo>

    /**
     * 下载弹幕到本地缓存。
     * @param source 弹幕源
     * @param episodeId 剧集 ID
     * @return 本地缓存文件的 Uri，失败返回 null
     */
    suspend fun downloadAndCache(source: DanmakuSource, episodeId: Int): Uri?

    /**
     * 检查是否有缓存。
     */
    fun isCached(episodeId: Int): Boolean

    /**
     * 获取缓存 URI。
     */
    fun getCacheUri(episodeId: Int): Uri?

    /**
     * 清空缓存。
     */
    fun clearCache()
}
