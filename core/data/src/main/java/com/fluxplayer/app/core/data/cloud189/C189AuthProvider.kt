package com.fluxplayer.app.core.data.cloud189

object C189AuthProvider {
    @Volatile var accessToken: String = ""
    @Volatile var sessionKey: String = ""
    @Volatile var sessionSecret: String = ""
    @Volatile var refreshToken: String = ""
    @Volatile var expiresIn: Long = 0
    @Volatile var familySessionKey: String = ""
    @Volatile var familySessionSecret: String = ""
    @Volatile var isActive: Boolean = false
    @Volatile var userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36"

    fun hasValidSession(): Boolean = isActive && sessionKey.isNotBlank()
    fun isTokenExpired(): Boolean = expiresIn > 0 && System.currentTimeMillis() > expiresIn

    fun setTokens(
        accessToken: String, sessionKey: String, sessionSecret: String,
        refreshToken: String = "", expiresIn: Long = 0
    ) {
        this.accessToken = accessToken
        this.sessionKey = sessionKey
        this.sessionSecret = sessionSecret
        this.refreshToken = refreshToken
        this.expiresIn = expiresIn
        isActive = true
    }

    fun setFamilyTokens(sessionKey: String, sessionSecret: String) {
        this.familySessionKey = sessionKey
        this.familySessionSecret = sessionSecret
    }

    fun clear() {
        accessToken = ""
        sessionKey = ""
        sessionSecret = ""
        refreshToken = ""
        familySessionKey = ""
        familySessionSecret = ""
        expiresIn = 0
        isActive = false
    }

    fun getPlayHeaders(): Map<String, String> {
        return if (isActive) {
            mapOf(
                "Cookie" to "COOKIE_LOGIN_USER=$accessToken",
                "User-Agent" to userAgent
            )
        } else emptyMap()
    }
}
