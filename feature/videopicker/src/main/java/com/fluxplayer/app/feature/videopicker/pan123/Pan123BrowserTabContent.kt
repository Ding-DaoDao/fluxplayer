package com.fluxplayer.app.feature.videopicker.pan123

import android.net.Uri
import androidx.activity.compose.BackHandler

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxplayer.app.core.ui.theme.FluxTheme
import com.fluxplayer.app.core.common.CloudUriScheme
import com.fluxplayer.app.core.common.onCloudMediaClick
import com.fluxplayer.app.core.common.onCloudVideoClick
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.core.ui.components.CancelButton
import com.fluxplayer.app.core.ui.components.DoneButton
import com.fluxplayer.app.core.ui.components.NextDialog
import com.fluxplayer.app.feature.videopicker.composables.CloudBrowserPanel as SharedCloudBrowserPanel
import com.fluxplayer.app.feature.videopicker.composables.ContextActionMenu
import com.fluxplayer.app.feature.videopicker.composables.CreateFolderDialog
import com.fluxplayer.app.feature.videopicker.composables.DownloadNotificationBar
import com.fluxplayer.app.feature.videopicker.composables.FolderPickerDialog
import com.fluxplayer.app.feature.videopicker.composables.ImageViewerScreen
import com.fluxplayer.app.feature.videopicker.composables.RenameDialog
import com.fluxplayer.app.feature.videopicker.composables.SharedShareBrowseDialog
import com.fluxplayer.app.feature.videopicker.composables.SharedShareTargetPickerDialog
import com.fluxplayer.app.feature.videopicker.composables.SortOption
import com.fluxplayer.app.feature.videopicker.composables.SortDropdownMenuContent
import com.fluxplayer.app.feature.videopicker.composables.formatFileSize

/**
 * 123云盘浏览器 Tab 内容
 */
@Composable
fun Pan123BrowserTabContent(
    onPlayVideo: (Uri, String?) -> Unit,
    onPlayVideos: (List<Uri>, Uri, Boolean) -> Unit,
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

    // 每次进入页面自动检测剪切板分享链接（冷启动、登录、切Tab、后台恢复）
    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) viewModel.detectClipboardShareUrl()
    }

    BackHandler(enabled = (state.breadcrumbs.size > 1 || state.isSearching || state.showShareBrowse) && state.isLoggedIn) {
        when {
            state.showShareBrowse -> viewModel.exitShareBrowse()
            state.isSearching -> viewModel.exitSearch()
            else -> viewModel.navigateUp()
        }
    }

    var contextMenuIndex by remember { mutableStateOf<Int?>(null) }
    var renameIndex by remember { mutableStateOf(-1) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var imageViewerIndex by remember { mutableIntStateOf(-1) }

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
            else if (item.isImage) imageViewerIndex = state.items.indexOf(item)
            else if (item.isAudio) onCloudMediaClick(
                item = item,
                allItems = state.items,
                mediaFilter = { it.isAudio },
                resolveUrl = { viewModel.resolveVideoUri(item) },
                buildPlaylistUri = { CloudUriScheme.buildCloudUri("pan123", it.path) },
                onPlayVideos = onPlayVideos,
                scope = scope,
            )
            else if (item.isVideo) onCloudVideoClick(
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
        notificationEvents = viewModel.messageEvents,
        providerMenuItems = { onDismiss ->
            DropdownMenuItem(
                text = { Text("盘内搜索") },
                onClick = { onDismiss(); viewModel.enterSearch() },
            )
            DropdownMenuItem(
                text = { Text("分享链接转存") },
                onClick = { onDismiss(); viewModel.showShareInput() },
            )
            DropdownMenuItem(
                text = { Text("账号信息") },
                onClick = { onDismiss(); viewModel.loadUserInfo() },
            )
        },
    )

    // 搜索栏
    if (state.isSearching) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = MaterialTheme.shapes.medium,
            shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                TextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("搜索文件...") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = { viewModel.submitSearch(state.searchQuery) }
                    ),
                )
                IconButton(onClick = { viewModel.submitSearch(state.searchQuery) }) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Search,
                        contentDescription = "搜索",
                    )
                }
                IconButton(onClick = { viewModel.exitSearch() }) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Close,
                        contentDescription = "关闭搜索",
                    )
                }
            }
        }
    }

    // 账号信息弹窗
    if (state.showAccountDialog) {
        AccountInfoDialog(
            userInfo = state.userInfo,
            onDismiss = { viewModel.dismissAccountDialog() }
        )
    }

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

        // 图片全屏查看器
        if (imageViewerIndex >= 0) {
            val allImages = state.items.filter { it.isImage }
            val clickedItem = state.items.getOrNull(imageViewerIndex)
            ImageViewerScreen(
                images = allImages,
                initialIndex = allImages.indexOf(clickedItem).coerceAtLeast(0),
                imageResolver = { viewModel.resolveImageUrl(it) },
                onClose = { imageViewerIndex = -1 },
            )
        }

        // === 分享链接输入弹窗 ===
        if (state.showShareInputDialog) {
            NextDialog(
                onDismissRequest = { viewModel.dismissShareInput() },
                title = { Text("打开123云盘分享链接") },
                content = {
                    OutlinedTextField(
                        value = state.shareInputText,
                        onValueChange = { viewModel.updateShareInputText(it) },
                        label = { Text("分享链接") },
                        placeholder = { Text("https://123865.com/s/...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    DoneButton(
                        onClick = { viewModel.openShareUrl(state.shareInputText) },
                    )
                },
                dismissButton = {
                    CancelButton(onClick = { viewModel.dismissShareInput() })
                },
            )
        }

        // === 分享浏览弹窗 ===
        if (state.showShareBrowse) {
            val backOrUp: () -> Unit = {
                if (state.shareBreadcrumbs.size > 1) viewModel.navigateShareUp()
                else viewModel.exitShareBrowse()
            }
            SharedShareBrowseDialog(
                title = "分享浏览",
                items = state.shareItems,
                targetBreadcrumbs = state.shareSaveTargetBreadcrumbs,
                breadcrumbLabel = { it.label },
                isLoading = state.shareIsLoading,
                canNavigateUp = state.shareBreadcrumbs.size > 1,
                subtitleProvider = { item ->
                    if (!item.isDirectory && item.size > 0) formatFileSize(item.size) else ""
                },
                onItemClick = { item -> viewModel.navigateShareFolder(item) },
                onSaveSelected = { indices -> viewModel.shareSaveFiles(indices) },
                onBack = backOrUp,
                onChangeTarget = { viewModel.showShareTargetFolderPicker() },
                onDismiss = { viewModel.exitShareBrowse() },
                onTargetBreadcrumbClick = { viewModel.shareTargetBreadcrumbClick(it) },
            )
        }

        // === 转存目标文件夹选择器 ===
        if (state.showShareTargetPicker) {
            SharedShareTargetPickerDialog(
                folders = state.shareTargetPickerFolders,
                isLoading = state.shareTargetPickerIsLoading,
                path = state.shareTargetPickerPath,
                breadcrumbLabel = { it.label },
                onDismiss = { viewModel.dismissShareTargetPicker() },
                onConfirmCurrent = { viewModel.selectShareTargetCurrentFolder() },
                onNavigateToFolder = { id, label -> viewModel.navigateShareTargetFolder(id, label) },
                onNavigateUp = { viewModel.navigateShareTargetUp() },
                onNavigateToIndex = { viewModel.navigateShareTargetToIndex(it) },
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

@Composable
private fun AccountInfoDialog(
    userInfo: com.fluxplayer.app.core.data.pan123.Pan123UserInfo?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("账号信息") },
        text = {
            if (userInfo == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 昵称 + UID
                    Text(
                        text = userInfo.nickname.ifBlank { "未设置昵称" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "UID: ${userInfo.uid}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // 存储空间
                    val used = userInfo.spaceUsed
                    val total = userInfo.spacePermanent
                    if (total > 0) {
                        val fraction = (used.toFloat() / total).coerceIn(0f, 1f)
                        Text(
                            text = "存储空间",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                        )
                        Text(
                            text = "${formatFileSize(used)} / ${formatFileSize(total)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // VIP
                    if (userInfo.isVip) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer,
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = userInfo.vipDesc.ifBlank { "VIP 会员" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                if (userInfo.vipTimeDesc.isNotBlank()) {
                                    Text(
                                        text = userInfo.vipTimeDesc,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "非会员",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

