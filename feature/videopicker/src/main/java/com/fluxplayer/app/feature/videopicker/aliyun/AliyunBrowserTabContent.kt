package com.fluxplayer.app.feature.videopicker.aliyun

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxplayer.app.core.common.CloudUriScheme
import com.fluxplayer.app.core.common.onCloudVideoClick
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.feature.videopicker.composables.CloudBrowserPanel as SharedCloudBrowserPanel
import com.fluxplayer.app.feature.videopicker.composables.ContextActionMenu
import com.fluxplayer.app.feature.videopicker.composables.CreateFolderDialog
import com.fluxplayer.app.feature.videopicker.composables.RenameDialog
import com.fluxplayer.app.feature.videopicker.composables.SortOption
import com.fluxplayer.app.feature.videopicker.composables.SortDropdownMenuContent
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
    onSettingsClick: () -> Unit = {},
    navigateToDirParam: Pair<String, String>? = null,
    onNavigateToDirConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: AliyunBrowserViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { viewModel.tryRestoreSession() }
    LaunchedEffect(Unit) { onLogoutReady { viewModel.logout() } }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigationStack by viewModel.navigationStack.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // 从历史页面跳转到云盘指定目录
    LaunchedEffect(state.isLoggedIn, navigateToDirParam) {
        val (fileId, label) = navigateToDirParam ?: return@LaunchedEffect
        if (state.isLoggedIn) {
            viewModel.jumpToFolder(fileId, label)
            onNavigateToDirConsumed()
        }
    }

    // 返回键：在子目录时返回上级；在根目录或未登录时不拦截（让系统处理）
    BackHandler(enabled = state.isLoggedIn && state.breadcrumbs.size > 1) {
        viewModel.navigateUp()
    }

    var contextMenuIndex by remember { mutableStateOf<Int?>(null) }
    var renameIndex by remember { mutableStateOf(-1) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showDriveMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    val currentSortKey = remember(state.orderBy) {
        when (state.orderBy) {
            "name:ASC" -> "name:asc"
            "name:DESC" -> "name:desc"
            "updated_at:ASC" -> "time:asc"
            "updated_at:DESC" -> "time:desc"
            "size:ASC" -> "size:asc"
            "size:DESC" -> "size:desc"
            else -> "name:asc"
        }
    }

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
            else onCloudVideoClick(
                item = item,
                allItems = state.items,
                resolveUrl = { viewModel.resolveVideoUri(item) },
                buildPlaylistUri = { CloudUriScheme.buildCloudUri("alipan", it.path) },
                onPlayVideos = onPlayVideos,
                scope = scope,
            )
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
        onSortClick = { showSortMenu = true },
        showSortMenu = showSortMenu,
        onSortMenuDismiss = { showSortMenu = false },
        sortMenuContent = {
            SortDropdownMenuContent(
                currentKey = currentSortKey,
                onSelect = { option ->
                    showSortMenu = false
                    val field = when (option.key) {
                        "name:asc" -> "name"
                        "name:desc" -> "name"
                        "time:asc" -> "updated_at"
                        "time:desc" -> "updated_at"
                        "size:asc" -> "size"
                        "size:desc" -> "size"
                        else -> "name"
                    }
                    val dir = if (option.key.endsWith(":desc")) "DESC" else "ASC"
                    viewModel.setSort(field, dir)
                },
                onDismiss = { showSortMenu = false },
            )
        },
        breadcrumbLabel = { it.label },
        breadcrumbActions = {
            if (state.driveOptions.size > 1) {
                val currentName = state.driveOptions.find { it.driveId == state.currentDriveId }?.name
                    ?: state.driveOptions.firstOrNull()?.name ?: "切换驱动"
                Box {
                    TextButton(onClick = { showDriveMenu = true }) {
                        Text(
                            text = currentName,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = showDriveMenu,
                        onDismissRequest = { showDriveMenu = false },
                    ) {
                        state.driveOptions.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = option.name,
                                        fontWeight = if (option.driveId == state.currentDriveId)
                                            FontWeight.Bold else FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    showDriveMenu = false
                                    if (option.driveId != state.currentDriveId) {
                                        viewModel.switchDrive(option.driveId)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
        loginContent = {
            AliyunLoginScreen(
                onLoginWithAuthorization = { auth -> viewModel.loginWithAuthorization(auth) },
                onLoginWithTokenJson = { json -> viewModel.loginWithTokenJson(json) },
                autoOpenWebView = state.reLoginRequired
            )
        },
        onCreateFolder = { showCreateFolderDialog = true },
        providerName = "阿里云盘",
        onSettingsClick = onSettingsClick,
        onExitClick = { viewModel.logout() },
        playedUriSet = state.playedUriSet,
        cloudProviderKey = "alipan",
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
                    text = statusText.ifEmpty {
                        if (autoOpenWebView) "正在尝试自动续期..." else "正在加载阿里云盘登录页面..."
                    },
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
                    // 自动续期模式下不清除 Cookie，保留已有登录会话
                    if (!autoOpenWebView) {
                        CookieManager.getInstance().removeAllCookies(null)
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            isLoading = true
                            statusText = if (autoOpenWebView) "正在检测登录状态..." else "正在加载..."
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (loginTriggered) return
                            isLoading = false
                            statusText = when {
                                autoOpenWebView && url.contains("alipan.com/drive") -> "正在获取凭证..."
                                autoOpenWebView -> "会话已过期，请在页面中完成登录（扫码或账号密码）"
                                else -> "请在页面中完成登录（扫码或账号密码）"
                            }

                            // 参考海阔视界：仅在登录成功跳转到 alipan.com/drive 后提取 token
                            if (!url.contains("alipan.com/drive")) return

                            view.evaluateJavascript(
                                "(function(){" +
                                " try{var t=localStorage.getItem('token');if(t)return t;}catch(e){}" +
                                " return'';" +
                                "})()"
                            ) { result ->
                                if (loginTriggered) return@evaluateJavascript
                                // evaluateJavascript 将返回值包装为 JSON 字符串，需正确解码
                                val raw = try {
                                    JSONObject("{ \"_v\": $result }").optString("_v", "")
                                } catch (_: Exception) {
                                    result?.trim('"') ?: ""
                                }
                                if (raw.isNotBlank() && raw != "null" && raw.length > 50) {
                                    try {
                                        val json = JSONObject(raw)
                                        val at = json.optString("access_token", "")
                                        if (at.isNotBlank()) {
                                            loginTriggered = true
                                            statusText = "登录成功，正在获取凭证..."
                                            onLoginWithTokenJson(raw)
                                        } else {
                                            statusText = "未能获取access_token，请返回重试"
                                            loginTriggered = false
                                        }
                                    } catch (_: Exception) {
                                        statusText = "登录信息解析失败，请返回重试"
                                        loginTriggered = false
                                    }
                                }
                            }
                        }
                    }
                    // 自动续期模式：先尝试 /drive 页面（利用 WebView 已有会话自动提取 token）
                    // 首次登录模式：直接跳到登录页
                    if (autoOpenWebView) {
                        loadUrl("https://www.alipan.com/drive")
                    } else {
                        loadUrl("https://www.alipan.com/sign/in?spm=aliyundrive.index.0.0.7db16f60GgbJVZ")
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

