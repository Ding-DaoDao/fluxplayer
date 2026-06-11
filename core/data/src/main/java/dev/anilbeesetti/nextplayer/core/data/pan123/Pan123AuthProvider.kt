package dev.anilbeesetti.nextplayer.core.data.pan123

object Pan123AuthProvider {
    @Volatile var authorization: String = ""
    @Volatile var referer: String = "https://yun.123pan.cn/"
    @Volatile var isActive: Boolean = false
    @Volatile var userAgent: String = "123pan/v3.1.3(Android_10;Xiaomi)"
    @Volatile var osVersion: String = android.os.Build.VERSION.RELEASE ?: "10"
    @Volatile var loginUuid: String = java.util.UUID.randomUUID().toString()
    @Volatile var deviceType: String = "phone"
    @Volatile var deviceName: String = "Xiaomi"

    fun clear() {
        authorization = ""
        isActive = false
    }

    fun getPlayHeaders(): Map<String, String> {
        return if (isActive) {
            mapOf(
                "Authorization" to authorization,
                "Referer" to referer,
                "User-Agent" to userAgent
            )
        } else emptyMap()
    }
}
