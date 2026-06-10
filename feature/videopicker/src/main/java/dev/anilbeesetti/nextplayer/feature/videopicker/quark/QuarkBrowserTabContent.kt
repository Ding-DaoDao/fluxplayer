package dev.anilbeesetti.nextplayer.feature.videopicker.quark

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

/**
 * 夸克网盘浏览器 Tab 内容
 */
@Composable
fun QuarkBrowserTabContent(
    onPlayVideo: (Uri, String?) -> Unit,
    onPlayVideos: (List<Uri>, Uri) -> Unit,
    onLogoutReady: (() -> Unit) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: QuarkBrowserViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { viewModel.setDriveType("quark") }
    LaunchedEffect(Unit) { onLogoutReady { viewModel.logout() } }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    BackHandler(enabled = state.breadcrumbs.size > 1 && state.isLoggedIn) {
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
        reLoginRequired = false,
        onItemClick = { item ->
            if (item.isDirectory) viewModel.navigateToDir(state.items.indexOf(item))
            else {
                scope.launch {
                    // 只解析点击的视频，其余视频用 cloud:// URI 按需加载
                    val clickedUri = viewModel.resolveVideoUri(item)
                    if (clickedUri != null) {
                        val provider = if (state.driveType == "uc") "uc" else "quark"
                        val videoItems = state.items.filter { !it.isDirectory }
                        val allUris = videoItems.map { CloudUriScheme.buildCloudUri(provider, it.path) }
                        onPlayVideos(allUris, CloudUriScheme.buildCloudUri(provider, item.path))
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
                    onCopy = { onDismiss() },
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
            QuarkLoginScreen(
                driveType = state.driveType,
                onLoginWithCookie = { cookie, dt -> viewModel.loginWithCookie(cookie, dt) }
            )
        }
    )
}

/**
 * 夸克网盘登录界面 —— 纯 WebView 登录
 *
 * 加载夸克网盘 PC 版页面，用户扫码或账号登录后自动提取 cookie 凭证
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun QuarkLoginScreen(
    driveType: String,
    onLoginWithCookie: (String, String) -> Unit,
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
                    text = statusText.ifEmpty { "正在加载夸克网盘登录页面..." },
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
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            isLoading = true
                            statusText = "正在加载..."
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (loginTriggered) return
                            isLoading = false
                            statusText = "请在页面中完成登录（扫码或账号密码）"

                            // 获取 pan.quark.cn 的 cookie
                            val panCookies = CookieManager.getInstance().getCookie("https://pan.quark.cn") ?: ""
                            // 也获取 drive-pc.quark.cn 的 cookie（登录后可能同时设置）
                            val driveCookies = CookieManager.getInstance().getCookie("https://drive-pc.quark.cn") ?: ""

                            val allCookies = listOf(panCookies, driveCookies)
                                .filter { it.isNotBlank() }
                                .joinToString("; ")

                            if (allCookies.isNotBlank() && allCookies.contains("__uid=")) {
                                loginTriggered = true
                                statusText = "登录成功，正在获取凭证..."
                                onLoginWithCookie(allCookies, driveType)
                            }
                        }
                    }
                    loadUrl("https://pan.quark.cn/")
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

