package com.fluxplayer.app.core.model

data class EpisodeInfo(
    val episodeId: Int,
    val animeId: Int,
    val title: String,
    val episodeNumber: Int = 0,
    val url: String? = null,
)
