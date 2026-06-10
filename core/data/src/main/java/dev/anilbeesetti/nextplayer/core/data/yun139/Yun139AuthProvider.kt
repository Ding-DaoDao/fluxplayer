package dev.anilbeesetti.nextplayer.core.data.yun139

object Yun139AuthProvider {
    @Volatile var token: String = ""
    @Volatile var isActive: Boolean = false
    @Volatile var sessionId: String = ""
    @Volatile var userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    fun hasValidSession(): Boolean = isActive && token.isNotBlank()

    fun setAuth(token: String, sessionId: String = "") {
        this.token = token
        this.sessionId = sessionId
        isActive = true
    }

    fun clear() {
        token = ""
        sessionId = ""
        isActive = false
    }
}
