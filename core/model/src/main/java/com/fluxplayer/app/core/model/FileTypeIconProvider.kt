package com.fluxplayer.app.core.model

fun interface FileTypeIconProvider {
    fun getResId(fileType: WebDavResource.FileType): Int
}
