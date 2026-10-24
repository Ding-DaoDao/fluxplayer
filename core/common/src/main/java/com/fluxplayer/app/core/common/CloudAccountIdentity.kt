package com.fluxplayer.app.core.common

import java.util.concurrent.ConcurrentHashMap

/** 云盘资源携带账号标识，切换账号后不能借用其他账号的同名缓存。 */
object CloudAccountIdentity {
    val bindings = ThreadLocal<Map<String, String>?>()
    fun bound(provider: String): String? = bindings.get()?.get(provider)
    fun boundProvider(): String? = bindings.get()?.keys?.firstOrNull()
    fun <T> runAs(provider: String, id: String, block: () -> T): T {
        val previous = bindings.get()
        bindings.set(previous.orEmpty() + (provider to id))
        return try {
            block()
        } finally {
            bindings.set(previous)
        }
    }
    private val active = ConcurrentHashMap<String, String>()
    fun setActive(provider: String, id: String) {
        active[provider] = id
    }
    fun active(provider: String): String = bound(provider) ?: active[provider] ?: "legacy"
    fun isCurrentCloudUri(value: String): Boolean {
        val uri = android.net.Uri.parse(value)
        if (uri.scheme != "cloud") return true
        val provider = uri.host ?: return false
        return (uri.getQueryParameter("account") ?: "legacy") == active(provider)
    }
    fun cacheKey(provider: String): String = "$provider@${active(provider.substringBefore(":"))}"
}
