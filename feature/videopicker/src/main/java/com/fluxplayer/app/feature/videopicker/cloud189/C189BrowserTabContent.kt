package com.fluxplayer.app.feature.videopicker.cloud189

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxplayer.app.core.ui.theme.FluxTheme
import com.fluxplayer.app.core.common.CloudUriScheme
import com.fluxplayer.app.core.common.onCloudMediaClick
import com.fluxplayer.app.core.common.onCloudVideoClick
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.core.ui.components.DoneButton
import com.fluxplayer.app.core.ui.components.CancelButton
import com.fluxplayer.app.core.ui.components.NextDialog
import com.fluxplayer.app.core.ui.designsystem.NextIcons
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
import com.fluxplayer.app.feature.videopicker.composables.buildItemSubtitle
import com.fluxplayer.app.feature.videopicker.composables.formatFileSize

/**
 * 天翼云盘浏览器 Tab 内容
 */
@Composable
fun C189BrowserTabContent(
    onPlayVideo: (Uri, String?) -> Unit,
    onPlayVideos: (List<Uri>, Uri, Boolean) -> Unit,
    onLogoutReady: (() -> Unit) -> Unit = {},
    onSettingsClick: () -> Unit = {},
    navigateToDirParam: Pair<String, String>? = null,
    onNavigateToDirConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: C189BrowserViewModel = hiltViewModel()
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

    // 从后台恢复或切Tab回来时，自动检测剪切板中的分享链接
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.detectClipboardShareUrl()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = state.breadcrumbs.size > 1 && state.isLoggedIn) {
        viewModel.navigateUp()
    }

    var contextMenuIndex by remember { mutableStateOf<Int?>(null) }
    var renameIndex by remember { mutableStateOf(-1) }
    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var imageViewerIndex by remember { mutableIntStateOf(-1) }

    val currentSortKey = remember(state.orderBy, state.descending) {
        when {
            state.orderBy == "filename" && !state.descending -> "name:asc"
            state.orderBy == "filename" && state.descending -> "name:desc"
            state.orderBy == "lastOpTime" && state.descending -> "time:desc"
            state.orderBy == "lastOpTime" && !state.descending -> "time:asc"
            state.orderBy == "filesize" && state.descending -> "size:desc"
            state.orderBy == "filesize" && !state.descending -> "size:asc"
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
                buildPlaylistUri = { CloudUriScheme.buildCloudUri("cloud189", it.path) },
                onPlayVideos = onPlayVideos,
                scope = scope,
            )
            else if (item.isVideo) onCloudVideoClick(
                item = item,
                allItems = state.items,
                resolveUrl = { viewModel.resolveVideoUri(item) },
                buildPlaylistUri = { CloudUriScheme.buildCloudUri("cloud189", it.path) },
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
                        "name:asc", "name:desc" -> "filename"
                        "time:asc", "time:desc" -> "lastOpTime"
                        "size:asc", "size:desc" -> "filesize"
                        else -> "filename"
                    }
                    val desc = option.key.endsWith(":desc")
                    viewModel.setSort(field, desc)
                },
                onDismiss = { showSortMenu = false },
            )
        },
        breadcrumbLabel = { it.label },
        breadcrumbActions = null,
        loginContent = {
            C189LoginScreen(
                uiState = state,
                onLoginWithPassword = { phone, password -> viewModel.loginByPassword(phone, password) },
                onSendSmsCode = { phone -> viewModel.sendSmsCode(phone) },
                onLoginWithSms = { phone, code -> viewModel.loginBySms(phone, code) },
                onLoginWithCookies = { cookies -> viewModel.loginWithCookies(cookies) },
                onSwitchTab = { /* tab switching handled internally by C189LoginScreen */ }
            )
        },
        onCreateFolder = { showCreateFolderDialog = true },
        providerName = "天翼云盘",
        onSettingsClick = onSettingsClick,
        onExitClick = { viewModel.logout() },
        playedUriSet = state.playedUriSet,
        cloudProviderKey = "cloud189",
        notificationEvents = viewModel.messageEvents,
        providerMenuItems = { onDismiss ->
                DropdownMenuItem(
                    text = { Text("签到") },
                    onClick = { onDismiss(); viewModel.manualSign() },
                )
                DropdownMenuItem(
                    text = { Text("用户信息") },
                    onClick = { onDismiss(); viewModel.showUserInfo() },
                )
                DropdownMenuItem(
                    text = { Text("打开分享链接") },
                    onClick = { onDismiss(); viewModel.showShareInput() },
                )
            },
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
                title = { Text("打开天翼云盘分享链接") },
                content = {
                    OutlinedTextField(
                        value = state.shareInputText,
                        onValueChange = { viewModel.updateShareInputText(it) },
                        label = { Text("分享链接") },
                        placeholder = { Text("https://cloud.189.cn/t/...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    DoneButton(
                        onClick = {
                            viewModel.openShareUrl(state.shareInputText)
                        },
                    )
                },
                dismissButton = {
                    CancelButton(onClick = { viewModel.dismissShareInput() })
                },
            )
        }

        // === 用户信息弹窗 ===
        if (state.showUserInfoDialog) {
            NextDialog(
                onDismissRequest = { viewModel.dismissUserInfo() },
                title = { Text("账号信息") },
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (state.isLoading) {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            if (state.userNickname.isNotEmpty()) {
                                InfoRow("昵称", state.userNickname)
                            }
                            if (state.userPhone.isNotEmpty()) {
                                InfoRow("手机", state.userPhone)
                            }
                            if (state.userCapacity > 0) {
                                InfoRow("总空间", formatFileSize(state.userCapacity))
                            }
                            if (state.userAvailable > 0 || state.userCapacity > 0) {
                                InfoRow("剩余空间", formatFileSize(state.userAvailable))
                            }
                            InfoRow(
                                "会员",
                                if (state.userVipExpireTime.isNotEmpty()) "到期: ${state.userVipExpireTime}" else "非会员"
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    CancelButton(onClick = { viewModel.dismissUserInfo() })
                },
            )
        }

        // === 分享浏览弹窗 ===
        if (state.showShareBrowse) {
            SharedShareBrowseDialog(
                title = "分享: ${state.shareName}",
                items = state.shareItems,
                targetBreadcrumbs = state.shareSaveTargetBreadcrumbs,
                breadcrumbLabel = { it.label },
                isLoading = state.shareIsLoading,
                hasMore = state.shareHasMore,
                canNavigateUp = state.shareBreadcrumbs.size > 1,
                onItemClick = { item ->
                    if (item.isDirectory) viewModel.navigateShareFolder(item)
                },
                onSaveSelected = { indices -> viewModel.shareSaveFiles(indices) },
                onBack = { viewModel.exitShareBrowse() },
                onNavigateUp = { viewModel.navigateShareUp() },
                onChangeTarget = { viewModel.showShareTargetFolderPicker() },
                onDismiss = { viewModel.exitShareBrowse() },
                onTargetBreadcrumbClick = { viewModel.shareTargetBreadcrumbClick(it) },
            )
        }

        // === 分享转存目标文件夹选择器 ===
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

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 天翼云盘登录界面 —— 支持密码登录 / 短信登录 / Cookie 登录
 */
@Composable
fun C189LoginScreen(
    uiState: C189BrowserUiState,
    onLoginWithPassword: (String, String) -> Unit,
    onSendSmsCode: (String) -> Unit,
    onLoginWithSms: (String, String) -> Unit,
    onLoginWithCookies: (String) -> Unit,
    onSwitchTab: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(uiState.loginTab) }
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var smsCode by remember { mutableStateOf("") }

    // Cookie 登录折叠面板
    var showCookieLogin by remember { mutableStateOf(false) }
    var cookies by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("登录天翼云盘", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(20.dp))

        // Tab 切换
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            FilterChip(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0; onSwitchTab(0) },
                label = { Text("密码登录") },
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            FilterChip(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1; onSwitchTab(1) },
                label = { Text("短信登录") },
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        Spacer(Modifier.height(16.dp))

        // 错误提示
        if (uiState.error != null) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            ) {
                Text(
                    uiState.error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // 验证码发送成功提示
        if (uiState.smsSentMessage != null) {
            Text(
                uiState.smsSentMessage,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // 手机号输入框 (两种模式共用)
        OutlinedTextField(
            value = phone, onValueChange = { phone = it },
            label = { Text("手机号") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.loginLoading && !uiState.smsSending,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
        )
        Spacer(Modifier.height(8.dp))

        when (selectedTab) {
            // ========== 密码登录 ==========
            0 -> {
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("密码") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.loginLoading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onLoginWithPassword(phone, password) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = phone.isNotBlank() && password.isNotBlank() && !uiState.loginLoading
                ) {
                    if (uiState.loginLoading && selectedTab == 0) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("登录")
                }

                // Cookie 登录折叠入口
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { showCookieLogin = !showCookieLogin }) {
                    Text(if (showCookieLogin) "收起 Cookie 登录 ▲" else "Cookie 登录 ▼")
                }
                AnimatedVisibility(
                    visible = showCookieLogin,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = cookies, onValueChange = { cookies = it },
                            label = { Text("粘贴 Cookie 字符串") }, singleLine = false,
                            modifier = Modifier.fillMaxWidth().height(80.dp),
                            enabled = !uiState.loginLoading
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { onLoginWithCookies(cookies) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = cookies.isNotBlank() && !uiState.loginLoading
                        ) {
                            Text("Cookie 登录")
                        }
                    }
                }
            }

            // ========== 短信登录 ==========
            1 -> {
                // 获取验证码行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = smsCode, onValueChange = { smsCode = it },
                        label = { Text("验证码") }, singleLine = true,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        enabled = !uiState.loginLoading,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Button(
                        onClick = { onSendSmsCode(phone) },
                        enabled = phone.isNotBlank() && !uiState.smsSending && !uiState.loginLoading,
                        modifier = Modifier.height(56.dp)
                    ) {
                        if (uiState.smsSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("发送中")
                        } else {
                            Text(if (uiState.smsCodeSent) "重新发送" else "获取验证码")
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onLoginWithSms(phone, smsCode) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = phone.isNotBlank() && smsCode.isNotBlank() && !uiState.loginLoading
                ) {
                    if (uiState.loginLoading && selectedTab == 1) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("登录")
                }
            }
        }
    }
}

