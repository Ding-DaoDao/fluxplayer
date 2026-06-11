package dev.anilbeesetti.nextplayer.feature.videopicker.aliyun

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.common.CloudUriScheme
import dev.anilbeesetti.nextplayer.core.model.WebDavResource
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.CloudBrowserPanel as SharedCloudBrowserPanel
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.ContextActionMenu
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 阿里云盘浏览器 Tab 内容
 */
@Composable
fun AliyunBrowserTabContent(
    onPlayVideo: (Uri, String?) -> Unit,
    onPlayVideos: (List<Uri>, Uri) -> Unit,
    onLogoutReady: (() -> Unit) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AliyunBrowserViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { viewModel.tryRestoreSession() }
    LaunchedEffect(Unit) { onLogoutReady { viewModel.logout() } }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigationStack by viewModel.navigationStack.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // 返回键：在子目录时返回上级；在根目录或未登录时不拦截（让系统处理）
    BackHandler(enabled = state.isLoggedIn && state.breadcrumbs.size > 1) {
        viewModel.navigateUp()
    }

    var contextMenuIndex by remember { mutableStateOf<Int?>(null) }

    SharedCloudBrowserPanel(
        modifier = modifier,
        items = state.items,
        breadcrumbs = state.breadcrumbs,
        isLoading = state.isLoading,
        isConfigured = state.isLoggedIn,
        error = state.error,
        isLoadingMore = state.isLoadingMore,
        reLoginRequired = state.reLoginRequired,
        navigationStack = navigationStack,
        onItemClick = { item ->
            if (item.isDirectory) viewModel.navigateToDir(state.items.indexOf(item))
            else {
                scope.launch {
                    // 只解析点击的视频，其余视频用 cloud:// URI 按需加载
                    val clickedUri = viewModel.resolveVideoUri(item)
                    if (clickedUri != null) {
                        val videoItems = state.items.filter { !it.isDirectory }
                        val allUris = videoItems.map { CloudUriScheme.buildCloudUri("alipan", it.path) }
                        onPlayVideos(allUris, CloudUriScheme.buildCloudUri("alipan", item.path))
                    }
                }
            }
        },
        onItemMoreClick = { index -> contextMenuIndex = index },
        expandedMenuIndex = contextMenuIndex,
        onMenuDismiss = { contextMenuIndex = null },
        menuContent = { index, onDismiss ->
            val item = state.items.getOrNull(index)
            if (item != null) {
                ContextActionMenu(
                    item = item,
                    onDismiss = onDismiss,
                    onMove = { onDismiss(); viewModel.startMove(index) },
                    onCopy = { onDismiss(); viewModel.startCopy(index) },
                    onDelete = { onDismiss(); viewModel.deleteItem(index) },
                    onRename = { onDismiss() },
                    onDownload = { onDismiss(); viewModel.downloadFile(index) },
                )
            }
        },
        onBreadcrumbClick = { viewModel.navigateToBreadcrumb(it) },
        onRefresh = { viewModel.refresh() },
        onLoadMore = { viewModel.loadMore() },
        breadcrumbLabel = { it.label },
        loginContent = {
            AliyunLoginScreen(
                onLoginWithAuthorization = { auth -> viewModel.loginWithAuthorization(auth) },
                onLoginWithTokenJson = { json -> viewModel.loginWithTokenJson(json) },
                autoOpenWebView = state.reLoginRequired
            )
        }
    )
}

/**
 * 阿里云盘登录界面 —— 纯 WebView 登录
 *
 * 加载 PC 版阿里云盘页面，用户扫码或账号登录后
 * 自动从 localStorage / URL 参数中提取 refresh_token
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AliyunLoginScreen(
    onLoginWithAuthorization: (String) -> Unit,
    onLoginWithTokenJson: (String) -> Unit,
    modifier: Modifier = Modifier,
    autoOpenWebView: Boolean = false
) {
    var statusText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var loginTriggered by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶部状态提示
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLoading && !loginTriggered) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = statusText.ifEmpty { "正在加载阿里云盘登录页面..." },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        // WebView 登录
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // PC 端 User-Agent
                    settings.userAgentString =
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                    // 桌面模式渲染
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    // 禁用移动端适配
                    settings.setSupportMultipleWindows(false)
                    settings.allowFileAccess = false
                    settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                    CookieManager.getInstance().removeAllCookies(null)

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            isLoading = true
                            statusText = "正在加载..."
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (loginTriggered) return
                            isLoading = false
                            statusText = "请在页面中完成登录（扫码或账号密码）"

                            // 参考海阔视界：仅在登录成功跳转到 alipan.com/drive 后提取 token
                            if (!url.contains("alipan.com/drive")) return

                            view.evaluateJavascript(
                                "(function(){" +
                                " try{var t=localStorage.getItem('token');if(t)return t;}catch(e){}" +
                                " return'';" +
                                "})()"
                            ) { result ->
                                if (loginTriggered) return@evaluateJavascript
                                val raw = result?.trim('"') ?: ""
                                if (raw.isNotBlank() && raw != "null" && raw.length > 50) {
                                    try {
                                        val json = JSONObject(raw)
                                        val at = json.optString("access_token", "")
                                        if (at.isNotBlank()) {
                                            loginTriggered = true
                                            statusText = "登录成功，正在获取凭证..."
                                            onLoginWithTokenJson(raw)
                                        }
                                    } catch (_: Exception) {
                                        // JSON 解析失败，尝试提取 access_token
                                        val idx = raw.indexOf("access_token")
                                        if (idx >= 0) {
                                            val start = raw.indexOf('"', idx + 14)
                                            if (start >= 0) {
                                                val end = raw.indexOf('"', start + 1)
                                                if (end > start) {
                                                    loginTriggered = true
                                                    statusText = "登录成功，正在获取凭证..."
                                                    onLoginWithAuthorization("Bearer ${raw.substring(start + 1, end)}")
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    loadUrl("https://www.alipan.com/sign/in?spm=aliyundrive.index.0.0.7db16f60GgbJVZ")
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

