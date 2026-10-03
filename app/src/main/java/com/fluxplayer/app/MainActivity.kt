package com.fluxplayer.app

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dagger.hilt.android.AndroidEntryPoint
import com.fluxplayer.app.core.common.storagePermission
import com.fluxplayer.app.core.data.backup.AutoBackupHelper
import com.fluxplayer.app.core.media.services.MediaService
import com.fluxplayer.app.core.media.sync.MediaSynchronizer
import com.fluxplayer.app.core.model.AccentPreset
import com.fluxplayer.app.core.model.SurfaceStyle
import com.fluxplayer.app.core.model.ThemeConfig
import com.fluxplayer.app.core.ui.theme.NextPlayerTheme
import com.fluxplayer.app.navigation.MediaRootRoute
import com.fluxplayer.app.navigation.mediaNavGraph
import com.fluxplayer.app.navigation.settingsNavGraph
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var synchronizer: MediaSynchronizer

    @Inject
    lateinit var mediaService: MediaService

    @Inject
    lateinit var autoBackupHelper: AutoBackupHelper

    private val viewModel: MainViewModel by viewModels()

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaService.initialize(this@MainActivity)

        var uiState: MainActivityUiState by mutableStateOf(MainActivityUiState.Loading)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    uiState = state
                }
            }
        }

        installSplashScreen().setKeepOnScreenCondition {
            when (uiState) {
                MainActivityUiState.Loading -> true
                is MainActivityUiState.Success -> false
            }
        }

        setContent {
            val shouldUseDarkTheme = shouldUseDarkTheme(uiState = uiState)

            LaunchedEffect(shouldUseDarkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        lightScrim = Color.TRANSPARENT,
                        darkScrim = Color.TRANSPARENT,
                        detectDarkMode = { shouldUseDarkTheme },
                    ),
                    navigationBarStyle = SystemBarStyle.auto(
                        lightScrim = Color.TRANSPARENT,
                        darkScrim = Color.TRANSPARENT,
                        detectDarkMode = { shouldUseDarkTheme },
                    ),
                )
            }

            NextPlayerTheme(
                darkTheme = shouldUseDarkTheme,
                highContrastDarkTheme = shouldUseHighContrastDarkTheme(uiState = uiState),
                accentPreset = shouldUseAccentPreset(uiState = uiState),
                surfaceStyle = shouldUseSurfaceStyle(uiState = uiState),
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    val permissionsToRequest = remember {
                        buildList {
                            add(storagePermission)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    }
                    val permissionsState = rememberMultiplePermissionsState(permissions = permissionsToRequest)

                    // 仅首次进入时自动请求，避免永久拒绝后每次回到前台都重复弹系统权限框
                    var hasRequestedPermissions by rememberSaveable { mutableStateOf(false) }

                    LifecycleEventEffect(event = Lifecycle.Event.ON_START) {
                        if (!permissionsState.allPermissionsGranted && !hasRequestedPermissions) {
                            hasRequestedPermissions = true
                            permissionsState.launchMultiplePermissionRequest()
                        }
                    }

                    val storageGranted = permissionsState.permissions.firstOrNull {
                        it.permission == storagePermission
                    }?.status?.isGranted ?: false

                    LaunchedEffect(storageGranted) {
                        if (storageGranted) {
                            synchronizer.startSync()
                        }
                    }

                    // MANAGE_EXTERNAL_STORAGE 引导（Android 11+）
                    var showManageStorageDialog by remember { mutableStateOf(false) }
                    val hasRequestedManageStorage = remember { mutableStateOf(false) }

                    LaunchedEffect(storageGranted) {
                        if (storageGranted
                            && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            && !Environment.isExternalStorageManager()
                            && !hasRequestedManageStorage.value
                        ) {
                            showManageStorageDialog = true
                            hasRequestedManageStorage.value = true
                        }
                    }

                    val manageStorageLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) {
                        // 返回后无需额外处理
                    }

                    if (showManageStorageDialog) {
                        AlertDialog(
                            onDismissRequest = { showManageStorageDialog = false },
                            title = { Text("需要所有文件访问权限") },
                            text = { Text("Flux Player 需要「所有文件访问」权限才能完整浏览和管理您的视频文件。请在接下来的设置页面中开启此权限。") },
                            confirmButton = {
                                TextButton(onClick = {
                                    showManageStorageDialog = false
                                    manageStorageLauncher.launch(
                                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                    )
                                }) { Text("去开启") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showManageStorageDialog = false }) { Text("稍后再说") }
                            },
                        )
                    }

                    val mainNavController = rememberNavController()

                    val springOffset = spring<IntOffset>(dampingRatio = 1f, stiffness = 400f)
                    val springFloat = spring<Float>(dampingRatio = 1f, stiffness = 400f)

                    NavHost(
                        navController = mainNavController,
                        startDestination = MediaRootRoute,
                        enterTransition = {
                            slideIntoContainer(
                                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                                animationSpec = springOffset,
                            ) + scaleIn(animationSpec = springFloat, initialScale = 0.95f) +
                                fadeIn(animationSpec = springFloat)
                        },
                        exitTransition = {
                            slideOutOfContainer(
                                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                                animationSpec = springOffset,
                                targetOffset = { fullOffset -> (fullOffset * 0.3f).toInt() },
                            ) + scaleOut(animationSpec = springFloat, targetScale = 0.95f) +
                                fadeOut(animationSpec = springFloat)
                        },
                        popEnterTransition = {
                            slideIntoContainer(
                                towards = AnimatedContentTransitionScope.SlideDirection.End,
                                animationSpec = springOffset,
                                initialOffset = { fullOffset -> (fullOffset * 0.3f).toInt() },
                            ) + scaleIn(animationSpec = springFloat, initialScale = 0.95f) +
                                fadeIn(animationSpec = springFloat)
                        },
                        popExitTransition = {
                            slideOutOfContainer(
                                towards = AnimatedContentTransitionScope.SlideDirection.End,
                                animationSpec = springOffset,
                            ) + scaleOut(animationSpec = springFloat, targetScale = 0.95f) +
                                fadeOut(animationSpec = springFloat)
                        },
                    ) {
                        mediaNavGraph(
                            context = this@MainActivity,
                            navController = mainNavController,
                        )
                        settingsNavGraph(navController = mainNavController)
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        lifecycleScope.launch(Dispatchers.IO) {
            autoBackupHelper.performAutoBackup()
        }
    }
}

/**
 * Returns `true` if dark theme should be used, as a function of the [uiState] and the
 * current system context.
 */
@Composable
fun shouldUseDarkTheme(
    uiState: MainActivityUiState,
): Boolean = when (uiState) {
    MainActivityUiState.Loading -> isSystemInDarkTheme()
    is MainActivityUiState.Success -> when (uiState.preferences.themeConfig) {
        ThemeConfig.SYSTEM -> isSystemInDarkTheme()
        ThemeConfig.OFF -> false
        ThemeConfig.ON -> true
    }
}

@Composable
fun shouldUseHighContrastDarkTheme(
    uiState: MainActivityUiState,
): Boolean = when (uiState) {
    MainActivityUiState.Loading -> false
    is MainActivityUiState.Success -> uiState.preferences.useHighContrastDarkTheme
}

@Composable
fun shouldUseAccentPreset(
    uiState: MainActivityUiState,
): AccentPreset = when (uiState) {
    MainActivityUiState.Loading -> AccentPreset.Default
    is MainActivityUiState.Success -> uiState.preferences.accentPreset
}

fun shouldUseSurfaceStyle(
    uiState: MainActivityUiState,
): SurfaceStyle = when (uiState) {
    MainActivityUiState.Loading -> SurfaceStyle.Default
    is MainActivityUiState.Success -> uiState.preferences.surfaceStyle
}
