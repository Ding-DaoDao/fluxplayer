package com.fluxplayer.app.core.common

import android.net.Uri

object CloudUriScheme {
    private const val SCHEME = "cloud"

    fun isCloudUri(uri: Uri): Boolean = uri.scheme == SCHEME

    fun getProvider(uri: Uri): String? {
        if (!isCloudUri(uri)) return null
        return uri.host  // e.g. "cloud://alipan/fileId"
    }

    fun getFileId(uri: Uri): String? {
        if (!isCloudUri(uri)) return null
        val path = uri.path ?: return null
        return path.trimStart('/')
    }

    /** 获取云端文件夹路径（从 query 参数中提取） */
    fun getCloudFolder(uri: Uri): String? = uri.getQueryParameter("folder")

    fun buildCloudUri(provider: String, fileId: String, folder: String = ""): Uri {
        val base = "$SCHEME://$provider/$fileId"
        return if (folder.isNotEmpty()) {
            Uri.parse(base).buildUpon().appendQueryParameter("folder", folder).build()
        } else {
            Uri.parse(base)
        }
    }
}
