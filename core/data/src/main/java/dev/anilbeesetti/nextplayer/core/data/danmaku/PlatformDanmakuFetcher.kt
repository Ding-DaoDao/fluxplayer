package dev.anilbeesetti.nextplayer.core.data.danmaku

import dev.anilbeesetti.nextplayer.core.model.AnimeMatch
import dev.anilbeesetti.nextplayer.core.model.EpisodeInfo
import java.io.InputStream

interface PlatformDanmakuFetcher {
    val name: String
    val sourceId: String
    fun match(url: String): Boolean
    suspend fun fetchDanmaku(url: String): InputStream?
    suspend fun search(keyword: String): List<AnimeMatch> = emptyList()
    suspend fun getEpisodes(anime: AnimeMatch): List<EpisodeInfo> = emptyList()
}
