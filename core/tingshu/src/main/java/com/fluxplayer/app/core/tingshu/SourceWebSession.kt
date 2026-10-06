package com.fluxplayer.app.core.tingshu

import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** 仅清理当前登录网站的网页凭证与存储，覆盖父级泛域 Cookie。 */
object SourceWebSession {
    suspend fun clear(urls: List<String>) = withContext(Dispatchers.Main) {
        val parsed = urls.mapNotNull { it.toHttpUrlOrNull() }
        val domains = parsed.map { it.topPrivateDomain() ?: it.host }.toSet()
        if (domains.isEmpty()) return@withContext
        val storage = WebStorage.getInstance()
        val origins = suspendCoroutine<List<String>> { continuation ->
            storage.getOrigins { values -> continuation.resume(values.keys.filterIsInstance<String>()) }
        }.filter { origin -> origin.toHttpUrlOrNull()?.let { (it.topPrivateDomain() ?: it.host) in domains } == true }
        val knownHosts = domains.flatMap { domain ->
            when (domain) {
                "quark.cn" -> listOf("pan.quark.cn", "drive.quark.cn", "b.quark.cn", "uop.quark.cn")
                "189.cn" -> listOf("cloud.189.cn", "m.cloud.189.cn", "open.e.189.cn", "e.189.cn")
                "139.com" -> listOf("yun.139.com", "caiyun.139.com")
                else -> emptyList()
            }
        }
        val targets = (urls + origins + (domains + knownHosts).map { "https://$it/" }).distinct().mapNotNull { it.toHttpUrlOrNull() }
        val manager = CookieManager.getInstance()
        targets.forEach { url ->
            val names = manager.getCookie(url.toString()).orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotBlank() }
            val parts = url.host.split('.')
            val top = url.topPrivateDomain() ?: url.host
            val cookieDomains = parts.indices.map { parts.drop(it).joinToString(".") }.filter { it == top || it.endsWith(".$top") }
            val paths = setOf("/", url.encodedPath.ifBlank { "/" })
            names.forEach { name ->
                paths.forEach { path ->
                    val base = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=$path; Secure"
                    val variants = listOf(base) + cookieDomains.flatMap { listOf("$base; Domain=$it", "$base; Domain=.$it") }
                    variants.forEach { cookie ->
                        suspendCoroutine<Unit> { continuation -> manager.setCookie(url.toString(), cookie) { continuation.resume(Unit) } }
                    }
                }
            }
        }
        origins.forEach(storage::deleteOrigin)
        manager.flush()
    }
}
