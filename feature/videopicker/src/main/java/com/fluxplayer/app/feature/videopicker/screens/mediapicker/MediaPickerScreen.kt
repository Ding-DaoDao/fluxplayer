package com.fluxplayer.app.feature.videopicker.screens.mediapicker

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.fluxplayer.app.core.common.storagePermission
import com.fluxplayer.app.core.media.services.MediaService
import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.model.Folder
import com.fluxplayer.app.core.model.MediaLayoutMode
import com.fluxplayer.app.core.model.MediaViewMode
import com.fluxplayer.app.core.model.Video
import com.fluxplayer.app.core.model.WebDavServer
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.base.DataState
import com.fluxplayer.app.core.ui.components.CancelButton
import com.fluxplayer.app.core.ui.components.DoneButton
import com.fluxplayer.app.core.ui.components.NextDialog
import com.fluxplayer.app.core.ui.components.NextTopAppBar
import com.fluxplayer.app.core.ui.composables.PermissionMissingView
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.extensions.copy
import com.fluxplayer.app.core.ui.preview.DayNightPreview
import com.fluxplayer.app.core.ui.preview.VideoPickerPreviewParameterProvider
import com.fluxplayer.app.core.model.ComposeEngine
import com.fluxplayer.app.core.ui.theme.FluxTheme
import com.fluxplayer.app.core.ui.theme.LocalHazeState
import com.fluxplayer.app.core.ui.theme.NextPlayerTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme
import dev.chrisbanes.haze.hazeEffect
import com.fluxplayer.app.core.ui.theme.FluxHazeStyle
import top.yukonga.miuix.kmp.basic.Scaffold as MiuixScaffold
import top.yukonga.miuix.kmp.basic.NavigationBar as MiuixNavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem as MiuixNavigationBarItem
import top.yukonga.miuix.kmp.basic.SmallTopAppBar as MiuixSmallTopAppBar
import com.fluxplayer.app.feature.videopicker.composables.CenterCircularProgressBar
import com.fluxplayer.app.feature.videopicker.openlist.OpenListBrowserTabContent
import com.fluxplayer.app.feature.videopicker.screens.history.HistoryTabContent
import com.fluxplayer.app.feature.videopicker.screens.mediapicker.BrowseTabs
import com.fluxplayer.app.feature.videopicker.screens.webdav.WebDavBrowserTabContent
import com.fluxplayer.app.feature.videopicker.composables.MediaView
import com.fluxplayer.app.feature.videopicker.composables.NoVideosFound
import com.fluxplayer.app.feature.videopicker.composables.QuickSettingsDialog
import com.fluxplayer.app.feature.videopicker.composables.RenameDialog
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import com.fluxplayer.app.feature.videopicker.composables.TextIconToggleButton
import com.fluxplayer.app.feature.videopicker.composables.VideoInfoDialog
import com.fluxplayer.app.feature.videopicker.state.SelectedFolder
import com.fluxplayer.app.feature.videopicker.state.SelectedVideo
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.fluxplayer.app.feature.videopicker.state.rememberSelectionManager

@Composable
fun MediaPickerRoute(
    viewModel: MediaPickerViewModel = hiltViewModel(),
    onPlayVideo: (uri: Uri, title: String?) -> Unit,
    onPlayVideos: (uris: List<Uri>, startUri: Uri, isAudioOnly: Boolean) -> Unit,
    onFolderClick: (folderPath: String) -> Unit,
    onSettingsClick: () -> Unit,
    onSearchClick: () -> Unit,
    onNavigateUp: () -> Unit,
    onWebDavClick: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activeWebDavServers by viewModel.activeWebDavServers.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    MediaPickerScreen(
        uiState = uiState,
        activeWebDavServers = activeWebDavServers,
        selectedTab = selectedTab,
        onTabSelected = { selectedTab = it },
        onPlayVideos = onPlayVideos,
        onNavigateUp = onNavigateUp,
        onPlayVideo = { uri, title ->
            viewModel.folderPath?.let { viewModel.recordFootprint(it, uri.toString()) }
            onPlayVideo(uri, title)
        },
        onFolderClick = { folderPath ->
            viewModel.folderPath?.let { viewModel.recordFootprint(it, folderPath) }
            onFolderClick(folderPath)
        },
        onSettingsClick = onSettingsClick,
        onSearchClick = onSearchClick,
        onWebDavClick = { selectedTab = 1 },
        onEvent = viewModel::onEvent,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalPermissionsApi::class)
@Composable
internal fun MediaPickerScreen(
    uiState: MediaPickerUiState,
    activeWebDavServers: List<WebDavServer> = emptyList(),
    selectedTab: Int = 0,
    onTabSelected: (Int) -> Unit = {},
    onNavigateUp: () -> Unit = {},
    onPlayVideo: (Uri, String?) -> Unit = { _, _ -> },
    onPlayVideos: (List<Uri>, Uri, Boolean) -> Unit = { _, _, _ -> },
    onFolderClick: (String) -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onSearchClick: () -> Unit = {},
    onWebDavClick: () -> Unit = {},
    onEvent: (MediaPickerUiEvent) -> Unit = {},
) {
    val selectionManager = rememberSelectionManager()
    val permissionState = rememberPermissionState(permission = storagePermission)
    val lazyGridState = rememberLazyGridState()
    val selectVideoFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { it?.let { onPlayVideo(it, null) } },
    )

    var isFabExpanded by rememberSaveable { mutableStateOf(false) }
    var showQuickSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var showUrlDialog by rememberSaveable { mutableStateOf(false) }

    var showRenameActionFor: Video? by rememberSaveable { mutableStateOf(null) }
    var showInfoActionFor: Video? by rememberSaveable { mutableStateOf(null) }
    var showDeleteVideosConfirmation by rememberSaveable { mutableStateOf(false) }

    var selectedProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var providerLogout by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showLogoutConfirmation by rememberSaveable { mutableStateOf(false) }
    // 从历史页面跳转到云盘目录的目标（fileId, label）
    var navigateToDirParam by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }




    val selectedItemsSize = selectionManager.selectedFolders.size + selectionManager.selectedVideos.size
    val totalItemsSize = (uiState.mediaDataState as? DataState.Success)?.value?.run { folderList.size + mediaList.size } ?: 0
    val hazeState = remember { HazeState() }
    val useFloatingBottomBar = uiState.preferences.useFloatingBottomBar
    val useLiquidGlass = useFloatingBottomBar
        && uiState.preferences.useLiquidGlass
        && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
    val backdrop = if (useLiquidGlass) rememberLayerBackdrop() else null

    CompositionLocalProvider(LocalHazeState provides hazeState) {
    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            val isMiuix = FluxTheme.engine == ComposeEngine.MIUIX
            if (selectedProvider != null && selectedTab == 1 && !selectionManager.isInSelectionMode) {
                // 已进入 provider → 不显示 Scaffold 顶栏，由 TabContent 内部 ProviderTopBar 接管
            } else if ((selectedTab == 1 || selectedTab == 2) && !selectionManager.isInSelectionMode) {
                if (isMiuix) {
                    MiuixSmallTopAppBar(
                        title = if (selectedTab == 1) stringResource(R.string.browse) else stringResource(R.string.history),
                        actions = {
                            IconButton(onClick = onSettingsClick) {
                                Icon(
                                    imageVector = NextIcons.Settings,
                                    contentDescription = stringResource(id = R.string.settings),
                                )
                            }
                        },
                    )
                } else {
                    NextTopAppBar(
                        title = if (selectedTab == 1) stringResource(R.string.browse) else stringResource(R.string.history),
                        fontWeight = FontWeight.Bold,
                        navigationIcon = {},
                        actions = {
                            IconButton(onClick = onSettingsClick) {
                                Icon(
                                    imageVector = NextIcons.Settings,
                                    contentDescription = stringResource(id = R.string.settings),
                                )
                            }
                        },
                    )
                }
            } else {
                if (isMiuix) {
                    // 视频列表页不启用顶栏模糊，避免透出后面的内容
                    MiuixSmallTopAppBar(
                        title = (uiState.folderName ?: stringResource(R.string.app_name)).takeIf { !selectionManager.isInSelectionMode } ?: "",
                        navigationIcon = {
                            if (selectionManager.isInSelectionMode) {
                                Row(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(MiuixTheme.colorScheme.surfaceVariant)
                                        .clickable { selectionManager.exitSelectionMode() }
                                        .padding(8.dp)
                                        .padding(end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = NextIcons.Close,
                                        contentDescription = stringResource(id = R.string.navigate_up),
                                    )
                                    Text(
                                        text = stringResource(R.string.m_n_selected, selectedItemsSize, totalItemsSize),
                                        style = MiuixTheme.textStyles.body1,
                                    )
                                }
                            } else if (uiState.folderName != null) {
                                FilledTonalIconButton(onClick = onNavigateUp) {
                                    Icon(
                                        imageVector = NextIcons.ArrowBack,
                                        contentDescription = stringResource(id = R.string.navigate_up),
                                    )
                                }
                            }
                        },
                        actions = {
                            if (selectionManager.isInSelectionMode) {
                                FilledTonalIconButton(
                                    onClick = {
                                        if (selectedItemsSize != totalItemsSize) {
                                            (uiState.mediaDataState as? DataState.Success)?.value?.let { folder ->
                                                folder.folderList.forEach { selectionManager.selectFolder(it) }
                                                folder.mediaList.forEach { selectionManager.selectVideo(it) }
                                            }
                                        } else {
                                            selectionManager.clearSelection()
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = if (selectedItemsSize != totalItemsSize) {
                                            NextIcons.SelectAll
                                        } else {
                                            NextIcons.DeselectAll
                                        },
                                        contentDescription = if (selectedItemsSize != totalItemsSize) {
                                            stringResource(R.string.select_all)
                                        } else {
                                            stringResource(R.string.deselect_all)
                                        },
                                    )
                                }
                            } else {
                                IconButton(onClick = onSearchClick) {
                                    Icon(
                                        imageVector = NextIcons.Search,
                                        contentDescription = stringResource(id = R.string.search),
                                    )
                                }
                                IconButton(onClick = { showQuickSettingsDialog = true }) {
                                    Icon(
                                        imageVector = NextIcons.DashBoard,
                                        contentDescription = stringResource(id = R.string.menu),
                                    )
                                }
                                IconButton(onClick = onSettingsClick) {
                                    Icon(
                                        imageVector = NextIcons.Settings,
                                        contentDescription = stringResource(id = R.string.settings),
                                    )
                                }
                            }
                        },
                    )
                } else {
                    NextTopAppBar(
                        title = (uiState.folderName ?: stringResource(R.string.app_name)).takeIf { !selectionManager.isInSelectionMode } ?: "",
                        fontWeight = FontWeight.Bold.takeIf { uiState.folderName == null },
                        navigationIcon = {
                            if (selectionManager.isInSelectionMode) {
                                Row(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                        .clickable { selectionManager.exitSelectionMode() }
                                        .padding(8.dp)
                                        .padding(end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        imageVector = NextIcons.Close,
                                        contentDescription = stringResource(id = R.string.navigate_up),
                                    )
                                    Text(
                                        text = stringResource(R.string.m_n_selected, selectedItemsSize, totalItemsSize),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            } else if (uiState.folderName != null) {
                                FilledTonalIconButton(onClick = onNavigateUp) {
                                    Icon(
                                        imageVector = NextIcons.ArrowBack,
                                        contentDescription = stringResource(id = R.string.navigate_up),
                                    )
                                }
                            }
                        },
                        actions = {
                            if (selectionManager.isInSelectionMode) {
                                FilledTonalIconButton(
                                    onClick = {
                                        if (selectedItemsSize != totalItemsSize) {
                                            (uiState.mediaDataState as? DataState.Success)?.value?.let { folder ->
                                                folder.folderList.forEach { selectionManager.selectFolder(it) }
                                                folder.mediaList.forEach { selectionManager.selectVideo(it) }
                                            }
                                        } else {
                                            selectionManager.clearSelection()
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = if (selectedItemsSize != totalItemsSize) {
                                            NextIcons.SelectAll
                                        } else {
                                            NextIcons.DeselectAll
                                        },
                                        contentDescription = if (selectedItemsSize != totalItemsSize) {
                                            stringResource(R.string.select_all)
                                        } else {
                                            stringResource(R.string.deselect_all)
                                        },
                                    )
                                }
                            } else {
                                IconButton(onClick = onSearchClick) {
                                    Icon(
                                        imageVector = NextIcons.Search,
                                        contentDescription = stringResource(id = R.string.search),
                                    )
                                }
                                IconButton(onClick = { showQuickSettingsDialog = true }) {
                                    Icon(
                                        imageVector = NextIcons.DashBoard,
                                        contentDescription = stringResource(id = R.string.menu),
                                    )
                                }
                                IconButton(onClick = onSettingsClick) {
                                    Icon(
                                        imageVector = NextIcons.Settings,
                                        contentDescription = stringResource(id = R.string.settings),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        },
        bottomBar = {
            if (selectionManager.isInSelectionMode && selectionManager.allSelectedVideos.isNotEmpty()) {
                SelectionActionsSheet(
                    show = true,
                    showRenameAction = selectionManager.isSingleVideoSelected,
                    showInfoAction = selectionManager.isSingleVideoSelected,
                    onPlayAction = {
                        val videoUris = selectionManager.allSelectedVideos.map { it.uriString.toUri() }
                        onPlayVideos(videoUris, videoUris.first(), false)
                        selectionManager.clearSelection()
                    },
                    onRenameAction = {
                        val selectedVideo = selectionManager.selectedVideos.firstOrNull() ?: return@SelectionActionsSheet
                        val video = (uiState.mediaDataState as? DataState.Success)?.value?.mediaList
                            ?.find { it.uriString == selectedVideo.uriString } ?: return@SelectionActionsSheet
                        showRenameActionFor = video
                    },
                    onInfoAction = {
                        val selectedVideo = selectionManager.selectedVideos.firstOrNull() ?: return@SelectionActionsSheet
                        val video = (uiState.mediaDataState as? DataState.Success)?.value?.mediaList
                            ?.find { it.uriString == selectedVideo.uriString } ?: return@SelectionActionsSheet
                        showInfoActionFor = video
                        selectionManager.clearSelection()
                    },
                    onShareAction = {
                        onEvent(MediaPickerUiEvent.ShareVideos(selectionManager.allSelectedVideos.map { it.uriString }))
                    },
                    onDeleteAction = {
                        if (MediaService.willSystemAsksForDeleteConfirmation()) {
                            onEvent(MediaPickerUiEvent.DeleteVideos(selectionManager.allSelectedVideos.map { it.uriString }))
                            selectionManager.clearSelection()
                        } else {
                            showDeleteVideosConfirmation = true
                        }
                    },
                )
            } else if (!useFloatingBottomBar) {
                // 标准 NavigationBar
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { onTabSelected(0) },
                        icon = {
                            Icon(
                                imageVector = NextIcons.Video,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(R.string.videos)) },
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { onTabSelected(1) },
                        icon = {
                            Icon(
                                imageVector = NextIcons.Folder,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(R.string.browse)) },
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { onTabSelected(2) },
                        icon = {
                            Icon(
                                imageVector = NextIcons.History,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(R.string.history)) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (selectionManager.isInSelectionMode || selectedTab != 0) return@Scaffold

            FloatingActionButtonMenu(
                modifier = Modifier.padding(bottom = 80.dp),
                expanded = isFabExpanded,
                button = {
                    ToggleFloatingActionButton(
                        checked = isFabExpanded,
                        onCheckedChange = { isFabExpanded = !isFabExpanded },
                    ) {
                        val icon by remember {
                            derivedStateOf {
                                if (checkedProgress > 0.5f) NextIcons.Close else NextIcons.Play
                            }
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.animateIcon(checkedProgress = { checkedProgress }),
                        )
                    }
                },
            ) {
                FloatingActionButtonMenuItem(
                    onClick = {
                        isFabExpanded = false
                        showUrlDialog = true
                    },
                    icon = {
                        Icon(
                            imageVector = NextIcons.Link,
                            contentDescription = null,
                        )
                    },
                    text = {
                        Text(text = stringResource(id = R.string.open_network_stream))
                    },
                )
                FloatingActionButtonMenuItem(
                    onClick = {
                        isFabExpanded = false
                        selectVideoFileLauncher.launch("video/*")
                    },
                    icon = {
                        Icon(
                            imageVector = NextIcons.FileOpen,
                            contentDescription = null,
                        )
                    },
                    text = {
                        Text(text = stringResource(id = R.string.open_local_video))
                    },
                )
                FloatingActionButtonMenuItem(
                    onClick = {
                        isFabExpanded = false
                        val folder = (uiState.mediaDataState as? DataState.Success)?.value ?: return@FloatingActionButtonMenuItem
                        val videoToPlay = folder.recentlyPlayedVideo ?: folder.firstVideo ?: return@FloatingActionButtonMenuItem
                        onPlayVideo(videoToPlay.uriString.toUri(), null)
                    },
                    icon = {
                        Icon(
                            imageVector = NextIcons.History,
                            contentDescription = null,
                        )
                    },
                    text = {
                        Text(text = stringResource(id = R.string.recently_played))
                    },
                )

            }
        },
        containerColor = if (FluxTheme.engine == ComposeEngine.MIUIX) {
            MiuixTheme.colorScheme.surface
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) { scaffoldPadding ->
        val contentPadding = if (useFloatingBottomBar) {
            PaddingValues(top = scaffoldPadding.calculateTopPadding())
        } else {
            scaffoldPadding
        }
        Crossfade(
            targetState = selectedTab,
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(280, easing = EaseOutCubic),
            label = "TabTransition",
        ) { tab ->
            when (tab) {
                0 -> {
                    when (uiState.mediaDataState) {
                        is DataState.Error -> {
                        }

                        is DataState.Loading -> {
                            CenterCircularProgressBar(modifier = Modifier.padding(contentPadding))
                        }

                        is DataState.Success -> {
                            val successFolder = uiState.mediaDataState.value
                            // Quick cards at root level (outside PullToRefreshBox, fixed header)
                            if (uiState.folderName == null && successFolder != null &&
                                !(successFolder.folderList.isEmpty() && successFolder.mediaList.isEmpty())
                            ) {
                                QuickCardsRow(
                                    folderCount = successFolder.folderList.size,
                                    videoCount = successFolder.mediaList.size,
                                    onWebDavClick = onWebDavClick,
                                )
                            }
                            PullToRefreshBox(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(top = contentPadding.calculateTopPadding())
                                    .padding(start = contentPadding.calculateStartPadding(LocalLayoutDirection.current))
                                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                                    .background(MaterialTheme.colorScheme.background),
                                isRefreshing = uiState.refreshing,
                                onRefresh = { onEvent(MediaPickerUiEvent.Refresh) },
                            ) {
                                val updatedScaffoldPadding = contentPadding.copy(top = 0.dp, start = 0.dp)
                                PermissionMissingView(
                                    isGranted = permissionState.status.isGranted,
                                    showRationale = permissionState.status.shouldShowRationale,
                                    permission = permissionState.permission,
                                    launchPermissionRequest = { permissionState.launchPermissionRequest() },
                                ) {
                                    val rootFolder = uiState.mediaDataState.value
                                    if (rootFolder == null || rootFolder.folderList.isEmpty() && rootFolder.mediaList.isEmpty()) {
                                        NoVideosFound(contentPadding = updatedScaffoldPadding)
                                        return@PermissionMissingView
                                    }

                                    MediaView(
                                        rootFolder = rootFolder,
                                        preferences = uiState.preferences,
                                        playedUriSet = uiState.playedUriSet,
                                        onFolderClick = onFolderClick,
                                        onVideoClick = { onPlayVideo(it, null) },
                                        selectionManager = selectionManager,
                                        lazyGridState = lazyGridState,
                                        contentPadding = updatedScaffoldPadding,
                                        onVideoLoaded = { onEvent(MediaPickerUiEvent.AddToSync(it)) },
                                    )
                                }
                            }
                        }
                    }
                }

                1 -> {
                    BrowseTabs(
                        onPlayVideo = onPlayVideo,
                        onPlayVideos = onPlayVideos,
                        onSettingsClick = onSettingsClick,
                        selectedProvider = selectedProvider,
                        onProviderSelected = { selectedProvider = it },
                        onProviderLogoutChanged = { providerLogout = it },
                        preferences = uiState.preferences,
                        onProviderReordered = { onEvent(MediaPickerUiEvent.ReorderProviders(it)) },
                        navigateToDirParam = navigateToDirParam,
                        onNavigateToDirConsumed = { navigateToDirParam = null },
                        webDavServers = activeWebDavServers,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(contentPadding),
                    )
                }

                2 -> {
                    HistoryTabContent(
                        onPlayVideo = onPlayVideo,
                        onPlayVideos = onPlayVideos,
                        onNavigateToCloudDir = { providerId, fileId, label ->
                            onTabSelected(1) // 切换到"浏览"Tab
                            selectedProvider = providerId
                            navigateToDirParam = fileId to label
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(contentPadding),
                    )
                }
            }
        }
    }

        // Floating bar overlay
        if (useFloatingBottomBar && !selectionManager.isInSelectionMode) {
            Box(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            ) {
                FloatingBottomBar(
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected,
                    backdrop = backdrop,
                    hazeState = hazeState,
                )
            }
        }
    }

    LaunchedEffect(lazyGridState.isScrollInProgress) {
        if (isFabExpanded && lazyGridState.isScrollInProgress) {
            isFabExpanded = false
        }
    }

    LaunchedEffect(selectionManager.isInSelectionMode) {
        if (selectionManager.isInSelectionMode) {
            isFabExpanded = false
        }
    }

    BackHandler(enabled = isFabExpanded) {
        isFabExpanded = false
    }

    BackHandler(enabled = selectionManager.isInSelectionMode) {
        selectionManager.exitSelectionMode()
    }

    if (showQuickSettingsDialog) {
        QuickSettingsDialog(
            applicationPreferences = uiState.preferences,
            onDismiss = { showQuickSettingsDialog = false },
            updatePreferences = { onEvent(MediaPickerUiEvent.UpdateMenu(it)) },
        )
    }

    if (showUrlDialog) {
        NetworkUrlDialog(
            onDismiss = { showUrlDialog = false },
            onDone = { onPlayVideo(it.toUri(), null) },
        )
    }

    showRenameActionFor?.let { video ->
        RenameDialog(
            name = video.displayName,
            onDismiss = { showRenameActionFor = null },
            onDone = {
                onEvent(MediaPickerUiEvent.RenameVideo(video.uriString.toUri(), it))
                showRenameActionFor = null
                selectionManager.clearSelection()
            },
        )
    }

    showInfoActionFor?.let { video ->
        VideoInfoDialog(
            video = video,
            onDismiss = { showInfoActionFor = null },
        )
    }

    if (showLogoutConfirmation) {
        LogoutConfirmationDialog(
            onConfirm = {
                providerLogout?.invoke()
                showLogoutConfirmation = false
            },
            onCancel = { showLogoutConfirmation = false },
        )
    }

    if (showDeleteVideosConfirmation) {
        DeleteConfirmationDialog(
            selectedVideos = selectionManager.selectedVideos,
            selectedFolders = selectionManager.selectedFolders,
            onConfirm = {
                onEvent(MediaPickerUiEvent.DeleteVideos(selectionManager.allSelectedVideos.map { it.uriString }))
                selectionManager.clearSelection()
                showDeleteVideosConfirmation = false
            },
            onCancel = { showDeleteVideosConfirmation = false },
        )
    }
    } // CompositionLocalProvider
}

@Composable
private fun LogoutConfirmationDialog(
    modifier: Modifier = Modifier,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    NextDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                text = stringResource(R.string.logout_confirmation_title),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = modifier,
            ) {
                Text(text = stringResource(R.string.logout))
            }
        },
        dismissButton = { CancelButton(onClick = onCancel) },
        modifier = modifier,
        content = {
            Text(
                text = stringResource(R.string.logout_confirmation_message),
                style = MaterialTheme.typography.titleSmall,
            )
        },
    )
}

@Composable
private fun DeleteConfirmationDialog(
    modifier: Modifier = Modifier,
    selectedVideos: Set<SelectedVideo>,
    selectedFolders: Set<SelectedFolder>,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    NextDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                text = when {
                    selectedVideos.isEmpty() -> when (selectedFolders.size) {
                        1 -> stringResource(R.string.delete_one_folder)
                        else -> stringResource(R.string.delete_folders, selectedFolders.size)
                    }

                    selectedFolders.isEmpty() -> when (selectedVideos.size) {
                        1 -> stringResource(R.string.delete_one_video)
                        else -> stringResource(R.string.delete_videos, selectedVideos.size)
                    }

                    else -> stringResource(R.string.delete_items, selectedFolders.size + selectedVideos.size)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = modifier,
            ) {
                Text(text = stringResource(R.string.delete))
            }
        },
        dismissButton = { CancelButton(onClick = onCancel) },
        modifier = modifier,
        content = {
            Text(
                text = if ((selectedFolders.size + selectedVideos.size) == 1) {
                    stringResource(R.string.delete_item_info)
                } else {
                    stringResource(R.string.delete_items_info)
                },
                style = MaterialTheme.typography.titleSmall,
            )
        },
    )
}

@Composable
private fun NetworkUrlDialog(
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    NextDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.network_stream)) },
        content = {
            Text(text = stringResource(R.string.enter_a_network_url))
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(text = stringResource(R.string.example_url)) },
            )
        },
        confirmButton = {
            DoneButton(
                enabled = url.isNotBlank(),
                onClick = { onDone(url) },
            )
        },
        dismissButton = { CancelButton(onClick = onDismiss) },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SelectionActionsSheet(
    modifier: Modifier = Modifier,
    show: Boolean,
    showRenameAction: Boolean,
    showInfoAction: Boolean,
    onPlayAction: () -> Unit,
    onRenameAction: () -> Unit,
    onShareAction: () -> Unit,
    onInfoAction: () -> Unit,
    onDeleteAction: () -> Unit,
) {
    AnimatedVisibility(
        modifier = modifier.padding(
            start = WindowInsets.displayCutout.asPaddingValues()
                .calculateStartPadding(LocalLayoutDirection.current),
        ),
        visible = show,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        val shape = MaterialTheme.shapes.largeIncreased.copy(
            bottomStart = ZeroCornerSize,
            bottomEnd = ZeroCornerSize,
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = shape,
                    )
                    .clip(shape)
                    .horizontalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(
                        horizontal = 8.dp,
                        vertical = 12.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                SelectionAction(
                    imageVector = NextIcons.Play,
                    title = stringResource(R.string.play),
                    onClick = onPlayAction,
                )
                if (showRenameAction) {
                    SelectionAction(
                        imageVector = NextIcons.Edit,
                        title = stringResource(R.string.rename),
                        onClick = onRenameAction,
                    )
                }
                SelectionAction(
                    imageVector = NextIcons.Share,
                    title = stringResource(R.string.share),
                    onClick = onShareAction,
                )
                if (showInfoAction) {
                    SelectionAction(
                        imageVector = NextIcons.Info,
                        title = stringResource(id = R.string.info),
                        onClick = onInfoAction,
                    )
                }
                SelectionAction(
                    imageVector = NextIcons.Delete,
                    title = stringResource(id = R.string.delete),
                    onClick = onDeleteAction,
                )
            }
        }
    }
}

@Composable
private fun SelectionAction(
    imageVector: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .defaultMinSize(
                minWidth = 75.dp,
                minHeight = 64.dp,
            )
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(
                horizontal = 16.dp,
                vertical = 8.dp,
            ),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = title,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(4.dp))
        Text(
            text = title,
            modifier = Modifier,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@PreviewScreenSizes
@PreviewLightDark
@Composable
private fun MediaPickerScreenPreview(
    @PreviewParameter(VideoPickerPreviewParameterProvider::class)
    videos: List<Video>,
) {
    NextPlayerTheme {
        MediaPickerScreen(
            uiState = MediaPickerUiState(
                folderName = null,
                mediaDataState = DataState.Success(
                    value = Folder(
                        name = "Root Folder",
                        path = "/root",
                        dateModified = System.currentTimeMillis(),
                        folderList = listOf(
                            Folder(name = "Folder 1", path = "/root/folder1", dateModified = System.currentTimeMillis()),
                            Folder(name = "Folder 2", path = "/root/folder2", dateModified = System.currentTimeMillis()),
                        ),
                        mediaList = videos,
                    ),
                ),
                preferences = ApplicationPreferences().copy(
                    mediaViewMode = MediaViewMode.FOLDER_TREE,
                    mediaLayoutMode = MediaLayoutMode.GRID,
                ),
            ),
        )
    }
}

@Preview
@Composable
private fun ButtonPreview() {
    Surface {
        TextIconToggleButton(
            text = "Title",
            icon = NextIcons.Title,
            onClick = {},
        )
    }
}

@DayNightPreview
@Composable
private fun MediaPickerNoVideosFoundPreview() {
    NextPlayerTheme {
        Surface {
            MediaPickerScreen(
                uiState = MediaPickerUiState(
                    folderName = null,
                    mediaDataState = DataState.Success(null),
                    preferences = ApplicationPreferences(),
                ),
            )
        }
    }
}

@Composable
private fun QuickCardsRow(
    folderCount: Int,
    videoCount: Int,
    onWebDavClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Local Videos card
        Card(
            modifier = Modifier
                .weight(1f)
                .clickable { /* stays on current screen */ },
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = NextIcons.Video,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "本地视频",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        text = "$folderCount 个文件夹 · $videoCount 个视频",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
            }
        }

        // WebDAV card
        Card(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onWebDavClick),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = NextIcons.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "WebDAV",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Text(
                        text = "浏览远程服务器文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "›",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun FloatingBottomBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    backdrop: LayerBackdrop?,
    hazeState: HazeState,
) {
    val navBarModifier = if (backdrop != null) {
        Modifier
    } else {
        Modifier.hazeEffect(
            state = hazeState,
            style = FluxHazeStyle.bottomBarStyle(),
        )
    }
    if (backdrop != null) {
        // 液态玻璃模式：三层叠加底栏（Legado 架构）
        val tabsCount = 3
        val tabsBackdrop = rememberLayerBackdrop()
        val combinedBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)
        val indicatorAnim = remember { Animatable(selectedTab.toFloat()) }
        val pressAnim = remember { Animatable(0f) }

        LaunchedEffect(selectedTab) {
            indicatorAnim.animateTo(
                selectedTab.toFloat(),
                spring(dampingRatio = 0.6f, stiffness = 400f),
            )
        }

        val selectedContentColor = MaterialTheme.colorScheme.onPrimaryContainer
        val unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        val tabs = listOf(
            Triple(NextIcons.Video, stringResource(R.string.videos), 0),
            Triple(NextIcons.Folder, stringResource(R.string.browse), 1),
            Triple(NextIcons.History, stringResource(R.string.history), 2),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // Layer 1: 主容器背景（vibrancy + blur + lens + highlight + shadow）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(percent = 50) },
                        effects = {
                            vibrancy()
                            blur(25f.dp.toPx())
                            lens(24f.dp.toPx(), 24f.dp.toPx())
                        },
                        highlight = { Highlight.Ambient },
                        shadow = { Shadow.Default },
                    ),
            ) {
                tabs.forEach { _ -> Spacer(modifier = Modifier.weight(1f)) }
            }

            // Layer 2: 透明标签层（为指示器提供取色源）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop),
            ) {
                tabs.forEach { _ -> Spacer(modifier = Modifier.weight(1f)) }
            }

            // Layer 3: 选中指示器（combinedBackdrop + lens 色散 + shadow + innerShadow）
            BoxWithConstraints(modifier = Modifier.matchParentSize()) {
                val tabWidth = maxWidth / tabsCount
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            translationX = indicatorAnim.value * size.width
                        }
                        .width(tabWidth)
                        .height(56.dp)
                        .drawBackdrop(
                            backdrop = combinedBackdrop,
                            shape = { RoundedCornerShape(percent = 50) },
                            effects = {
                                lens(
                                    10f.dp.toPx(),
                                    14f.dp.toPx(),
                                    depthEffect = true,
                                    chromaticAberration = true,
                                )
                            },
                            shadow = { Shadow.Default.copy(color = Color.Black.copy(alpha = 0.15f)) },
                            innerShadow = { InnerShadow.Default },
                        ),
                )
            }

            // 按钮层（接收触摸事件）
            Row(modifier = Modifier.fillMaxWidth().height(56.dp)) {
                tabs.forEach { (icon, label, index) ->
                    val isSelected = selectedTab == index
                    val interactionSource = remember { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()

                    LaunchedEffect(isPressed) {
                        if (isPressed) {
                            pressAnim.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 300f))
                        } else {
                            pressAnim.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 250f))
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .graphicsLayer {
                                val scale = 1f + 0.08f * pressAnim.value
                                scaleX = scale
                                scaleY = scale
                            }
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                            ) { onTabSelected(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (isSelected) selectedContentColor else unselectedContentColor,
                            )
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) selectedContentColor else unselectedContentColor,
                            )
                        }
                    }
                }
            }
        }
    } else {
        // 毛玻璃模式：标准 NavigationBar
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.8f),
            modifier = navBarModifier,
        ) {
            NavigationBarItem(
                selected = selectedTab == 0,
                onClick = { onTabSelected(0) },
                icon = {
                    Icon(
                        imageVector = NextIcons.Video,
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(R.string.videos)) },
            )
            NavigationBarItem(
                selected = selectedTab == 1,
                onClick = { onTabSelected(1) },
                icon = {
                    Icon(
                        imageVector = NextIcons.Folder,
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(R.string.browse)) },
            )
            NavigationBarItem(
                selected = selectedTab == 2,
                onClick = { onTabSelected(2) },
                icon = {
                    Icon(
                        imageVector = NextIcons.History,
                        contentDescription = null,
                    )
                },
                label = { Text("历史") },
            )
        }
    }
}

@DayNightPreview
@Composable
private fun MediaPickerLoadingPreview() {
    NextPlayerTheme {
        Surface {
            MediaPickerScreen(
                uiState = MediaPickerUiState(
                    folderName = null,
                    mediaDataState = DataState.Loading,
                    preferences = ApplicationPreferences(),
                ),
            )
        }
    }
}
