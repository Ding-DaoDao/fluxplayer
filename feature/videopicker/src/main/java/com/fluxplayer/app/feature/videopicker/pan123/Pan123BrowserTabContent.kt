package com.fluxplayer.app.feature.videopicker.pan123

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxplayer.app.core.common.CloudUriScheme
import com.fluxplayer.app.core.common.onCloudVideoClick
import com.fluxplayer.app.feature.videopicker.composables.CloudBrowserPanel as SharedCloudBrowserPanel
import com.fluxplayer.app.feature.videopicker.composables.ContextActionMenu
import com.fluxplayer.app.feature.videopicker.composables.CreateFolderDialog
import com.fluxplayer.app.feature.videopicker.composables.DownloadNotificationBar
import com.fluxplayer.app.feature.videopicker.composables.FolderPickerDialog
import com.fluxplayer.app.feature.videopicker.composables.RenameDialog
import com.fluxplayer.app.feature.videopicker.composables.SortOption
import com.fluxplayer.app.feature.videopicker.composables.SortDropdownMenuContent

/**
 * 123云盘浏览器 Tab 内容
 */
@Composable
fun Pan123BrowserTabContent(
    onPlayVideo: (Uri, String?) -> Unit,
    onPlayVideos: (List<Uri>, Uri) -> Unit,
    onLogoutReady: (() -> Unit) -> Unit = {},
    onSettingsClick: () -> Unit = {},
    navigateToDirParam: Pair<String, String>? = null,
    onNavigateToDirConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: Pan123BrowserViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { onLogoutReady { viewModel.logout() } }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val navigationStack by viewModel.navigationStack.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // 从历史页面跳转到云盘指定目录
    LaunchedEffect(state.isLoggedIn, navigateToDirParam) {
        val (fileId, label) = navigateToDirParam ?: return@LaunchedEffect
        if (state.isLoggedIn) {
            viewModel.jumpToFolder(fileId, label)
            onNavigateToDirConsumed()
        }
    }

    BackHandler(enabled = state.breadcrumbs.size > 1 && state.isLoggedIn) {
        viewModel.navigateUp()
    }

    var contextMenuIndex by remember { mutableStateOf<Int?>(null) }
    var renameIndex by remember { mutableStateOf(-1) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    val currentSortKey = remember(state.orderBy, state.orderDirection) {
        val dir = state.orderDirection.lowercase()
        when (state.orderBy) {
            "file_name" -> if (dir == "desc") "name:desc" else "name:asc"
            "update_time" -> if (dir == "asc") "time:asc" else "time:desc"
            "size" -> if (dir == "asc") "size:asc" else "size:desc"
            else -> "name:asc"
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        SharedCloudBrowserPanel(
            modifier = Modifier.fillMaxSize(),
        items = state.items, breadcrumbs = state.breadcrumbs,
        isLoading = state.isLoading, isConfigured = state.isLoggedIn,
        error = state.error, isLoadingMore = state.isLoadingMore, reLoginRequired = false,
        navigationStack = navigationStack,
        onItemClick = { item ->
            if (item.isDirectory) viewModel.navigateToDir(state.items.indexOf(item))
            else onCloudVideoClick(
                item = item,
                allItems = state.items,
                resolveUrl = { viewModel.resolveVideoUri(item) },
                buildPlaylistUri = { CloudUriScheme.buildCloudUri("pan123", it.path) },
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
                        "name:asc", "name:desc" -> "file_name"
                        "time:asc", "time:desc" -> "update_time"
                        "size:asc", "size:desc" -> "size"
                        else -> "file_name"
                    }
                    val dir = if (option.key.endsWith(":desc")) "desc" else "asc"
                    viewModel.setSort(field, dir)
                },
                onDismiss = { showSortMenu = false },
            )
        },
        breadcrumbLabel = { it.label },
        loginContent = {
            if (state.initializing) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LoginScreen(
                    isLoading = state.isLoading,
                    error = state.error,
                    onLogin = { passport, password -> viewModel.login(passport, password) },
                    onLoginWithToken = { token -> viewModel.loginWithToken(token) }
                )
            }
        },
        onCreateFolder = { showCreateFolderDialog = true },
        providerName = "123云盘",
        onSettingsClick = onSettingsClick,
        onExitClick = { viewModel.logout() },
        playedUriSet = state.playedUriStrings,
        cloudProviderKey = "pan123",
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

    // 移动文件 —— 目标文件夹选择器
    if (state.pendingAction == "move") {
        FolderPickerDialog(
            action = "move",
            folders = state.pickerFolders,
            isLoading = state.pickerIsLoading,
            onDismiss = { viewModel.dismissPicker() },
            onConfirm = { targetFolderId -> viewModel.moveTo(targetFolderId) },
            onNavigateToFolder = { folderId -> viewModel.loadFoldersForPicker(folderId) },
            onCreateFolder = { parentFolderId, name -> viewModel.createFolderInPicker(parentFolderId, name) },
        )
    }


        // 下载进度
        downloadProgress?.let { dp ->
            DownloadNotificationBar(
                progress = dp.progress,
                fileName = dp.fileName,
                completedFilePath = dp.completedFilePath,
                downloadedBytes = dp.downloadedBytes,
                totalBytes = dp.totalBytes,
                onCancel = { viewModel.dismissDownloadProgress() },
                onOpenFile = { path -> viewModel.openDownloadedFile(path) },
                onDismiss = { viewModel.dismissDownloadProgress() },
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
            )
        }
    }

}

/**
 * 123云盘登录界面 — 支持手机号+密码 / Token 两种方式
 */
@Composable
fun LoginScreen(
    isLoading: Boolean,
    error: String?,
    onLogin: (String, String) -> Unit,
    onLoginWithToken: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var useToken by remember { mutableStateOf(false) }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = MaterialTheme.shapes.large,
            tonalElevation = 2.dp,
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("123云盘", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "请登录您的123云盘账号",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(20.dp))

                if (useToken) {
                    // Token 登录模式
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Bearer Token") },
                        singleLine = true,
                        enabled = !isLoading
                    )
                } else {
                    // 账号密码登录模式
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("手机号") },
                        singleLine = true,
                        enabled = !isLoading,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("密码") },
                        singleLine = true,
                        enabled = !isLoading,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }

                Spacer(Modifier.height(20.dp))

                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    val loginEnabled = if (useToken) token.isNotBlank()
                    else phone.isNotBlank() && password.isNotBlank()

                    Button(
                        onClick = {
                            if (useToken) onLoginWithToken(token)
                            else onLogin(phone, password)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = loginEnabled
                    ) {
                        Text("登录")
                    }
                }

                Spacer(Modifier.height(12.dp))

                TextButton(onClick = { useToken = !useToken }) {
                    Text(
                        if (useToken) "账号密码登录" else "使用Token登录",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

