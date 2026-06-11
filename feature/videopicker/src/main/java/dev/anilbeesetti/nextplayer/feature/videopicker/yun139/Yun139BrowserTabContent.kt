package dev.anilbeesetti.nextplayer.feature.videopicker.yun139

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.CreateFolderDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.RenameDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "Yun139Login"

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
    val navigationStack by viewModel.navigationStack.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    BackHandler(enabled = state.breadcrumbs.size > 1 && state.isLoggedIn) {
        viewModel.navigateUp()
    }

    var contextMenuIndex by remember { mutableStateOf<Int?>(null) }
    var renameIndex by remember { mutableStateOf(-1) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }

    SharedCloudBrowserPanel(
        modifier = modifier,
        items = state.items, breadcrumbs = state.breadcrumbs,
        isLoading = state.isLoading, isConfigured = state.isLoggedIn,
        error = state.error, isLoadingMore = state.isLoadingMore, reLoginRequired = false,
        navigationStack = navigationStack,
        onItemClick = { item ->
            if (item.isDirectory) viewModel.navigateToDir(state.items.indexOf(item))
            else {
                scope.launch {
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
                    onRename = { renameIndex = index; onDismiss() },
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
                onLoginWithWeb = { auth, udId -> viewModel.loginWithWeb(auth, udId) }
            )
        },
        onCreateFolder = { showCreateFolderDialog = true }
    )

    val renameItem = state.items.getOrNull(renameIndex)
    if (renameItem != null) {
        RenameDialog(
            name = renameItem.name,
            onDismiss = { renameIndex = -1 },
            onDone = { newName -> viewModel.renameItem(renameIndex, newName); renameIndex = -1 },
        )
    }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onDismiss = { showCreateFolderDialog = false },
            onCreate = { name -> viewModel.createDirectory(name); showCreateFolderDialog = false },
        )
    }
}

/**
 * 移动云盘登录界面 — 纯 WebView 登录
 *
 * 加载移动云盘 PC 版登录页，用户登录后通过协程轮询 CookieManager 提取凭证。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Yun139LoginScreen(
    onLoginWithWeb: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var statusText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    var loginTriggered by remember { mutableStateOf(false) }

    // Cookie 轮询提取 —— 当 isLoading 变为 false 时启动
    LaunchedEffect(isLoading) {
        if (isLoading || loginTriggered) return@LaunchedEffect
        statusText = "请在页面中登录移动云盘"
        while (isActive && !loginTriggered) {
            val cookies = CookieManager.getInstance().getCookie("https://yun.139.com") ?: ""
            val authMatch = Regex("authorization=([^;]+)").find(cookies)
            val udMatch = Regex("ud_id=([^;]+)").find(cookies)

            if (authMatch != null && udMatch != null) {
                loginTriggered = true
                val auth = authMatch.groupValues[1]
                val udId = udMatch.groupValues[1]
                Log.d(TAG, "Credential extracted: auth=${auth.take(20)}..., udId=$udId")
                statusText = "登录成功，正在获取凭证..."
                onLoginWithWeb(auth, udId)
                return@LaunchedEffect
            }
            delay(300)
        }
    }

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
                try {
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        settings.userAgentString = UA_PC
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.databaseEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false

                        CookieManager.getInstance().removeAllCookies(null)

                        webChromeClient = WebChromeClient()

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                isLoading = true
                                statusText = "正在加载..."
                                Log.d(TAG, "onPageStarted: $url")
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                if (loginTriggered) return
                                isLoading = false
                                statusText = "请在页面中登录移动云盘"
                                Log.d(TAG, "onPageFinished: $url")
                            }

                            // 拦截外部 scheme（mcloud://、intent:// 等），防止页面跳转客户端导致白屏
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest
                            ): Boolean {
                                val url = request.url.toString()
                                Log.d(TAG, "shouldOverrideUrlLoading: $url")
                                if (url.startsWith("http://") || url.startsWith("https://")) {
                                    return false // 正常加载
                                }
                                // 拦截所有非 http/https scheme
                                Log.w(TAG, "Blocked external scheme: $url")
                                return true
                            }

                            @Suppress("DEPRECATION")
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                url: String
                            ): Boolean {
                                Log.d(TAG, "shouldOverrideUrlLoading(deprecated): $url")
                                if (url.startsWith("http://") || url.startsWith("https://")) {
                                    return false
                                }
                                Log.w(TAG, "Blocked external scheme: $url")
                                return true
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: android.webkit.WebResourceError
                            ) {
                                if (request.isForMainFrame) {
                                    Log.e(TAG, "onReceivedError: ${error.description} url=${request.url}")
                                    statusText = "页面加载失败: ${error.description}"
                                }
                            }

                            @Suppress("DEPRECATION")
                            override fun onReceivedError(
                                view: WebView,
                                errorCode: Int,
                                description: String,
                                failingUrl: String
                            ) {
                                Log.e(TAG, "onReceivedError(deprecated): $description")
                                statusText = "页面加载失败: $description"
                            }
                        }

                        loadUrl("https://yun.139.com/m/#/login")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "WebView factory failed", e)
                    statusText = "WebView初始化失败: ${e.message}"
                    WebView(ctx)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

private const val UA_PC = "Mozilla/5.0 (Linux; Android 14; 24031PN0DC) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6649.40 Mobile Safari/537.36"
