package com.fluxplayer.app.core.data.cloud189

import android.content.Context
import android.content.SharedPreferences
import com.fluxplayer.app.core.data.cloud.cloudPreferences
import com.fluxplayer.app.core.data.security.getSecret
import com.fluxplayer.app.core.data.security.putSecret
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

    private class State {
        @Volatile var credentials = Credentials()

        @Volatile var searchIdentity = ""
        var preferences: SharedPreferences? = null
        val refreshMutex = Mutex()

        @Volatile var familySessionKey = ""

        @Volatile var familySessionSecret = ""

        @Volatile var isActive = false

        @Volatile var userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/87.0.4280.88 Safari/537.36"
    }
    private val accounts = com.fluxplayer.app.core.data.cloud.CloudAccountState("cloud189") { State() }
    fun clearAll() = accounts.clearAll()
    private var credentials: Credentials
        get() = accounts.current.credentials
        set(value) {
            accounts.current.credentials = value
        }
    internal var searchIdentity: String
        get() = accounts.current.searchIdentity
        private set(value) {
            accounts.current.searchIdentity = value
        }
    private var preferences: SharedPreferences?
        get() = accounts.current.preferences
        set(value) {
            accounts.current.preferences = value
        }
    internal val refreshMutex get() = accounts.current.refreshMutex

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

    var familySessionKey: String
        get() = accounts.current.familySessionKey
        set(value) {
            accounts.current.familySessionKey = value
        }

    var familySessionSecret: String
        get() = accounts.current.familySessionSecret
        set(value) {
            accounts.current.familySessionSecret = value
        }

    var isActive: Boolean
        get() = accounts.current.isActive
        set(value) {
            accounts.current.isActive = value
        }

    var userAgent: String
        get() = accounts.current.userAgent
        set(value) {
            accounts.current.userAgent = value
        }

    @Synchronized
    private fun update(transform: Credentials.() -> Credentials) {
        val updated = credentials.transform()
        if (updated.accessToken != credentials.accessToken) searchIdentity = java.util.UUID.randomUUID().toString()
        if (updated != credentials) credentials = updated
    }

    fun snapshot(): Credentials = credentials

    fun initialize(context: Context) {
        val prefs = context.applicationContext.cloudPreferences("cloud189", Context.MODE_PRIVATE)
        synchronized(this) {
            if (preferences === prefs) return
            clear()
            preferences = prefs
            restoreSavedSession()
        }
    }

    @Synchronized
    fun restoreSavedSession() {
        if (isActive) return
        val prefs = preferences ?: return
        credentials = Credentials(
            accessToken = prefs.getSecret("cloud189", "accessToken").orEmpty(),
            sessionKey = prefs.getSecret("cloud189", "sessionKey").orEmpty(),
            sessionSecret = prefs.getSecret("cloud189", "sessionSecret").orEmpty(),
            refreshToken = prefs.getSecret("cloud189", "refreshToken").orEmpty(),
            expiresIn = prefs.getLong("expiresIn", 0),
        )
        searchIdentity = prefs.getString("searchIdentity", null) ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString("searchIdentity", it).apply() }
        isActive = credentials.accessToken.isNotBlank() || credentials.refreshToken.isNotBlank()
        val secrets = listOf("accessToken", "sessionKey", "sessionSecret", "refreshToken", "cookies")
        val saved = secrets.associateWith { prefs.getSecret("cloud189", it) }
        C189Cookies.restore(saved["cookies"].orEmpty())
        // 只有成功读取的旧明文字段才迁移，密钥暂不可用时保留原密文。
        val legacy = saved.filter { (key, value) -> value != null && prefs.getString(key, null)?.startsWith("enc:v1:") == false }
        if (legacy.isNotEmpty()) {
            runCatching {
                val editor = prefs.edit()
                legacy.forEach { (key, value) -> editor.putSecret("cloud189", key, value) }
                check(editor.commit()) { "保存天翼登录信息失败，请重试" }
            }.onFailure { android.util.Log.w("C189AuthProvider", "旧凭证迁移未完成，原数据已保留", it) }
        }
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
    internal fun savePassword(expected: Credentials, username: String, password: String): Boolean {
        if (credentials !== expected) return false
        val prefs = preferences ?: return false
        return prefs.edit().putSecret("cloud189", "username", username).putSecret("cloud189", "password", password)
            .putBoolean("auto_login_blocked", false).commit()
    }

    @Synchronized
    fun forgetPassword(expected: Credentials) {
        if (credentials === expected) preferences?.edit()?.remove("username")?.remove("password")?.putBoolean("auto_login_blocked", false)?.commit()
    }

    @Synchronized
    internal fun passwordFor(expected: Credentials): Pair<String, String>? {
        if (credentials !== expected || preferences?.getBoolean("auto_login_blocked", false) == true) return null
        val username = preferences?.getSecret("cloud189", "username").orEmpty()
        val password = preferences?.getSecret("cloud189", "password").orEmpty()
        return if (username.isNotBlank() && password.isNotEmpty()) username to password else null
    }

    @Synchronized
    internal fun blockPassword(expected: Credentials) {
        if (credentials === expected) preferences?.edit()?.putBoolean("auto_login_blocked", true)?.commit()
    }

    @Synchronized
    fun persist() {
        val current = credentials
        preferences?.edit()
            ?.putSecret("cloud189", "accessToken", current.accessToken)
            ?.putString("searchIdentity", searchIdentity)
            ?.putSecret("cloud189", "sessionKey", current.sessionKey)
            ?.putSecret("cloud189", "sessionSecret", current.sessionSecret)
            ?.putSecret("cloud189", "refreshToken", current.refreshToken)
            ?.putLong("expiresIn", current.expiresIn)
            ?.putSecret("cloud189", "cookies", C189Cookies.save())
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
