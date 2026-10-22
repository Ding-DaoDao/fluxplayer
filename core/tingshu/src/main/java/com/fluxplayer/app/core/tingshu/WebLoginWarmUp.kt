package com.fluxplayer.app.core.tingshu

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView

/**
 * 书源登录页预热。
 *
 * 第一次 `new WebView(context)` 要拉起系统 WebView（Chromium）提供者，冷启动常见几百毫秒到一秒多；
 * 再加上登录页本身的 DNS/TLS 和首屏资源，用户点「打开网页登录」后会看到一段空窗。
 *
 * 这里在配置面板打开、且当前源支持网页登录时先建一个离屏 WebView 把登录页预载一次，
 * 真正进登录页时只需渲染。只是尽力而为：预热失败不影响登录流程，两分钟后自动释放。
 */
object WebLoginWarmUp {
    private const val KEEP_MS = 120_000L

    private val handler = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var loadedUrl = ""
    private var releaseTask: Runnable? = null

    /** 预热指定登录页；同一地址只加载一次。 */
    fun warm(context: Context, url: String, userAgent: String) {
        if (url.isBlank()) return
        val app = context.applicationContext
        handler.post {
            val view = web ?: runCatching { create(app) }.getOrNull() ?: return@post
            web = view
            if (loadedUrl == url) return@post
            if (userAgent.isNotBlank()) view.settings.userAgentString = userAgent
            loadedUrl = url
            runCatching { view.loadUrl(url) }
            releaseTask?.let(handler::removeCallbacks)
            releaseTask = Runnable { release() }.also { handler.postDelayed(it, KEEP_MS) }
        }
    }

    /** 登录页已经打开或面板关闭时调用，及时归还内存。 */
    fun release() {
        handler.post {
            releaseTask?.let(handler::removeCallbacks)
            releaseTask = null
            web?.let {
                runCatching {
                    it.stopLoading()
                    it.destroy()
                }
            }
            web = null
            loadedUrl = ""
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(context: Context): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
    }
}
