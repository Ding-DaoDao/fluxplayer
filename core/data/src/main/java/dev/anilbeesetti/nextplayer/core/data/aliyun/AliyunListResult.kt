package dev.anilbeesetti.nextplayer.core.data.aliyun

data class AliyunListResult(
    val items: List<AliyunFileItem>,
    val nextMarker: String
)
