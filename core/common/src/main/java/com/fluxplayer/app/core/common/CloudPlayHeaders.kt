package com.fluxplayer.app.core.common

import android.net.Uri

/**
 * 全局云端播放请求头注册表。
 *
 * 支持两种匹配：
 * - register(domain)       → 精确域名，如 "vod.quark.cn"
 * - registerSuffix(suffix) → 后缀匹配，如 ".quark.cn" 匹配所有 *.quark.cn 子域名
 *
 * 查找优先级：精确匹配 > 最长后缀匹配。
 *
 * **按来源隔离**：夸克与 UC 共用同一段 CDN 域名，因此注册时可以带上来源
 * （[registerFor] / [registerSuffixFor]）。查找时优先取「来源 == [activeProvider]」的规则，
 * 其次取不带来源的公共规则，最后才回退到其它来源——这样一家注册的域名不会把另一家的头顶掉。
 */
object CloudPlayHeaders {
    @Volatile var activeProvider: String = ""

    /** 不带来源的公共规则（其它四家网盘用这个，域名互不重叠）。 */
    private const val GLOBAL = ""

    private val exactMatchers = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, () -> Map<String, String>>()
    private val suffixMatchers = java.util.concurrent.ConcurrentHashMap<Pair<String, String>, () -> Map<String, String>>()

    /**
     * 明确「不需要任何认证头」的域名 → 注册它的来源。
     * 只有当前播放来源与注册来源一致时才算匿名。
     */
    private val anonymousHosts = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun register(domain: String, headerProvider: () -> Map<String, String>) =
        registerFor(GLOBAL, domain, headerProvider)

    fun register(domain: String, headers: Map<String, String>) {
        if (headers.isNotEmpty()) registerFor(GLOBAL, domain) { headers }
    }

    fun registerFor(provider: String, domain: String, headerProvider: () -> Map<String, String>) {
        val owner = owner(provider)
        exactMatchers[owner to domain] = bind(owner, headerProvider)
    }

    fun registerFor(provider: String, domain: String, headers: Map<String, String>) {
        if (headers.isNotEmpty()) registerFor(provider, domain) { headers }
    }

    /**
     * 注册域名后缀匹配规则，suffix 应以 "." 开头，如 ".quark.cn"。
     * HLS .ts 分片可能落在 CDN 边缘子域名上，后缀匹配可保证全子域名覆盖。
     */
    fun registerSuffix(suffix: String, headerProvider: () -> Map<String, String>) =
        registerSuffixFor(GLOBAL, suffix, headerProvider)

    fun registerSuffix(suffix: String, headers: Map<String, String>) {
        if (headers.isNotEmpty()) registerSuffixFor(GLOBAL, suffix) { headers }
    }

    fun registerSuffixFor(provider: String, suffix: String, headerProvider: () -> Map<String, String>) {
        val owner = owner(provider)
        suffixMatchers[owner to normalizeSuffix(suffix)] = bind(owner, headerProvider)
    }

    fun registerSuffixFor(provider: String, suffix: String, headers: Map<String, String>) {
        if (headers.isNotEmpty()) registerSuffixFor(provider, suffix) { headers }
    }

    fun registerAnonymous(domain: String, provider: String) {
        anonymousHosts[domain] = owner(provider)
    }

    /** 只有当前播放来源与注册来源一致时才算明确匿名。 */
    fun isExplicitlyAnonymous(domain: String): Boolean = anonymousHosts[domain] == owner(CloudAccountIdentity.boundProvider() ?: activeProvider)

    fun getHeaders(domain: String): Map<String, String> {
        exactMatchers.entries
            .filter { it.key.second == domain && eligible(it.key.first) }
            .minByOrNull { priority(it.key.first) }
            ?.let { return it.value.invoke() }

        return suffixMatchers.entries
            .filter { domain.endsWith(it.key.second) && eligible(it.key.first) }
            .minWithOrNull(compareBy({ priority(it.key.first) }, { -it.key.second.length }))
            ?.value?.invoke()
            ?: emptyMap()
    }

    private fun owner(provider: String): String {
        val effective = provider.ifBlank { CloudAccountIdentity.boundProvider() ?: activeProvider }
        return if (effective.isBlank()) GLOBAL else "$effective@${CloudAccountIdentity.active(effective)}"
    }
    private fun bind(owner: String, headers: () -> Map<String, String>): () -> Map<String, String> {
        if (owner == GLOBAL) return headers
        val provider = owner.substringBefore('@')
        val account = owner.substringAfter('@')
        return { CloudAccountIdentity.runAs(provider, account, headers) }
    }
    private fun eligible(owner: String): Boolean = owner == GLOBAL ||
        owner.substringAfter('@') == CloudAccountIdentity.active(owner.substringBefore('@'))

    private fun priority(owner: String): Int = when (owner) {
        owner(CloudAccountIdentity.boundProvider() ?: activeProvider) -> 0
        GLOBAL -> 1
        else -> 2
    }

    private fun normalizeSuffix(suffix: String) = if (suffix.startsWith(".")) suffix else ".$suffix"

    /**
     * 判断 URL 是否为预签名（自认证）链接。
     * 预签名 URL 通过查询参数携带签名信息，注入额外 Authorization 会破坏签名。
     */
    fun isSelfAuthenticatingUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.getQueryParameter("X-Amz-Algorithm") != null ||
            uri.getQueryParameter("OSSAccessKeyId") != null
    }

    fun clear() {
        activeProvider = ""
        exactMatchers.clear()
        suffixMatchers.clear()
        anonymousHosts.clear()
    }
}
