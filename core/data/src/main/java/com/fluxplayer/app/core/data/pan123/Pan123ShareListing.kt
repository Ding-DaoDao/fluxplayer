package com.fluxplayer.app.core.data.pan123

data class Pan123ShareListing(
    val files: List<Pan123ShareFileItem>,
    val total: Int = files.size,
)
