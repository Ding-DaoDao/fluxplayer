package dev.anilbeesetti.nextplayer.core.data.quark

object QuarkAuthProvider {
    @Volatile var cookie: String = ""
    @Volatile var referer: String = "https://drive.quark.cn/"
    @Volatile var isActive: Boolean = false
    @Volatile var userAgent: String = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/100.0.4896.58 UCBrowser/16.5.8.1309 Mobile Safari/537.36"

    fun clear() {
        cookie = ""
        isActive = false
    }

    fun getPlayHeaders(): Map<String, String> {
        return if (isActive) {
            mapOf(
                "Cookie" to cookie,
                "Referer" to referer,
                "User-Agent" to userAgent
            )
        } else emptyMap()
    }
}
