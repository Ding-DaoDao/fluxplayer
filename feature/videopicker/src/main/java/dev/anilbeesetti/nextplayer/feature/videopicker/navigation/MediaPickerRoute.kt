package dev.anilbeesetti.nextplayer.feature.videopicker.navigation

import kotlinx.serialization.Serializable

@Serializable
data class MediaPickerRoute(
    val folderId: String? = null,
)
