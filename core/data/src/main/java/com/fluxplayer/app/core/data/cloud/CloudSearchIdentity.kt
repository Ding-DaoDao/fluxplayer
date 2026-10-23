package com.fluxplayer.app.core.data.cloud

import okio.ByteString.Companion.decodeBase64

/** 优先使用令牌内的账号标识，刷新令牌时仍复用同一份历史。 */
internal fun searchTokenAccount(token: String): String = runCatching {
    val payload = token.removePrefix("Bearer ").split('.')[1]
    val json = org.json.JSONObject(payload.decodeBase64()!!.utf8())
    listOf("userID", "userId", "user_id", "uid", "sub").firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.isNotBlank() && it != "null" } } ?: token
}.getOrDefault(token)
