package com.fluxplayer.app.core.tingshu

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.widget.Toast
import com.github.eprendre.tingshu.utils.Book
import java.io.File
import org.json.JSONObject

/** 听书兼容接口的宿主，配置与原有网盘账号分别存储。 */
object SourceHost {
    lateinit var context: Context
        private set

    val currentBook = ThreadLocal<Book?>()
    val extractedUrl = ThreadLocal<String?>()
    const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
    const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    fun getString(key: String, default: String?): String? =
        context.getSharedPreferences("tingshu_source_config", Context.MODE_PRIVATE).getString(key, default)

    fun putString(key: String, value: String?) {
        context.getSharedPreferences("tingshu_source_config", Context.MODE_PRIVATE).edit().putString(key, value).commit()
    }

    fun cookie(url: String): String? = CookieManager.getInstance().getCookie(url)

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
