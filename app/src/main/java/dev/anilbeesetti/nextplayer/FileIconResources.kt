package dev.anilbeesetti.nextplayer

import dev.anilbeesetti.nextplayer.core.model.FileTypeIconProvider
import dev.anilbeesetti.nextplayer.core.model.WebDavResource

data object FileIconResources : FileTypeIconProvider {
    override fun getResId(fileType: WebDavResource.FileType): Int = when (fileType) {
        WebDavResource.FileType.FOLDER -> R.drawable.ic_file_folder
        WebDavResource.FileType.VIDEO -> R.drawable.ic_file_video
        WebDavResource.FileType.AUDIO -> R.drawable.ic_file_audio
        WebDavResource.FileType.DOC -> R.drawable.ic_file_doc
        WebDavResource.FileType.IMAGE -> R.drawable.ic_file_image
        WebDavResource.FileType.ARCHIVE -> R.drawable.ic_file_archive
        WebDavResource.FileType.UNKNOWN -> R.drawable.ic_file_unknown
    }

    fun getProviderResId(providerKey: String): Int = when (providerKey) {
        "alipan" -> R.drawable.ic_provider_alipan
        "pan123" -> R.drawable.ic_provider_pan123
        "webdav" -> R.drawable.ic_provider_webdav
        "yun139" -> R.drawable.ic_provider_yun139
        "openlist" -> R.drawable.ic_provider_openlist
        "uc" -> R.drawable.ic_provider_uc
        "baidu" -> R.drawable.ic_provider_baidu
        "quark" -> R.drawable.ic_provider_quark
        "cloud189" -> R.drawable.ic_provider_cloud189
        else -> 0
    }
}
