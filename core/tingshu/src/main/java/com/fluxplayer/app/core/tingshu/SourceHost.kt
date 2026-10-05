package com.fluxplayer.app.core.tingshu

import android.content.Context
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
        return LoginInfo(url, if (desktop) DESKTOP_UA else MOBILE_UA)
    }

    data class LoginInfo(val url: String, val userAgent: String)

    fun toast(message: String) {
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
