package com.fluxplayer.app.core.data.pan123

data class LoginResult(
    val token: String,
    val refreshTokenExpireTime: Long // Unix timestamp (seconds)
)
