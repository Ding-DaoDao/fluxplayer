package com.fluxplayer.app.core.data.aliyun

data class AliyunFileItem(
    val fileId: String,
    val fileName: String,
    val type: String,
    val category: String,
    val size: Long,
    val createdAt: String,
    val updatedAt: String,
    val thumbnail: String,
    val mimeType: String,
    val parentFileId: String
)
