package com.fluxplayer.app.core.model

sealed interface DanmakuDownloadState {
    data object Idle : DanmakuDownloadState

    data class Searching(
        val source: DanmakuSource? = null,
    ) : DanmakuDownloadState

    data class SearchResult(
        val animeList: List<AnimeMatch>,
        val source: DanmakuSource? = null,
    ) : DanmakuDownloadState

    data class AnimeSelected(
        val anime: AnimeMatch,
        val episodes: List<EpisodeInfo>,
        val currentEpisode: EpisodeInfo? = null,
        val source: DanmakuSource? = null,
    ) : DanmakuDownloadState

    data class Downloading(
        val source: DanmakuSource? = null,
    ) : DanmakuDownloadState

    data class Ready(
        val localPath: String,
    ) : DanmakuDownloadState

    data class Error(
        val message: String,
        val source: DanmakuSource? = null,
    ) : DanmakuDownloadState
}
