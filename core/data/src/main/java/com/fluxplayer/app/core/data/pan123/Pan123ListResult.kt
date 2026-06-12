package com.fluxplayer.app.core.data.pan123

data class Pan123ListResult(
    val items: List<Pan123FileItem>,
    val nextCursor: String? // null = no more, "-1" = no more, other = next page cursor
)
