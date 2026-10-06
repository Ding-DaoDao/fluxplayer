package com.fluxplayer.app.core.tingshu

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.widget.Toast
import com.github.eprendre.tingshu.sources.ILogin
import com.github.eprendre.tingshu.sources.TingShu
import com.github.eprendre.tingshu.utils.Book
import java.io.File
import org.json.JSONObject

/** 听书兼容接口的宿主，配置与原有网盘账号分别存储。 */
object SourceHost {
    lateinit var context: Context
        private set

    val currentBook = ThreadLocal<Book?>()
    val extractedUrl = ThreadLocal<String?>()
    private val capturedMessages = ThreadLocal<MutableList<String>?>()

    internal fun <T> captureMessages(block: (MutableList<String>) -> T): T {
        val previous = capturedMessages.get()
        val messages = mutableListOf<String>()
        capturedMessages.set(messages)
        try {
            return block(messages)
        } finally {
            if (previous == null) capturedMessages.remove() else capturedMessages.set(previous)
        }
    }

    /**
     * 手机版 UA。用设备真实型号而非固定值——网盘 H5 登录页（尤其移动云盘的
     * cmpassport 认证 SDK）会按 UA 走不同分支，写死 Android 13/Chrome 120
     * 会让页面脚本找不到预期 DOM 而崩溃。取值与原生网盘浏览器 Yun139BrowserTabContent 保持一致。
     */
    val MOBILE_UA: String by lazy {
        val release = android.os.Build.VERSION.RELEASE?.takeIf { it.isNotBlank() } ?: "14"
        val model = android.os.Build.MODEL?.takeIf { it.isNotBlank() } ?: "Pixel 8"
        "Mozilla/5.0 (Linux; Android $release; $model) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.6649.40 Mobile Safari/537.36"
    }

    const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    fun getString(key: String, default: String?): String? =
        context.getSharedPreferences("tingshu_source_config", Context.MODE_PRIVATE).getString(key, default)

    fun putString(key: String, value: String?) {
        context.getSharedPreferences("tingshu_source_config", Context.MODE_PRIVATE).edit().putString(key, value).commit()
    }

    fun cookie(url: String): String? = CookieManager.getInstance().getCookie(url).also { value ->
        // 书源常把失败静默吞掉（如根目录未命中时直接返回空列表），登录态排查只能靠这里。
        // 只打印域名、长度与键名，绝不打印凭证值。
        if (value == null) {
            Log.d(LOG_TAG, "cookie($url) -> null")
        } else {
            val keys = value.split(';')
                .mapNotNull { part -> part.substringBefore('=').trim().takeIf { it.isNotEmpty() } }
            Log.d(LOG_TAG, "cookie($url) -> len=${value.length} keys=${keys.joinToString(",")}")
        }
    }

    private const val LOG_TAG = "SourceHost"

    /**
     * 写回 Cookie。部分源（如 Quark）会在解析过程中主动把新拿到的 Cookie 写回系统，
     * 让后续 WebView 请求与 API 请求共享同一份登录态。
     */
    fun setCookie(url: String, cookieValue: String) {
        CookieManager.getInstance().setCookie(url, cookieValue)
    }

    /**
     * WebView 登录入口信息。仅当书源实现了 [ILogin] 且给出可用地址时返回 null 表示不支持。
     *
     * 书源侧（天翼/夸克/移动）都已实现该接口并给出登录页地址，缺的只是 app 侧的
     * WebView 承载，因此这里只做类型判定与参数提取。
     */
    fun loginInfo(source: TingShu): LoginInfo? {
        val login = source as? ILogin ?: return null
        val url = runCatching { login.getLoginUrl() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val desktop = runCatching { login.isLoginDesktop() }.getOrDefault(false)
        val (domain, markers) = loginMarker(url) ?: return null
        return LoginInfo(url, if (desktop) DESKTOP_UA else MOBILE_UA, domain, markers)
    }

    data class LoginInfo(
        val url: String,
        val userAgent: String,
        /** 探测登录态用的 cookie 域，通常与登录页同域 */
        val cookieDomain: String,
        /** 判定「已登录」所需的关键 cookie 名，任一命中即视为已登录 */
        val markerCookies: List<String>,
    )

    /**
     * 按登录页地址推导登录态判定规则。
     *
     * [ILogin] 只声明了登录页地址，没有状态查询方法，而登录态实际就落在系统
     * CookieManager 里，所以这里按各家已验证的关键 cookie 名做探测：
     * - 夸克 `pan.quark.cn`：核心 cookie `__puus`（挂在 `.quark.cn` 泛域）
     * - 移动云盘 `yun.139.com`：`authorization`
     * - 天翼 `cloud.189.cn`：登录后即有任意 cookie，其凭证是换来的
     *   accessToken/sessionKey，不靠 cookie 判定
     *
     * 书源改用其他网盘时返回 null，UI 侧退化为不显示状态。
     */
    fun loginMarker(loginUrl: String): Pair<String, List<String>>? {
        val host = Uri.parse(loginUrl).host?.lowercase() ?: return null
        return when {
            host.endsWith("quark.cn") -> "https://$host" to listOf("__puus")
            host.endsWith("139.com") -> "https://$host" to listOf("authorization")
            host.endsWith("189.cn") -> "https://$host" to emptyList()
            else -> null
        }
    }

    /** 依据登录态判定规则探测是否已登录；无规则时返回 null 表示未知。 */
    fun isLoggedIn(info: LoginInfo): Boolean? {
        // 直接读 CookieManager 而不走 cookie()，避免每次状态刷新都打一条日志
        val raw = CookieManager.getInstance().getCookie(info.cookieDomain)
        if (raw.isNullOrBlank()) return false
        if (info.markerCookies.isEmpty()) return true
        val names = raw.split(';')
            .mapNotNull { part -> part.substringBefore('=').trim().takeIf { it.isNotEmpty() } }
        return info.markerCookies.any { marker -> names.any { it.equals(marker, ignoreCase = true) } }
    }

    fun toast(message: String) {
        capturedMessages.get()?.let {
            it.add(message)
            return
        }
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    fun cacheDir(sourceId: String, subDir: String): File {
        val parent = File(context.cacheDir, "tingshu/${digest(sourceId)}").apply { mkdirs() }
        val child = File(parent, subDir).canonicalFile
        require(child == parent.canonicalFile || child.path.startsWith(parent.canonicalPath + File.separator)) {
            "无效的书源缓存目录"
        }
        return child.apply { mkdirs() }
    }

    fun saveLyrics(lyrics: HashMap<String, String>, sourceId: String, bookUrl: String) {
        File(cacheDir(sourceId, "lyrics"), "${digest(bookUrl)}.json").writeText(JSONObject(lyrics as Map<*, *>).toString())
    }

    fun publish(url: String) {
        require(url.isNotBlank()) { "书源没有返回播放地址" }
        extractedUrl.set(url)
    }
}

internal fun digest(value: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
