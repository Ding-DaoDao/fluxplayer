package com.fluxplayer.app.settings.screens.general

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxplayer.app.core.common.uriToFilePath
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.components.CancelButton
import com.fluxplayer.app.core.ui.components.ClickablePreferenceItem
import com.fluxplayer.app.core.ui.components.FluxSettingsScaffold
import com.fluxplayer.app.core.ui.components.ListSectionTitle
import com.fluxplayer.app.core.ui.components.MediaGroupSpacer as HorizontalDivider
import com.fluxplayer.app.core.ui.components.NextDialog
import com.fluxplayer.app.core.ui.components.PreferenceSlider
import com.fluxplayer.app.core.ui.components.PreferenceSwitch
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import kotlin.math.roundToInt

@Composable
fun GeneralPreferencesScreen(
    onNavigateUp: () -> Unit,
    onBackupClick: () -> Unit = {},
    viewModel: GeneralPreferencesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    GeneralPreferencesContent(
        uiState = uiState,
        onEvent = viewModel::onEvent,
        onNavigateUp = onNavigateUp,
        onBackupClick = onBackupClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GeneralPreferencesContent(
    uiState: GeneralPreferencesUiState,
    onEvent: (GeneralPreferencesUiEvent) -> Unit,
    onNavigateUp: () -> Unit,
    onBackupClick: () -> Unit = {},
) {
    val context = LocalContext.current
    val p = uiState.preferences
    FluxSettingsScaffold(
        title = stringResource(id = R.string.general_name),
        onNavigateUp = onNavigateUp,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(state = rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            ListSectionTitle(text = "浏览与记录")
            Column {
                ClickablePreferenceItem(
                    title = "应用语言",
                    description = "跟随系统或单独选择应用语言（Android 13 及以上）",
                    icon = NextIcons.Appearance,
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                    onClick = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_APP_LOCALE_SETTINGS, "package:${context.packageName}".toUri()))
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, "当前系统未提供单独的应用语言设置", Toast.LENGTH_SHORT).show()
                        }
                    },
                    isFirstItem = true,
                )
                PreferenceSwitch(
                    title = "合并同名历史",
                    description = "同名视频只显示最近一条；原始记录仍保留",
                    icon = NextIcons.History,
                    isChecked = p.historyMergeEnabled,
                    onClick = { onEvent(GeneralPreferencesUiEvent.UpdatePreferences { it.copy(historyMergeEnabled = !it.historyMergeEnabled) }) },
                )
                PreferenceSwitch(
                    title = "视频无痕播放",
                    description = "不保存新的视频历史和续播位置，已有记录保留",
                    icon = NextIcons.History,
                    isChecked = p.videoIncognito,
                    onClick = { onEvent(GeneralPreferencesUiEvent.UpdatePreferences { it.copy(videoIncognito = !it.videoIncognito) }) },
                )
                PreferenceSlider(
                    title = "历史显示上限",
                    description = "最多显示 ${p.historyLimit} 条，超出部分保留在本地",
                    icon = NextIcons.History,
                    value = p.historyLimit.toFloat(),
                    valueRange = 50f..1000f,
                    steps = 18,
                    saveOnRelease = true,
                    onValueChange = { value -> onEvent(GeneralPreferencesUiEvent.UpdatePreferences { it.copy(historyLimit = value.roundToInt().coerceIn(50, 1000)) }) },
                )
                PreferenceSlider(
                    title = "媒体网格列数", description = if (p.mediaGridColumns == 0) "自动适应屏幕" else "${p.mediaGridColumns} 列（切换到网格布局后生效）", icon = NextIcons.Folder,
                    value = p.mediaGridColumns.toFloat(), valueRange = 0f..6f, steps = 5, saveOnRelease = true,
                    onValueChange = { value -> onEvent(GeneralPreferencesUiEvent.UpdatePreferences { it.copy(mediaGridColumns = value.roundToInt().coerceIn(0, 6)) }) }, isLastItem = true,
                )
            }
            ListSectionTitle(text = stringResource(id = R.string.user_data))
            Column {
                ClickablePreferenceItem(
                    title = stringResource(R.string.backup_and_restore),
                    description = stringResource(R.string.backup_and_restore_description),
                    icon = NextIcons.Update,
                    onClick = onBackupClick,
                    isFirstItem = true,
                )
                HorizontalDivider()
                ClickablePreferenceItem(
                    title = stringResource(R.string.delete_thumbnail_cache),
                    description = stringResource(R.string.delete_thumbnail_cache_description),
                    icon = NextIcons.DeleteSweep,
                    onClick = { onEvent(GeneralPreferencesUiEvent.ShowDialog(GeneralPreferencesDialog.ClearThumbnailCacheDialog)) },
                )
                HorizontalDivider()
                ClickablePreferenceItem(
                    title = stringResource(R.string.reset_settings),
                    description = stringResource(R.string.reset_settings_description),
                    icon = NextIcons.History,
                    onClick = { onEvent(GeneralPreferencesUiEvent.ShowDialog(GeneralPreferencesDialog.ResetSettingsDialog)) },
                )
                HorizontalDivider()
                ClickablePreferenceItem(
                    title = stringResource(R.string.clear_local_listening_progress),
                    description = stringResource(R.string.clear_local_listening_progress_description),
                    icon = NextIcons.DeleteSweep,
                    onClick = { onEvent(GeneralPreferencesUiEvent.ShowDialog(GeneralPreferencesDialog.ClearLocalListeningProgressDialog)) },
                    isLastItem = true,
                )
            }

            ListSectionTitle(text = stringResource(R.string.download))
            Column {
                ClickablePreferenceItem(
                    title = stringResource(R.string.download_location),
                    description = uiState.preferences.downloadPath.ifBlank { stringResource(R.string.not_set) },
                    icon = NextIcons.Folder,
                    onClick = { onEvent(GeneralPreferencesUiEvent.ShowDialog(GeneralPreferencesDialog.ChangeDownloadPathDialog)) },
                    isFirstItem = true,
                    isLastItem = true,
                )
            }
        }

        uiState.showDialog?.let { dialog ->
            when (dialog) {
                GeneralPreferencesDialog.ClearThumbnailCacheDialog -> {
                    NextDialog(
                        onDismissRequest = { onEvent(GeneralPreferencesUiEvent.ShowDialog(null)) },
                        title = {
                            Text(
                                text = stringResource(R.string.delete_thumbnail_cache),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    onEvent(GeneralPreferencesUiEvent.ClearThumbnailCache)
                                    onEvent(GeneralPreferencesUiEvent.ShowDialog(null))
                                },
                            ) {
                                Text(text = stringResource(R.string.delete))
                            }
                        },
                        dismissButton = { CancelButton(onClick = { onEvent(GeneralPreferencesUiEvent.ShowDialog(null)) }) },
                        content = {
                            Text(
                                text = stringResource(R.string.delete_thumbnail_cache_confirmation),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        },
                    )
                }
                GeneralPreferencesDialog.ClearLocalListeningProgressDialog -> {
                    NextDialog(
                        onDismissRequest = { onEvent(GeneralPreferencesUiEvent.ShowDialog(null)) },
                        title = { Text(stringResource(R.string.clear_local_listening_progress)) },
                        content = { Text(stringResource(R.string.clear_local_listening_progress_confirmation)) },
                        confirmButton = {
                            TextButton(onClick = {
                                onEvent(GeneralPreferencesUiEvent.ClearLocalListeningProgress)
                                onEvent(GeneralPreferencesUiEvent.ShowDialog(null))
                            }) { Text(stringResource(R.string.delete)) }
                        },
                        dismissButton = { CancelButton(onClick = { onEvent(GeneralPreferencesUiEvent.ShowDialog(null)) }) },
                    )
                }
                GeneralPreferencesDialog.ResetSettingsDialog -> {
                    NextDialog(
                        onDismissRequest = { onEvent(GeneralPreferencesUiEvent.ShowDialog(null)) },
                        title = {
                            Text(
                                text = stringResource(R.string.reset_settings),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    onEvent(GeneralPreferencesUiEvent.ResetSettings)
                                    onEvent(GeneralPreferencesUiEvent.ShowDialog(null))
                                },
                            ) {
                                Text(text = stringResource(R.string.reset))
                            }
                        },
                        dismissButton = { CancelButton(onClick = { onEvent(GeneralPreferencesUiEvent.ShowDialog(null)) }) },
                        content = {
                            Text(
                                text = stringResource(R.string.reset_settings_confirmation),
                                style = MaterialTheme.typography.titleSmall,
                            )
                        },
                    )
                }
                GeneralPreferencesDialog.ChangeDownloadPathDialog -> {
                    val directoryPickerLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocumentTree(),
                    ) { uri ->
                        if (uri != null) {
                            val path = uriToFilePath(uri)
                            if (path != null) {
                                onEvent(GeneralPreferencesUiEvent.ChangeDownloadPath(path))
                            }
                        }
                        onEvent(GeneralPreferencesUiEvent.ShowDialog(null))
                    }

                    NextDialog(
                        onDismissRequest = { onEvent(GeneralPreferencesUiEvent.ShowDialog(null)) },
                        title = {
                            Text(
                                text = stringResource(R.string.download_location),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = { directoryPickerLauncher.launch(null) },
                            ) {
                                Text(stringResource(R.string.choose_directory))
                            }
                        },
                        dismissButton = {
                            CancelButton(onClick = { onEvent(GeneralPreferencesUiEvent.ShowDialog(null)) })
                        },
                        content = {
                            Column {
                                Text(stringResource(R.string.current_path), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    uiState.preferences.downloadPath.ifBlank { stringResource(R.string.not_set) },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(12.dp))
                                TextButton(
                                    onClick = {
                                        onEvent(GeneralPreferencesUiEvent.ResetDownloadPath)
                                        onEvent(GeneralPreferencesUiEvent.ShowDialog(null))
                                    },
                                ) {
                                    Text(stringResource(R.string.restore_default))
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
