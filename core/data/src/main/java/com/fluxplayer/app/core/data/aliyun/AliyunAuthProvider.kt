package com.fluxplayer.app.core.data.aliyun

object AliyunAuthProvider {
    @Volatile var authorization: String = ""
    @Volatile var isActive: Boolean = false
    @Volatile var userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    fun clear() {
        authorization = ""
        isActive = false
    }

    fun getPlayHeaders(): Map<String, String> {
        return if (isActive) {
            mapOf(
                "Authorization" to authorization,
                "User-Agent" to userAgent,
                "Referer" to "https://www.alipan.com/"
            )
        } else emptyMap()
    }
}
