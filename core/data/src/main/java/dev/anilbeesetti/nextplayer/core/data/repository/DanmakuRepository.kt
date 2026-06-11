package dev.anilbeesetti.nextplayer.core.data.repository

import android.net.Uri
import dev.anilbeesetti.nextplayer.core.data.danmaku.PlatformDanmakuFetcher
import dev.anilbeesetti.nextplayer.core.model.AnimeMatch
import dev.anilbeesetti.nextplayer.core.model.DanmakuSource
import dev.anilbeesetti.nextplayer.core.model.EpisodeInfo
import java.io.InputStream

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
     * @param anime 动漫搜索结果（包含 animeId、url 等，平台源需要 url 来构造 API 请求）
     */
    suspend fun getEpisodes(source: DanmakuSource, anime: AnimeMatch): List<EpisodeInfo>

    /**
     * 下载弹幕到本地缓存。
     * @param source 弹幕源
     * @param episode 剧集信息（包含 episodeId、url 等）
     * @return 本地缓存文件的 Uri，失败返回 null
     */
    suspend fun downloadAndCache(source: DanmakuSource, episode: EpisodeInfo): Uri?

    /**
     * 通过视频 URL 抓取弹幕（平台源自动匹配 fetcher）。
     * @param videoUrl 视频播放地址
     * @return 本地缓存文件的 Uri，失败返回 null
     */
    suspend fun fetchDanmakuByUrl(videoUrl: String): Uri?

    /**
     * 根据视频 URL 匹配对应的平台弹幕抓取器。
     */
    fun matchPlatformFetcher(videoUrl: String): PlatformDanmakuFetcher?

    /**
     * 获取所有平台弹幕抓取器。
     */
    fun getAllPlatformFetchers(): List<PlatformDanmakuFetcher>

    /**
     * 通过平台源搜索动漫。
     */
    suspend fun searchPlatformAnime(keyword: String, source: DanmakuSource): List<AnimeMatch>

    /**
     * 根据弹幕源获取对应的平台抓取器。
     */
    fun getPlatformFetcherBySource(source: DanmakuSource): PlatformDanmakuFetcher?

    /**
     * 将弹幕流缓存到本地。
     * @param inputStream 弹幕数据流
     * @param cacheKey 缓存键
     * @return 本地缓存文件的 Uri
     */
    suspend fun cacheDanmakuStream(inputStream: InputStream, cacheKey: String): Uri

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
