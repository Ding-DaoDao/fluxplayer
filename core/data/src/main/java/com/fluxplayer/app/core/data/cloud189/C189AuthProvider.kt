package com.fluxplayer.app.core.data.cloud189

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.sync.Mutex

object C189AuthProvider {
    data class Credentials(
        val accessToken: String = "",
        val sessionKey: String = "",
        val sessionSecret: String = "",
        val refreshToken: String = "",
        val expiresIn: Long = 0,
    ) {
        fun hasSession(): Boolean = accessToken.isNotBlank() && sessionKey.isNotBlank() && sessionSecret.isNotBlank()
    }

    @Volatile private var credentials = Credentials()

    @Volatile internal var searchIdentity: String = ""
        private set
    private var preferences: SharedPreferences? = null
    internal val refreshMutex = Mutex()

    var accessToken: String
        get() = credentials.accessToken
        set(value) = update { copy(accessToken = value) }
    var sessionKey: String
        get() = credentials.sessionKey
        set(value) = update { copy(sessionKey = value) }
    var sessionSecret: String
        get() = credentials.sessionSecret
        set(value) = update { copy(sessionSecret = value) }
    var refreshToken: String
        get() = credentials.refreshToken
        set(value) = update { copy(refreshToken = value) }
    var expiresIn: Long
        get() = credentials.expiresIn
        set(value) = update { copy(expiresIn = value) }

    @Volatile var familySessionKey: String = ""

    @Volatile var familySessionSecret: String = ""

    @Volatile var isActive: Boolean = false

    @Volatile var userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36"

    @Synchronized
    private fun update(transform: Credentials.() -> Credentials) {
        val updated = credentials.transform()
        if (updated.accessToken != credentials.accessToken) searchIdentity = java.util.UUID.randomUUID().toString()
        if (updated != credentials) credentials = updated
    }

    fun snapshot(): Credentials = credentials

    @Synchronized
    fun initialize(context: Context) {
        if (preferences != null) return
        val prefs = context.applicationContext.getSharedPreferences("cloud189", Context.MODE_PRIVATE)
        preferences = prefs
        restoreSavedSession()
    }

    @Synchronized
    fun restoreSavedSession() {
        if (isActive) return
        val prefs = preferences ?: return
        credentials = Credentials(
            accessToken = prefs.getString("accessToken", "").orEmpty(),
            sessionKey = prefs.getString("sessionKey", "").orEmpty(),
            sessionSecret = prefs.getString("sessionSecret", "").orEmpty(),
            refreshToken = prefs.getString("refreshToken", "").orEmpty(),
            expiresIn = prefs.getLong("expiresIn", 0),
        )
        searchIdentity = prefs.getString("searchIdentity", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("searchIdentity", it).apply() }
        isActive = credentials.accessToken.isNotBlank() || credentials.refreshToken.isNotBlank()
        C189ApiClient().restoreCookies(prefs.getString("cookies", "").orEmpty())
    }

    fun hasValidSession(): Boolean = isActive && credentials.hasSession()
    fun isTokenExpired(): Boolean = expiresIn > 0 && System.currentTimeMillis() > expiresIn

    @Synchronized
    fun setTokens(accessToken: String, sessionKey: String, sessionSecret: String, refreshToken: String = "", expiresIn: Long = 0) {
        if (accessToken != credentials.accessToken) searchIdentity = java.util.UUID.randomUUID().toString()
        credentials = Credentials(accessToken, sessionKey, sessionSecret, refreshToken, expiresIn)
        isActive = true
        persist()
    }

    /** 退出或切换账号后，丢弃旧请求返回的凭证，避免把旧账号重新登录回来。 */
    @Synchronized
    internal fun replaceIfCurrent(expected: Credentials, refreshed: Credentials): Boolean {
        if (credentials !== expected || refreshed.accessToken.isBlank()) return false
        credentials = refreshed
        isActive = true
        persist()
        return true
    }

    @Synchronized
    fun persist() {
        val current = credentials
        preferences?.edit()
            ?.putString("accessToken", current.accessToken)
            ?.putString("searchIdentity", searchIdentity)
            ?.putString("sessionKey", current.sessionKey)
            ?.putString("sessionSecret", current.sessionSecret)
            ?.putString("refreshToken", current.refreshToken)
            ?.putLong("expiresIn", current.expiresIn)
            ?.putString("cookies", C189ApiClient().saveCookies())
            ?.apply()
    }

    fun setFamilyTokens(sessionKey: String, sessionSecret: String) {
        familySessionKey = sessionKey
        familySessionSecret = sessionSecret
    }

    @Synchronized
    fun clear() {
        searchIdentity = ""
        credentials = Credentials()
        familySessionKey = ""
        familySessionSecret = ""
        isActive = false
    }

    /** 磁盘凭证和内存凭证一起退出，避免并发恢复读到旧账号。 */
    @Synchronized
    fun logout() {
        preferences?.edit()?.clear()?.apply()
        clear()
    }

    fun getPlayHeaders(): Map<String, String> = if (isActive) {
        mapOf("Cookie" to "COOKIE_LOGIN_USER=$accessToken", "User-Agent" to userAgent)
    } else {
        emptyMap()
    }
}
