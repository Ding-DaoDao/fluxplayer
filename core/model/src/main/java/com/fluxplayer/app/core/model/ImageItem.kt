package com.fluxplayer.app.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ImageItem(
    val name: String,
    val path: String,
    val url: String,
)
