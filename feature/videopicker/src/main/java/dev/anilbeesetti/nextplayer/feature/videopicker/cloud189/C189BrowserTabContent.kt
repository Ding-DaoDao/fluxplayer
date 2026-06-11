package dev.anilbeesetti.nextplayer.feature.videopicker.cloud189

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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.common.CloudUriScheme
import dev.anilbeesetti.nextplayer.core.common.onCloudVideoClick
import dev.anilbeesetti.nextplayer.core.model.WebDavResource
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.CloudBrowserPanel as SharedCloudBrowserPanel
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.ContextActionMenu
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.CreateFolderDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.RenameDialog
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.SortOption
import dev.anilbeesetti.nextplayer.feature.videopicker.composables.SortOptionSheet

/**
 * 天翼云盘浏览器 Tab 内容
 */
@Composable
fun C189BrowserTabContent(
    onPlayVideo: (Uri, String?) -> Unit,
    onPlayVideos: (List<Uri>, Uri) -> Unit,
    onLogoutReady: (() -> Unit) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: C189BrowserViewModel = hiltViewModel()
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
    var showSortSheet by remember { mutableStateOf(false) }

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

    SharedCloudBrowserPanel(
        modifier = modifier,
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
        onSortClick = { showSortSheet = true },
        breadcrumbLabel = { it.label },
        loginContent = {
            C189LoginScreen(
                uiState = state,
                onLoginWithPassword = { phone, password -> viewModel.loginByPassword(phone, password) }
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

    if (showSortSheet) {
        SortOptionSheet(
            currentKey = currentSortKey,
            onSelect = { option ->
                showSortSheet = false
                val field = when (option.key) {
                    "name:asc", "name:desc" -> "filename"
                    "time:asc", "time:desc" -> "lastOpTime"
                    "size:asc", "size:desc" -> "filesize"
                    else -> "filename"
                }
                val desc = option.key.endsWith(":desc")
                viewModel.setSort(field, desc)
            },
            onDismiss = { showSortSheet = false },
        )
    }
}

/**
 * 天翼云盘登录界面 —— 手机号+密码登录
 */
@Composable
fun C189LoginScreen(
    uiState: C189BrowserUiState,
    onLoginWithPassword: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("登录天翼云盘", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))

        if (uiState.error != null) {
            Text(uiState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = phone, onValueChange = { phone = it },
            label = { Text("手机号") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.loginLoading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("密码") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.loginLoading,
            // visualTransformation = PasswordVisualTransformation(),  // 明文显示便于调试
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onLoginWithPassword(phone, password) },
            modifier = Modifier.fillMaxWidth(),
            enabled = phone.isNotBlank() && password.isNotBlank() && !uiState.loginLoading
        ) {
            if (uiState.loginLoading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
            }
            Text("登录")
        }
    }
}

