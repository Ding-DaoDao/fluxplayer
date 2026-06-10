package dev.anilbeesetti.nextplayer.core.common

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

    fun buildCloudUri(provider: String, fileId: String): Uri {
        return Uri.parse("$SCHEME://$provider/$fileId")
    }
}
