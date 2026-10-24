package com.fluxplayer.app.core.data.pan123

data class LoginResult(
    val token: String,
    // 到期时间，单位为秒
    val refreshTokenExpireTime: Long,
    val userId: String = "",
)
