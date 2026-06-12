package com.fluxplayer.app.core.model

data class AnimeMatch(
    val animeId: Int,
    val title: String,
    val type: String = "",
    val summary: String = "",
    val episodeCount: Int = 0,
    val url: String? = null,
)
