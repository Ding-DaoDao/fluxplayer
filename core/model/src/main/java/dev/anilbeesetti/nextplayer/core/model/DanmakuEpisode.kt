package dev.anilbeesetti.nextplayer.core.model

/**
 * 动漫搜索结果 —— 来自弹幕 API 的搜索匹配结果。
 */
data class AnimeMatch(
    val animeId: Int,
    val title: String,
    val type: String = "",       // 类型：TV/剧场版/OVA
    val summary: String = "",
    val episodeCount: Int = 0,
)

/**
 * 剧集信息 —— 一个具体的分集。
 */
data class EpisodeInfo(
    val episodeId: Int,
    val animeId: Int,
    val title: String,
    val episodeNumber: Int = 0,
)

/**
 * 弹幕下载任务的状态。
 */
sealed interface DanmakuDownloadState {
    data object Idle : DanmakuDownloadState
    data object Searching : DanmakuDownloadState
    data class SearchResult(val animeList: List<AnimeMatch>) : DanmakuDownloadState
    data class AnimeSelected(val anime: AnimeMatch, val episodes: List<EpisodeInfo>) : DanmakuDownloadState
    data object Downloading : DanmakuDownloadState
    data class Ready(val localPath: String) : DanmakuDownloadState
    data class Error(val message: String) : DanmakuDownloadState
}
