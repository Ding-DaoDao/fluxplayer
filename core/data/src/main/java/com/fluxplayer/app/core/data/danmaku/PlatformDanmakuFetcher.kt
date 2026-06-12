package com.fluxplayer.app.core.data.danmaku

import com.fluxplayer.app.core.model.AnimeMatch
import com.fluxplayer.app.core.model.EpisodeInfo
import java.io.InputStream

interface PlatformDanmakuFetcher {
    val name: String
    val sourceId: String
    fun match(url: String): Boolean
    suspend fun fetchDanmaku(url: String): InputStream?
    suspend fun search(keyword: String): List<AnimeMatch> = emptyList()
    suspend fun getEpisodes(anime: AnimeMatch): List<EpisodeInfo> = emptyList()
}
