package com.fluxplayer.app.feature.tingshu

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.fluxplayer.app.core.tingshu.SourceHost

/**
 * 书源 WebView 登录页。
 *
 * 天翼/夸克/移动等网盘书源实现了 `ILogin`，需要在 App 内打开真实登录页，
 * 让 Cookie 落进系统级 [CookieManager]；书源随后用 `SourceHost.cookie()` 读出
 * 并手动塞进 API 请求的 header（书源侧用 Fuel 发请求，自身不处理 Cookie）。
 *
 * 流程：书源声明 `getLoginUrl()` → 本页加载该地址 → 用户在页内完成登录 →
 * Cookie 由 WebView 自动写入 CookieManager → 返回 App 即可。
 * 登录是否成功由书源自行校验（它有各自的凭证解析逻辑），此处不做判断。
 */
class SourceLoginActivity : ComponentActivity() {

    private var webView: WebView? = null

    companion object {
        /** 便于 `adb logcat -s SourceLogin` 观察页面加载与 JS 报错 */
        private const val LOG_TAG = "SourceLogin"
        private const val EXTRA_URL = "url"
        private const val EXTRA_UA = "ua"
        private const val EXTRA_SOURCE_NAME = "name"

        fun intent(context: Context, sourceName: String, url: String, userAgent: String): Intent {
            Log.i(LOG_TAG, "启动登录页 source=$sourceName url=$url desktopUA=${userAgent == SourceHost.DESKTOP_UA}")
            return Intent(context, SourceLoginActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_UA, userAgent)
                .putExtra(EXTRA_SOURCE_NAME, sourceName)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        val userAgent = intent.getStringExtra(EXTRA_UA).orEmpty()
        val sourceName = intent.getStringExtra(EXTRA_SOURCE_NAME).orEmpty()

        // Cookie 必须落盘，否则进程被杀后登录态丢失，用户每次都要重新登录
        CookieManager.getInstance().setAcceptCookie(true)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val web = webView
                    if (web != null && web.canGoBack()) web.goBack() else finish()
                }
            },
        )

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LoginPage(
                        sourceName = sourceName,
                        url = url,
                        userAgent = userAgent,
                        onClose = {
                            CookieManager.getInstance().flush()
                            setResult(RESULT_OK)
                            finish()
                        },
                        onWebViewReady = { webView = it },
                    )
                }
            }
        }
    }

    @Composable
    private fun LoginPage(
        sourceName: String,
        url: String,
        userAgent: String,
        onClose: () -> Unit,
        onWebViewReady: (WebView) -> Unit,
    ) {
        var loading by remember { mutableStateOf(true) }
        var pageError by remember { mutableStateOf<String?>(null) }
        var desktop by remember { mutableStateOf(userAgent == SourceHost.DESKTOP_UA) }

        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (sourceName.isBlank()) "登录网盘" else "$sourceName 登录",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClose) { Text("完成") }
            }
            Row {
                TextButton(onClick = {
                    pageError = null
                    webView?.reload()
                }) { Text("重新加载") }
                TextButton(onClick = {
                    desktop = !desktop
                    pageError = null
                    webView?.apply {
                        settings.userAgentString = if (desktop) SourceHost.DESKTOP_UA else WebSettings.getDefaultUserAgent(context)
                        loadUrl(url)
                    }
                }) { Text(if (desktop) "切换手机版" else "切换电脑版") }
            }
            pageError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }

            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LoginWebView(
                url = url,
                userAgent = userAgent,
                onWebViewReady = onWebViewReady,
                onLoadingChange = { loading = it },
                onError = { pageError = it },
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun LoginWebView(
        url: String,
        userAgent: String,
        onWebViewReady: (WebView) -> Unit,
        onLoadingChange: (Boolean) -> Unit,
        onError: (String?) -> Unit,
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val web = WebView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    // 部分网盘登录页要 PC 版 UA，源通过 isLoginDesktop() 指定
                    if (userAgent.isNotBlank()) {
                        settings.userAgentString = userAgent
                    }
                    // 网盘登录页常混用 http 资源（如中移动认证 SDK），必须完全放开，
                    // 否则脚本加载失败会直接白屏。取值与原生网盘浏览器一致。
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                            Log.i(LOG_TAG, "console[${msg.messageLevel()}] ${msg.message()} @${msg.lineNumber()}")
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, u: String?, favicon: Bitmap?) {
                            Log.i(LOG_TAG, "pageStarted $u")
                            onLoadingChange(true)
                            onError(null)
                        }

                        override fun onPageFinished(view: WebView?, u: String?) {
                            Log.i(LOG_TAG, "pageFinished $u")
                            onLoadingChange(false)
                            // 登录完成后立刻落盘，不等进程被杀
                            CookieManager.getInstance().flush()
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?,
                        ) {
                            Log.w(LOG_TAG, "加载失败 ${request?.url} code=${error?.errorCode} ${error?.description}")
                            if (request?.isForMainFrame == true) {
                                onLoadingChange(false)
                                onError("网页加载失败：${error?.description ?: "网络不可用"}，可重新加载或切换网页版本")
                            }
                        }

                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
                            if (request?.isForMainFrame == true) {
                                onLoadingChange(false)
                                onError("登录网站返回 HTTP ${response?.statusCode}，请稍后重新加载")
                            }
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): Boolean {
                            val target = request?.url?.toString().orEmpty()
                            // 拦截 mcloud:// / intent:// 等外部 scheme：网盘登录常跳自家App，
                            // 放行会让 WebView 直接白屏（页面被交给外部应用处理）。
                            if (target.isNotBlank() && !target.startsWith("http://") && !target.startsWith("https://")) {
                                Log.w(LOG_TAG, "拦截外部 scheme: $target")
                                return true
                            }
                            // http/https 正常加载，跳转第三方授权域时刷新 Cookie
                            CookieManager.getInstance().flush()
                            return false
                        }

                        @Suppress("DEPRECATION")
                        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                            val target = url.orEmpty()
                            if (target.isNotBlank() && !target.startsWith("http://") && !target.startsWith("https://")) {
                                Log.w(LOG_TAG, "拦截外部 scheme(deprecated): $target")
                                return true
                            }
                            CookieManager.getInstance().flush()
                            return false
                        }
                    }
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
                onWebViewReady(web)
                web.loadUrl(url)
                web
            },
        )
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        webView?.let { web ->
            web.stopLoading()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        webView = null
        super.onDestroy()
    }
}
