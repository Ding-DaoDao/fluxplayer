package com.fluxplayer.app.core.data

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * 全局 CookieJar。
 *
 * OkHttp 的 [CookieJar] 会被多个调度线程并发调用（saveFromResponse / loadForRequest），
 * 因此内部存储必须线程安全：
 * - [store] 使用 ConcurrentHashMap + CopyOnWriteArrayList
 * - 夸克和 UC 的 Cookie 按账号存储，互不覆盖
 */
object GlobalCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, CopyOnWriteArrayList<Cookie>>()

    private val quarkCookies = ConcurrentHashMap<String, String>()
    private val ucCookies = ConcurrentHashMap<String, String>()
    private fun account(provider: String) = com.fluxplayer.app.core.common.CloudAccountIdentity.active(provider)
    private fun key(host: String): String {
        val provider = when {
            host == "189.cn" || host.endsWith(".189.cn") -> "cloud189"
            host == "139.com" || host.endsWith(".139.com") -> "yun139"
            host.endsWith(".quark.cn") -> "quark"
            host.endsWith(".uc.cn") -> "uc"
            else -> return host
        }
        return "$host@${account(provider)}"
    }
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val stored = store.computeIfAbsent(key(url.host)) { CopyOnWriteArrayList() }
        synchronized(stored) {
            cookies.forEach { cookie ->
                stored.removeAll { it.name == cookie.name && it.path == cookie.path }
                if (cookie.expiresAt > System.currentTimeMillis()) stored.add(cookie)
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        val cookieStr = when {
            "quark.cn" in host -> quarkCookies[account("quark")].orEmpty()
            "uc.cn" in host -> ucCookies[account("uc")].orEmpty()
            else -> ""
        }
        if (cookieStr.isBlank()) {
            return store[key(host)] ?: emptyList()
        }
        return cookieStr.split(";").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx > 0) {
                Cookie.Builder()
                    .name(part.substring(0, idx).trim())
                    .value(part.substring(idx + 1).trim())
                    .hostOnlyDomain(host)
                    .build()
            } else {
                null
            }
        }
    }

    fun setQuarkCookie(cookie: String) {
        quarkCookies[account("quark")] = cookie
    }
    fun setUcCookie(cookie: String) {
        ucCookies[account("uc")] = cookie
    }
    fun getQuarkCookie(): String = quarkCookies[account("quark")].orEmpty()
    fun getUcCookie(): String = ucCookies[account("uc")].orEmpty()

    fun setCookie(host: String, name: String, value: String) {
        // 用 computeIfAbsent（原子）而非 getOrPut（非原子，并发首插会互相覆盖丢数据）
        val cookies = store.computeIfAbsent(key(host)) { CopyOnWriteArrayList() }
            ?: CopyOnWriteArrayList<Cookie>()
        synchronized(cookies) {
            cookies.removeAll { it.name == name }
            cookies.add(
                Cookie.Builder()
                    .name(name).value(value)
                    .hostOnlyDomain(host)
                    .build(),
            )
        }
    }

    /** 清除指定 host 的 Cookie */
    fun clearHost(host: String) {
        store.remove(key(host))
    }

    fun clearProvider(provider: String) {
        val suffix = mapOf("cloud189" to "189.cn", "yun139" to "139.com", "quark" to "quark.cn", "uc" to "uc.cn")[provider]
        if (suffix != null) store.keys.removeAll { it.substringBefore('@').let { host -> host == suffix || host.endsWith(".$suffix") } }
        if (provider == "quark") quarkCookies.clear()
        if (provider == "uc") ucCookies.clear()
    }

    /** 清除所有 Cookie（包括 quark/uc 专用 cookie） */
    fun clearAll() {
        store.clear()
        quarkCookies.clear()
        ucCookies.clear()
    }
}
