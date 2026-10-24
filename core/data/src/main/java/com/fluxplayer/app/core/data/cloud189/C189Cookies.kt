package com.fluxplayer.app.core.data.cloud189

import com.fluxplayer.app.core.data.GlobalCookieJar

/** Cookie 快照不创建网络客户端，避免凭证锁与请求会话锁相互等待。 */
internal object C189Cookies {
    fun clear() {
        listOf("cloud.189.cn", "api.cloud.189.cn", "m.cloud.189.cn", "open.e.189.cn", "auth.client.189.cn").forEach { GlobalCookieJar.clearHost(it) }
    }

    /** 保存 GlobalCookieJar 中所有 cloud.189.cn 相关 Cookie 为字符串 */
    fun save(): String {
        val sb = StringBuilder()
        for (host in listOf("cloud.189.cn", "api.cloud.189.cn", "m.cloud.189.cn", "open.e.189.cn")) {
            val cookies = GlobalCookieJar.loadForRequest(okhttp3.HttpUrl.Builder().scheme("https").host(host).build())
            if (cookies.isNotEmpty()) {
                sb.append(cookies.joinToString(";") { "${it.name}=${it.value}" })
                sb.append(";")
            }
        }
        return sb.toString()
    }

    /** 恢复 Cookie 到 GlobalCookieJar（app 重启后调用） */
    fun restore(cookies: String) {
        clear()
        if (cookies.isBlank()) return
        for (part in cookies.split(";")) {
            val idx = part.indexOf('=')
            if (idx > 0) {
                val name = part.substring(0, idx).trim()
                val value = part.substring(idx + 1).trim()
                if (name.isNotBlank() && value.isNotBlank()) {
                    for (host in listOf("cloud.189.cn", "api.cloud.189.cn", "m.cloud.189.cn", "open.e.189.cn")) {
                        GlobalCookieJar.setCookie(host, name, value)
                    }
                }
            }
        }
    }
}
