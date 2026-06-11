package dev.anilbeesetti.nextplayer.core.common

/**
 * 全局云端播放请求头注册表
 * 云盘 ViewModel 在解析视频 URI 时注册 headers，播放器在打开 HTTP 连接时自动注入
 */
object CloudPlayHeaders {
    private val providersByDomain = mutableMapOf<String, () -> Map<String, String>>()

    fun register(domain: String, headerProvider: () -> Map<String, String>) {
        providersByDomain[domain] = headerProvider
    }

    fun register(domain: String, headers: Map<String, String>) {
        if (headers.isNotEmpty()) {
            providersByDomain[domain] = { headers }
        }
    }

    fun getHeaders(domain: String): Map<String, String> {
        return providersByDomain[domain]?.invoke() ?: emptyMap()
    }

    fun clear() {
        providersByDomain.clear()
    }
}
