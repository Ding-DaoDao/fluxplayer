package com.fluxplayer.app.core.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局 CookieJar。
 *
 * OkHttp 的 [CookieJar] 会被多个调度线程并发调用（saveFromResponse / loadForRequest），
 * 因此内部存储必须线程安全：
 * - [store] 使用 ConcurrentHashMap + CopyOnWriteArrayList
 * - [quarkCookie] / [ucCookie] 使用 @Volatile 保证可见性
 */
object GlobalCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, CopyOnWriteArrayList<Cookie>>()

    @Volatile
    private var quarkCookie = ""

    @Volatile
    private var ucCookie = ""

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store[url.host] = CopyOnWriteArrayList(cookies)
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        val cookieStr = when {
            "quark.cn" in host -> quarkCookie
            "uc.cn" in host -> ucCookie
            else -> ""
        }
        if (cookieStr.isBlank()) {
            return store[host] ?: emptyList()
        }
        return cookieStr.split(";").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx > 0) {
                Cookie.Builder()
                    .name(part.substring(0, idx).trim())
                    .value(part.substring(idx + 1).trim())
                    .hostOnlyDomain(host)
                    .build()
            } else null
        }
    }

    fun setQuarkCookie(cookie: String) { quarkCookie = cookie }
    fun setUcCookie(cookie: String) { ucCookie = cookie }
    fun getQuarkCookie(): String = quarkCookie
    fun getUcCookie(): String = ucCookie

    fun setCookie(host: String, name: String, value: String) {
        // 用 computeIfAbsent（原子）而非 getOrPut（非原子，并发首插会互相覆盖丢数据）
        val cookies = store.computeIfAbsent(host) { CopyOnWriteArrayList() }
            ?: CopyOnWriteArrayList<Cookie>()
        cookies.add(
            Cookie.Builder()
                .name(name).value(value)
                .hostOnlyDomain(host)
                .build()
        )
    }

    /** 清除指定 host 的 Cookie */
    fun clearHost(host: String) {
        store.remove(host)
    }

    /** 清除所有 Cookie（包括 quark/uc 专用 cookie） */
    fun clearAll() {
        store.clear()
        quarkCookie = ""
        ucCookie = ""
    }
}
