package dev.anilbeesetti.nextplayer.core.common.extensions

import android.net.Uri
import dev.anilbeesetti.nextplayer.core.model.VideoSource

fun VideoSource.Companion.fromUri(uriString: String): VideoSource {
    val uri: Uri = try {
        Uri.parse(uriString)
    } catch (_: Exception) {
        return VideoSource.OTHER
    }
    return when {
        uri.scheme == "content" || uri.scheme == "file" -> VideoSource.LOCAL
        uri.userInfo != null -> VideoSource.WEBDAV
        uri.host == "127.0.0.1" && uri.port == 5244 -> VideoSource.OPENLIST
        else -> VideoSource.OTHER
    }
}
