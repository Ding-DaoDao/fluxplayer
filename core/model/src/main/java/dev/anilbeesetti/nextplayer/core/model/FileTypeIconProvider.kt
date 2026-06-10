package dev.anilbeesetti.nextplayer.core.model

fun interface FileTypeIconProvider {
    fun getResId(fileType: WebDavResource.FileType): Int
}
