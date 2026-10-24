package com.fluxplayer.app.core.data.aliyun

object AliyunAuthProvider {
    private class State {
        @Volatile var authorization: String = ""

        @Volatile var isActive: Boolean = false

        @Volatile var userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
    private val accounts = com.fluxplayer.app.core.data.cloud.CloudAccountState("alipan") { State() }
    fun clearAll() = accounts.clearAll()

    var authorization: String
        get() = accounts.current.authorization
        set(value) {
            accounts.current.authorization = value
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

    fun clear() {
        authorization = ""
        isActive = false
    }

    fun getPlayHeaders(): Map<String, String> {
        return if (isActive) {
            mapOf(
                "Authorization" to authorization,
                "User-Agent" to userAgent,
                "Referer" to "https://www.alipan.com/",
            )
        } else {
            emptyMap()
        }
    }
}
