package dev.anilbeesetti.nextplayer.core.data

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

object GlobalCookieJar : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()
    private var quarkCookie = ""
    private var ucCookie = ""

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store[url.host] = cookies.toMutableList()
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
        store.getOrPut(host) { mutableListOf() }.add(
            Cookie.Builder()
                .name(name).value(value)
                .hostOnlyDomain(host)
                .build()
        )
    }
}
