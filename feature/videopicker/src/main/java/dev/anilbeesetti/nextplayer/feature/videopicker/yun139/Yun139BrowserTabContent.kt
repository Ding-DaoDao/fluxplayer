package dev.anilbeesetti.nextplayer.feature.videopicker.yun139

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.common.CloudUriScheme
import dev.anilbeesetti.nextplayer.core.model.WebDavResource
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.CloudBrowserPanel as SharedCloudBrowserPanel
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.ContextActionMenu
import kotlinx.coroutines.launch

/**
 * 移动云盘浏览器 Tab 内容
 */
@Composable
fun Yun139BrowserTabContent(
    onPlayVideo: (Uri, String?) -> Unit,
    onPlayVideos: (List<Uri>, Uri) -> Unit,
    onLogoutReady: (() -> Unit) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: Yun139BrowserViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { onLogoutReady { viewModel.logout() } }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    BackHandler(enabled = state.breadcrumbs.size > 1 && state.isLoggedIn) {
        viewModel.navigateUp()
    }

    var contextMenuIndex by remember { mutableStateOf<Int?>(null) }

    SharedCloudBrowserPanel(
        modifier = modifier,
        items = state.items, breadcrumbs = state.breadcrumbs,
        isLoading = state.isLoading, isConfigured = state.isLoggedIn,
        error = state.error, isLoadingMore = state.isLoadingMore, reLoginRequired = false,
        onItemClick = { item ->
            if (item.isDirectory) viewModel.navigateToDir(state.items.indexOf(item))
            else {
                scope.launch {
                    // 只解析点击的视频，其余视频用 cloud:// URI 按需加载
                    val clickedUri = viewModel.resolveVideoUri(item)
                    if (clickedUri != null) {
                        val videoItems = state.items.filter { !it.isDirectory }
                        val allUris = videoItems.map { CloudUriScheme.buildCloudUri("yun139", it.path) }
                        onPlayVideos(allUris, CloudUriScheme.buildCloudUri("yun139", item.path))
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
            Yun139LoginScreen(
                uiState = state,
                onLoginWithToken = { token -> viewModel.loginWithToken(token) },
                onLoginWithWeb = { auth, udId -> viewModel.loginWithToken(auth) },
                viewModel = viewModel
            )
        }
    )
}

/**
 * 移动云盘登录界面 —— 纯 WebView 登录
 *
 * 加载移动云盘 H5 页面，用户登录后自动提取 cookie 作为凭证
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Yun139LoginScreen(
    uiState: Yun139BrowserUiState,
    onLoginWithToken: (String) -> Unit,
    onLoginWithWeb: (String, String) -> Unit,
    viewModel: Yun139BrowserViewModel,
    modifier: Modifier = Modifier
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
                    text = statusText.ifEmpty { "正在加载移动云盘登录页面..." },
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
                    settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                    CookieManager.getInstance().removeAllCookies(null)
                    webViewClient = object : WebViewClient() {
                        private var authExtracted = false
                        private var jsChecked = false

                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            isLoading = true
                            statusText = "正在加载..."
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (loginTriggered) return
                            isLoading = false
                            statusText = "请在页面中完成登录"

                            // 每次页面加载完都尝试通过 JS 提取 token
                            if (!authExtracted) {
                                jsChecked = false
                                view.evaluateJavascript(
                                    "(function(){" +
                                    " try{var t=localStorage.getItem('token');if(t&&t.length>50)return t;}catch(e){}" +
                                    " try{var s=sessionStorage.getItem('token');if(s&&s.length>50)return s;}catch(e){}" +
                                    " return'';" +
                                    "})()"
                                ) { result ->
                                    jsChecked = true
                                    if (loginTriggered || authExtracted) return@evaluateJavascript
                                    val raw = result?.trim('"') ?: ""
                                    if (raw.isNotBlank() && raw != "null" && raw.length > 20) {
                                        authExtracted = true
                                        loginTriggered = true
                                        statusText = "登录成功，正在获取凭证..."
                                        onLoginWithToken(raw)
                                    }
                                }

                                // JS 回调是异步的，延迟 500ms 后再检查 cookie（给 JS 时间）
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    if (loginTriggered || authExtracted || !jsChecked) return@postDelayed
                                    val cookies = CookieManager.getInstance().getCookie(url) ?: ""
                                    if (cookies.isNotBlank() && cookies.length > 100) {
                                        authExtracted = true
                                        loginTriggered = true
                                        statusText = "登录成功，正在获取凭证..."
                                        onLoginWithToken(cookies)
                                    }
                                }, 500)
                            }
                        }
                    }
                    loadUrl("https://yun.139.com/")
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

