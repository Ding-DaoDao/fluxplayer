package com.fluxplayer.app.core.common

/**
 * 脱敏 URL 用于日志：保留 scheme/host/path，query 参数值全部打码。
 * 播放 URL 常携带签名/token，直接打印会泄露鉴权凭证。
 */
fun sanitizeUrl(url: String?): String {
    if (url.isNullOrBlank()) return url ?: "null"
    return try {
        val u = java.net.URI(url)
        val sb = StringBuilder()
        sb.append(u.scheme).append("://").append(u.host ?: "").append(u.path ?: "")
        if (u.query != null) {
            sb.append('?')
            sb.append(u.query.split('&').joinToString("&") { pair ->
                val key = pair.substringBefore('=')
                if (pair.contains('=') && key.isNotBlank()) "$key=***" else key
            })
        }
        sb.toString()
    } catch (_: Exception) {
        "<malformed-url>"
    }
}
