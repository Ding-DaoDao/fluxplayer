package com.fluxplayer.app.core.data.quark

data class QuarkFileItem(
    val fid: String,
    val fileName: String,
    val dir: Boolean,
    val objCategory: String,
    val size: Long,
    val updatedAt: Long,
    val createdAt: Long,
    val thumbnail: String,
    val shareFidToken: String,
    val includeItems: Int = 0
) {
    val isVideo: Boolean get() = objCategory == "video"
    val isAudio: Boolean get() = objCategory == "audio"
    val isImage: Boolean get() = objCategory == "image"
}
