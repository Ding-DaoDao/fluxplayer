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
import dev.anilbeesetti.nextplayer.core.common.onCloudVideoClick
import dev.anilbeesetti.nextplayer.core.model.WebDavResource
import android.widget.Toast
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.CloudBrowserPanel as SharedCloudBrowserPanel
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.ContextActionMenu
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.CreateFolderDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.RenameDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.SortOption
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.SortDropdownMenuContent

/**
 * 夸克网盘浏览器 Tab 内容
 */
@Composable
fun QuarkBrowserTabContent(
    onPlayVideo: (Uri, String?) -> Unit,
    onPlayVideos: (List<Uri>, Uri) -> Unit,
    onLogoutReady: (() -> Unit) -> Unit = {},
    driveType: String = "quark",
    onSettingsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: QuarkBrowserViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { viewModel.setDriveType(driveType) }
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
    var showSortMenu by remember { mutableStateOf(false) }

    val currentSortKey = remember(state.orderBy) {
        when (state.orderBy) {
            "file_name:asc" -> "name:asc"
            "file_name:desc" -> "name:desc"
            "updated_at:asc" -> "time:asc"
            "updated_at:desc" -> "time:desc"
            "size:asc" -> "size:asc"
            "size:desc" -> "size:desc"
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
        reLoginRequired = false,
        navigationStack = navigationStack,
        onItemClick = { item ->
            if (item.isDirectory) viewModel.navigateToDir(state.items.indexOf(item))
            else onCloudVideoClick(
                item = item,
                allItems = state.items,
                resolveUrl = { viewModel.resolveVideoUri(item) },
                buildPlaylistUri = { CloudUriScheme.buildCloudUri(if (state.driveType == "uc") "uc" else "quark", it.path) },
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
                    onCopy = { onDismiss(); Toast.makeText(viewModel.getApplication(), "暂不支持复制", Toast.LENGTH_SHORT).show() },
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
                    val providerOrderBy = when (option.key) {
                        "name:asc" -> "file_name:asc"
                        "name:desc" -> "file_name:desc"
                        "time:asc" -> "updated_at:asc"
                        "time:desc" -> "updated_at:desc"
                        "size:asc" -> "size:asc"
                        "size:desc" -> "size:desc"
                        else -> "file_name:asc"
                    }
                    viewModel.setSort(providerOrderBy)
                },
                onDismiss = { showSortMenu = false },
            )
        },
        breadcrumbLabel = { it.label },
        loginContent = {
            QuarkLoginScreen(
                driveType = state.driveType,
                onLoginWithCookie = { cookie, dt -> viewModel.loginWithCookie(cookie, dt) },
                loginUrl = if (driveType == "uc") "https://drive.uc.cn/" else "https://pan.quark.cn/",
                cookieDomains = if (driveType == "uc") listOf("drive.uc.cn") else listOf("pan.quark.cn", "drive-pc.quark.cn"),
            )
        },
        onCreateFolder = { showCreateFolderDialog = true },
        providerName = if (driveType == "uc") "UC网盘" else "夸克网盘",
        onSettingsClick = onSettingsClick,
        onExitClick = { viewModel.logout() }
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
 * 夸克网盘登录界面 —— 纯 WebView 登录
 *
 * 加载夸克网盘 PC 版页面，用户扫码或账号登录后自动提取 cookie 凭证
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun QuarkLoginScreen(
    driveType: String,
    onLoginWithCookie: (String, String) -> Unit,
    loginUrl: String = "https://pan.quark.cn/",
    cookieDomains: List<String> = listOf("pan.quark.cn", "drive-pc.quark.cn"),
    modifier: Modifier = Modifier
) {
    val providerName = if (driveType == "uc") "UC网盘" else "夸克网盘"
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
                    text = statusText.ifEmpty { "正在加载${providerName}登录页面..." },
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
                    // 清除所有旧 Cookie，确保夸克/UC 登录凭证完全隔离
                    val cm = CookieManager.getInstance()
                    cm.removeAllCookies(null)
                    cm.flush()

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            isLoading = true
                            statusText = "正在加载..."
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            if (loginTriggered) return
                            isLoading = false
                            statusText = "请在页面中完成登录（扫码或账号密码）"

                            val allCookies = cookieDomains
                                .map { domain ->
                                    CookieManager.getInstance().getCookie("https://$domain") ?: ""
                                }
                                .filter { it.isNotBlank() }
                                .joinToString("; ")

                            if (allCookies.isNotBlank() && allCookies.contains("__uid=")) {
                                loginTriggered = true
                                statusText = "登录成功，正在获取凭证..."
                                onLoginWithCookie(allCookies, driveType)
                            }
                        }
                    }
                    loadUrl(loginUrl)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

