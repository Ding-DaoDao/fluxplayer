package dev.anilbeesetti.nextplayer.core.common.extensions

import android.net.Uri
import dev.anilbeesetti.nextplayer.core.common.CloudUriScheme
import dev.anilbeesetti.nextplayer.core.model.VideoSource

fun VideoSource.Companion.fromUri(uriString: String): VideoSource {
    val uri: Uri = try {
        Uri.parse(uriString)
    } catch (_: Exception) {
        return VideoSource.OTHER
    }

    // cloud:// scheme
    if (uri.scheme == "cloud") {
        return when (uri.host) {
            "webdav" -> VideoSource.WEBDAV
            "openlist" -> VideoSource.OPENLIST
            "alipan" -> VideoSource.ALIYUN
            "pan123" -> VideoSource.PAN123
            "uc" -> VideoSource.UC
            "quark" -> VideoSource.QUARK
            "cloud189" -> VideoSource.CLOUD189
            "yun139" -> VideoSource.YUN139
            else -> VideoSource.OTHER
        }
    }

    // content:// or file://
    if (uri.scheme == "content" || uri.scheme == "file") return VideoSource.LOCAL

    // WebDAV with userinfo
    if (uri.userInfo != null) return VideoSource.WEBDAV

    // OpenList local proxy
    if (uri.host == "127.0.0.1" && uri.port == 5244) return VideoSource.OPENLIST

    // Fragment-based cloud detection
    val fragment = uri.fragment ?: ""
    return when {
        fragment.contains("ucPlay") -> VideoSource.UC
        fragment.contains("quarkPlay") -> VideoSource.QUARK
        fragment.contains("189Play") -> VideoSource.CLOUD189
        fragment.contains("yun139Play") -> VideoSource.YUN139
        fragment.contains("isVideo") -> VideoSource.PAN123
        fragment.contains("alipanPlay") -> VideoSource.ALIYUN
        else -> VideoSource.OTHER
    }
}
