package com.fluxplayer.app.core.data.pan123

object Pan123AuthProvider {
    private class State {
        @Volatile var authorization: String = ""

        @Volatile var referer: String = "https://yun.123pan.cn/"

        @Volatile var isActive: Boolean = false

        @Volatile var userAgent: String = "123pan/v3.1.3(Android_10;Xiaomi)"

        @Volatile var osVersion: String = android.os.Build.VERSION.RELEASE ?: "10"

        @Volatile var loginUuid: String = java.util.UUID.randomUUID().toString()

        @Volatile var deviceType: String = "phone"

        @Volatile var deviceName: String = "Xiaomi"
    }
    private val accounts = com.fluxplayer.app.core.data.cloud.CloudAccountState("pan123") { State() }
    fun clearAll() = accounts.clearAll()

    var authorization: String
        get() = accounts.current.authorization
        set(value) {
            accounts.current.authorization = value
        }
    var referer: String
        get() = accounts.current.referer
        set(value) {
            accounts.current.referer = value
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
    var osVersion: String
        get() = accounts.current.osVersion
        set(value) {
            accounts.current.osVersion = value
        }
    var loginUuid: String
        get() = accounts.current.loginUuid
        set(value) {
            accounts.current.loginUuid = value
        }
    var deviceType: String
        get() = accounts.current.deviceType
        set(value) {
            accounts.current.deviceType = value
        }
    var deviceName: String
        get() = accounts.current.deviceName
        set(value) {
            accounts.current.deviceName = value
        }

    fun clear() {
        authorization = ""
        isActive = false
    }

    fun getPlayHeaders(): Map<String, String> {
        return if (isActive) {
            mapOf(
                "Referer" to referer,
                "User-Agent" to userAgent,
                "X-MF-PAN-RANGE" to "1",
            )
        } else {
            emptyMap()
        }
    }
}
