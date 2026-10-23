package com.fluxplayer.app.core.common

import android.net.Uri

/**
 * 全局云端播放请求头注册表
 * 支持两种注册方式：
 * - register(domain)  → 精确域名匹配，如 "vod.quark.cn"
 * - registerSuffix(suffix) → 后缀匹配，如 ".quark.cn" 匹配所有 *.quark.cn 子域名
 *
 * 查找优先级：精确匹配 > 最长后缀匹配
 */
object CloudPlayHeaders {
    @Volatile var activeProvider: String = ""

    fun isExplicitlyAnonymous(domain: String): Boolean = anonymousHosts[domain] == activeProvider

    /**
     * 明确「不需要任何认证头」的域名 → 注册它的来源。
     * 必须带来源作用域：夸克与 UC 共用阿里 CDN 域名，UC TV 的预签名地址
     * 如果无条件把域名标成匿名，会把夸克同一域名上的播放头一起屏蔽掉。
     */
    private val anonymousHosts = mutableMapOf<String, String>()

    fun registerAnonymous(domain: String, provider: String) {
        anonymousHosts[domain] = provider
    }

    private val exactMatchers = mutableMapOf<String, () -> Map<String, String>>()
    private val suffixMatchers = mutableMapOf<String, () -> Map<String, String>>()

    fun register(domain: String, headerProvider: () -> Map<String, String>) {
        exactMatchers[domain] = headerProvider
    }

    fun register(domain: String, headers: Map<String, String>) {
        if (headers.isNotEmpty()) {
            exactMatchers[domain] = { headers }
        }
    }

    /**
     * 注册域名后缀匹配规则，suffix 应以 "." 开头，如 ".quark.cn"
     * HLS .ts 分片可能落在 CDN 边缘子域名上，后缀匹配可保证全子域名覆盖
     */
    fun registerSuffix(suffix: String, headerProvider: () -> Map<String, String>) {
        val normalized = if (suffix.startsWith(".")) suffix else ".$suffix"
        suffixMatchers[normalized] = headerProvider
    }

    fun registerSuffix(suffix: String, headers: Map<String, String>) {
        if (headers.isNotEmpty()) {
            val normalized = if (suffix.startsWith(".")) suffix else ".$suffix"
            suffixMatchers[normalized] = { headers }
        }
    }

    fun getHeaders(domain: String): Map<String, String> {
        // 1. 精确匹配优先
        exactMatchers[domain]?.invoke()?.let { return it }

        // 2. 后缀匹配（最长后缀优先，确保 .a.b.com 优先于 .b.com）
        return suffixMatchers.entries
            .filter { domain.endsWith(it.key) }
            .maxByOrNull { it.key.length }
            ?.value?.invoke()
            ?: emptyMap()
    }

    /**
     * 判断 URL 是否为预签名（自认证）链接。
     * 预签名 URL 通过查询参数携带签名信息，注入额外 Authorization 会破坏签名。
     */
    fun isSelfAuthenticatingUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.getQueryParameter("X-Amz-Algorithm") != null
            || uri.getQueryParameter("OSSAccessKeyId") != null
    }

    fun clear() {
        activeProvider = ""
        exactMatchers.clear()
        suffixMatchers.clear()
        anonymousHosts.clear()
    }
}
