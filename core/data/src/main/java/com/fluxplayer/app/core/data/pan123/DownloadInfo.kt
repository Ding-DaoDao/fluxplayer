package com.fluxplayer.app.core.data.pan123

data class DownloadInfo(
    val url: String,
    val fileName: String = "",
    val size: Long = 0,
    val headers: Map<String, String> = emptyMap()
)
