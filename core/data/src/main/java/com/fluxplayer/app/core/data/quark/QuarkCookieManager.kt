package com.fluxplayer.app.core.data.quark

class QuarkCookieManager(initial: String = "") {
    private val cookies = LinkedHashMap<String, String>()

    init { add(initial) }

    fun add(cookie: Any?) {
        when (cookie) {
            is String -> {
                if (cookie.isNotBlank()) {
                    cookie.split(";").forEach { part ->
                        val idx = part.indexOf('=')
                        if (idx > 0) {
                            val key = part.substring(0, idx).trim()
                            val value = part.substring(idx + 1).trim()
                            if (key.isNotEmpty()) cookies[key] = value
                        }
                    }
                }
            }
            is List<*> -> cookie.filterIsInstance<String>().forEach { add(it) }
        }
    }

    fun get(): String = cookies.entries.joinToString(";") { "${it.key}=${it.value}" }
}
