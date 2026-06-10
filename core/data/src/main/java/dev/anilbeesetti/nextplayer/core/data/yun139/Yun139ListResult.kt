package dev.anilbeesetti.nextplayer.core.data.yun139

data class Yun139ListResult(
    val items: List<Yun139FileItem>,
    val totalCount: Int,
    val nextMarker: String = ""
)
