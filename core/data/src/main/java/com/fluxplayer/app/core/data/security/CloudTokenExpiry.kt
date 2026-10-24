package com.fluxplayer.app.core.data.security

import okio.ByteString.Companion.decodeBase64
import org.json.JSONObject

/** JWT 仅用于安排续期，账号身份和登录有效性仍由服务端确认。 */
object CloudTokenExpiry {
    fun fromToken(token: String): Long = runCatching {
        val payload = token.removePrefix("Bearer ").split('.').getOrNull(1)?.decodeBase64()?.utf8() ?: return 0
        val seconds = JSONObject(payload).optLong("exp", 0)
        if (seconds in 1..Long.MAX_VALUE / 1000) seconds * 1000 else 0
    }.getOrDefault(0)

    fun fromResponse(json: JSONObject, token: String, now: Long = System.currentTimeMillis()): Long {
        val seconds = json.optLong("expires_in", json.optLong("expiresIn", 0))
        return if (seconds in 1..(Long.MAX_VALUE - now) / 1000) now + seconds * 1000 else fromToken(token)
    }

    fun needsRefresh(expiresAt: Long, now: Long = System.currentTimeMillis()): Boolean = expiresAt > 0 && expiresAt - now <= 60_000
}
