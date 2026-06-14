@file:Suppress("MagicNumber", "TooManyFunctions")

package com.fluxplayer.app.settings.screens.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.components.ClickablePreferenceItem
import com.fluxplayer.app.core.ui.components.FluxSettingsScaffold
import com.fluxplayer.app.core.common.uriToFilePath
import com.fluxplayer.app.core.ui.components.ListSectionTitle
import com.fluxplayer.app.core.ui.components.NextDialog
import com.fluxplayer.app.core.ui.components.NextDialogWithDoneAndCancelButtons
import com.fluxplayer.app.core.ui.components.NextSegmentedListItem
import com.fluxplayer.app.core.ui.components.PreferenceSwitch
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.settings.composables.OptionsDialog
import com.fluxplayer.app.core.ui.components.RadioTextButton
import com.fluxplayer.app.core.model.BackupWebDavConfig
import com.fluxplayer.app.core.model.WebDavResource

// ==================== 对话框状态类型 ====================
private sealed interface BackupDialog {
    data object Account : BackupDialog
    data object DeviceName : BackupDialog
    data object BackupPath : BackupDialog
    data object SyncMode : BackupDialog
    data object RestoreIgnore : BackupDialog
    data object RestoreSource : BackupDialog
    data object RemoteFiles : BackupDialog
    data object RestoreComplete : BackupDialog
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BackupScreen(
    onNavigateUp: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val remoteFiles by viewModel.remoteFiles.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 对话框状态
    var dialog by remember { mutableStateOf<BackupDialog?>(null) }

    // 恢复完成时自动弹出对话框
    LaunchedEffect(uiState.restoreCompleted) {
        if (uiState.restoreCompleted) {
            dialog = BackupDialog.RestoreComplete
        }
    }

    // 输入框临时状态
    var editUsername by remember(config.username) { mutableStateOf(config.username) }
    var editPassword by remember(config.password) { mutableStateOf(config.password) }
    var editSubfolder by remember(config.subfolder) { mutableStateOf(config.subfolder) }
    var editDeviceName by remember(config.deviceName) { mutableStateOf(config.deviceName) }
    var editBackupPath by remember(config.backupPath) { mutableStateOf(config.backupPath) }
    var ignoreListState by remember(config.restoreIgnoreList) { mutableStateOf(config.restoreIgnoreList.toSet()) }

    // 文件选择器
    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? -> uri?.let { viewModel.createBackup(it) } }

    val restoreBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.restoreBackup(it) } }

    // Snackbar 消息
    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let { snackbarHostState.showSnackbar(it); viewModel.clearMessages() }
    }
    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { snackbarHostState.showSnackbar(it); viewModel.clearMessages() }
    }
    FluxSettingsScaffold(
        title = stringResource(R.string.backup_and_restore),
        onNavigateUp = onNavigateUp,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(innerPadding),
            ) {
                // ============ WebDAV 设置 ============
                ListSectionTitle(text = stringResource(R.string.backup_webdav_settings))
                Column(
                    Modifier.padding(horizontal = 12.dp),
                ) {
                    ClickablePreferenceItem(
                        title = stringResource(R.string.backup_webdav_url),
                        description = config.url.ifBlank { stringResource(R.string.backup_not_set) },
                        onClick = {
                            editUsername = config.username; editPassword = config.password
                            editSubfolder = config.subfolder; dialog = BackupDialog.Account
                        },
                        isFirstItem = true,
                    )
                    HorizontalDivider()
                    ClickablePreferenceItem(
                        title = stringResource(R.string.backup_webdav_account),
                        description = config.username.ifBlank { stringResource(R.string.backup_webdav_account_hint) },
                        onClick = {
                            editUsername = config.username; editPassword = config.password
                            editSubfolder = config.subfolder; dialog = BackupDialog.Account
                        },
                    )
                    HorizontalDivider()
                    ClickablePreferenceItem(
                        title = stringResource(R.string.backup_subfolder),
                        description = config.subfolder.ifBlank { "/" },
                        onClick = {
                            editSubfolder = config.subfolder; dialog = BackupDialog.Account
                        },
                    )
                    HorizontalDivider()
                    ClickablePreferenceItem(
                        title = stringResource(R.string.backup_device_name),
                        description = config.deviceName.ifBlank { stringResource(R.string.backup_not_set) },
                        onClick = {
                            editDeviceName = config.deviceName; dialog = BackupDialog.DeviceName
                        },
                    )
                    HorizontalDivider()
                    ClickablePreferenceItem(
                        title = stringResource(R.string.backup_test_webdav),
                        description = when {
                            uiState.connectionTesting -> stringResource(R.string.backup_testing)
                            uiState.connectionSuccess == true -> stringResource(R.string.backup_connection_success)
                            uiState.connectionSuccess == false -> stringResource(R.string.backup_connection_failed)
                            else -> stringResource(R.string.backup_test_webdav_desc)
                        },
                        onClick = { viewModel.testConnection() },
                    )
                    HorizontalDivider()
                    PreferenceSwitch(
                        title = stringResource(R.string.backup_auto_check),
                        description = stringResource(R.string.backup_auto_check_desc),
                        isChecked = config.autoCheckNewBackup,
                        onClick = {
                            viewModel.updateConfigField { it.copy(autoCheckNewBackup = !it.autoCheckNewBackup) }
                        },
                    )
                    HorizontalDivider()
                    ClickablePreferenceItem(
                        title = stringResource(R.string.backup_auto_sync),
                        description = when (config.autoBackupSyncMode) {
                            "both" -> stringResource(R.string.backup_sync_both)
                            "local" -> stringResource(R.string.backup_sync_local)
                            "remote" -> stringResource(R.string.backup_sync_remote)
                            else -> config.autoBackupSyncMode
                        },
                        onClick = { dialog = BackupDialog.SyncMode },
                        isLastItem = true,
                    )
                }

                // ============ 备份与恢复 ============
                Spacer(Modifier.padding(top = 8.dp))
                ListSectionTitle(text = stringResource(R.string.backup_and_restore))
                Column(
                    Modifier.padding(horizontal = 12.dp),
                ) {
                    ClickablePreferenceItem(
                        title = stringResource(R.string.backup_path),
                        description = config.backupPath.ifBlank { stringResource(R.string.backup_not_set) },
                        onClick = {
                            editBackupPath = config.backupPath; dialog = BackupDialog.BackupPath
                        },
                        isFirstItem = true,
                    )
                    HorizontalDivider()
                    ClickablePreferenceItem(
                        title = stringResource(R.string.backup_action),
                        description = stringResource(R.string.backup_action_desc),
                        onClick = { viewModel.performFullBackup() },
                    )
                    HorizontalDivider()
                    ClickablePreferenceItem(
                        title = stringResource(R.string.backup_upload_to_cloud),
                        description = stringResource(R.string.backup_upload_to_cloud_desc),
                        onClick = { viewModel.uploadToCloud() },
                    )
                    HorizontalDivider()
                    ClickablePreferenceItem(
                        title = stringResource(R.string.restore_backup),
                        description = stringResource(R.string.restore_backup_desc),
                        onClick = { dialog = BackupDialog.RestoreSource },
                    )
                    HorizontalDivider()
                    ClickablePreferenceItem(
                        title = stringResource(R.string.backup_restore_ignore),
                        description = config.restoreIgnoreList
                            .ifEmpty { listOf(stringResource(R.string.backup_none)) }
                            .joinToString(", "),
                        onClick = {
                            ignoreListState = config.restoreIgnoreList.toSet()
                            dialog = BackupDialog.RestoreIgnore
                        },
                    )
                    HorizontalDivider()
                    PreferenceSwitch(
                        title = stringResource(R.string.backup_keep_latest),
                        description = stringResource(R.string.backup_keep_latest_desc),
                        isChecked = config.keepOnlyLatestBackup,
                        onClick = {
                            viewModel.updateConfigField { it.copy(keepOnlyLatestBackup = !it.keepOnlyLatestBackup) }
                        },
                        isLastItem = true,
                    )
                }
                Spacer(Modifier.padding(bottom = 32.dp))
            }

            if (uiState.isLoading) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }

    // ============ 对话框 ============

    when (dialog) {
        BackupDialog.Account -> AccountDialog(
            config = config,
            editUsername = editUsername,
            editPassword = editPassword,
            editSubfolder = editSubfolder,
            onUsernameChange = { editUsername = it },
            onPasswordChange = { editPassword = it },
            onSubfolderChange = { editSubfolder = it },
            onUrlChange = { v -> viewModel.updateConfigField { it.copy(url = v) } },
            onImport = { viewModel.copyFromWebDavServer() },
            onConfirm = {
                viewModel.updateConfigField {
                    it.copy(username = editUsername, password = editPassword, subfolder = editSubfolder)
                }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        BackupDialog.DeviceName -> NextDialogWithDoneAndCancelButtons(
            title = stringResource(R.string.backup_device_name),
            onDoneClick = {
                viewModel.updateConfigField { it.copy(deviceName = editDeviceName) }
                dialog = null
            },
            onDismissClick = { dialog = null },
            content = {
                OutlinedTextField(
                    value = editDeviceName,
                    onValueChange = { editDeviceName = it },
                    placeholder = { Text(stringResource(R.string.backup_device_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )

        BackupDialog.BackupPath -> {
            val directoryPickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    val path = uriToFilePath(uri)
                    if (path != null) {
                        editBackupPath = path
                    }
                }
            }
            NextDialogWithDoneAndCancelButtons(
                title = stringResource(R.string.backup_path),
                onDoneClick = {
                    viewModel.updateConfigField { it.copy(backupPath = editBackupPath) }
                    dialog = null
                },
                onDismissClick = { dialog = null },
                content = {
                    Column {
                        OutlinedTextField(
                            value = editBackupPath,
                            onValueChange = { editBackupPath = it },
                            placeholder = { Text("/storage/emulated/0/backup") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { directoryPickerLauncher.launch(null) }) {
                            Icon(NextIcons.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("浏览目录")
                        }
                    }
                },
            )
        }

        BackupDialog.SyncMode -> SyncModeDialog(
            current = config.autoBackupSyncMode,
            onSelect = { mode ->
                viewModel.updateConfigField { it.copy(autoBackupSyncMode = mode) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        BackupDialog.RestoreIgnore -> RestoreIgnoreDialog(
            selected = ignoreListState,
            onToggle = { key -> ignoreListState = if (key in ignoreListState) ignoreListState - key else ignoreListState + key },
            onConfirm = {
                viewModel.updateConfigField { it.copy(restoreIgnoreList = ignoreListState.toList()) }
                dialog = null
            },
            onDismiss = { dialog = null },
        )

        BackupDialog.RestoreSource -> RestoreSourceDialog(
            onLocal = {
                dialog = null
                restoreBackupLauncher.launch(arrayOf("application/zip", "application/json", "*/*"))
            },
            onRemote = {
                dialog = null
                viewModel.listRemoteFiles()
                dialog = BackupDialog.RemoteFiles
            },
            onDismiss = { dialog = null },
        )

        BackupDialog.RemoteFiles -> RemoteFilesDialog(
            remoteFiles = remoteFiles,
            listLoading = uiState.listLoading,
            onRefresh = { viewModel.listRemoteFiles() },
            onSelect = { file ->
                dialog = null
                viewModel.restoreFromRemoteFile(file.path)
            },
            onDismiss = { dialog = null },
        )

        BackupDialog.RestoreComplete -> RestoreCompleteDialog(
            onRestart = {
                dialog = null
                viewModel.clearRestoreCompleted()
                viewModel.restartApp()
            },
            onDismiss = {
                dialog = null
                viewModel.clearRestoreCompleted()
            },
        )

        null -> { /* 无对话框 */ }
    }
}

// ==================== 对话框组件 ====================

@Composable
private fun AccountDialog(
    config: BackupWebDavConfig,
    editUsername: String,
    editPassword: String,
    editSubfolder: String,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSubfolderChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onImport: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val urlRes = stringResource(R.string.server_url)
    val userRes = stringResource(R.string.backup_webdav_username)
    val passRes = stringResource(R.string.backup_webdav_password)
    val folderRes = stringResource(R.string.backup_subfolder)
    val hint = stringResource(R.string.backup_webdav_account_hint)
    val importRes = stringResource(R.string.backup_import_from_server)
    val doneRes = stringResource(R.string.done)
    val cancelRes = stringResource(R.string.cancel)

    NextDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_webdav_account)) },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = config.url, onValueChange = onUrlChange,
                    label = { Text(urlRes) }, placeholder = { Text("https://webdav.example.com") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editUsername, onValueChange = onUsernameChange,
                    label = { Text(userRes) }, placeholder = { Text(hint) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editPassword, onValueChange = onPasswordChange,
                    label = { Text(passRes) }, visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = editSubfolder, onValueChange = onSubfolderChange,
                    label = { Text(folderRes) }, placeholder = { Text("legado") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                    Text(importRes)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(doneRes) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancelRes) } },
    )
}

@Composable
private fun SyncModeDialog(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val both = stringResource(R.string.backup_sync_both)
    val local = stringResource(R.string.backup_sync_local)
    val remote = stringResource(R.string.backup_sync_remote)
    OptionsDialog(
        text = stringResource(R.string.backup_auto_sync),
        onDismissClick = onDismiss,
    ) {
        val modes = listOf("both" to both, "local" to local, "remote" to remote)
        items(modes) { (mode, label) ->
            RadioTextButton(
                text = label,
                selected = mode == current,
                onClick = { onSelect(mode) },
            )
        }
    }
}

@Composable
private fun RestoreIgnoreDialog(
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    NextDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore_ignore)) },
        content = {
            val options = listOf(
                "app_preferences" to stringResource(R.string.backup_ignore_app_prefs),
                "player_preferences" to stringResource(R.string.backup_ignore_player_prefs),
                "webdav_servers" to stringResource(R.string.backup_ignore_webdav),
                "openlist_config" to stringResource(R.string.backup_ignore_openlist),
                "cloud_credentials" to stringResource(R.string.backup_ignore_cloud_creds),
                "backup_settings" to stringResource(R.string.backup_ignore_backup_settings),
            )
            Column {
                options.forEach { (key, label) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = key in selected, onCheckedChange = { onToggle(key) })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RestoreSourceDialog(
    onLocal: () -> Unit,
    onRemote: () -> Unit,
    onDismiss: () -> Unit,
) {
    NextDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_backup)) },
        content = {
            Column {
                NextSegmentedListItem(
                    onClick = onLocal, isFirstItem = true, isLastItem = true,
                    content = { Text(stringResource(R.string.backup_restore_local)) },
                    supportingContent = { Text(stringResource(R.string.backup_restore_local_desc)) },
                )
                Spacer(Modifier.padding(4.dp))
                NextSegmentedListItem(
                    onClick = onRemote, isFirstItem = true, isLastItem = true,
                    content = { Text(stringResource(R.string.backup_restore_remote)) },
                    supportingContent = { Text(stringResource(R.string.backup_restore_remote_desc)) },
                )
            }
        },
        confirmButton = { },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RemoteFilesDialog(
    remoteFiles: List<WebDavResource>,
    listLoading: Boolean,
    onRefresh: () -> Unit,
    onSelect: (WebDavResource) -> Unit,
    onDismiss: () -> Unit,
) {
    NextDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.backup_remote_files), modifier = Modifier.weight(1f))
                if (listLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                IconButton(onClick = onRefresh) {
                    Icon(NextIcons.Update, stringResource(R.string.backup_refresh))
                }
            }
        },
        content = {
            if (remoteFiles.isEmpty() && !listLoading) {
                Text(
                    stringResource(R.string.backup_no_remote_files),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
            } else {
                Column {
                    remoteFiles.forEach { file ->
                        NextSegmentedListItem(
                            onClick = { onSelect(file) },
                            content = {
                                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                val parts = listOfNotNull(
                                    formatFileSize(file.size),
                                    file.lastModified.takeIf { it.isNotBlank() },
                                ).joinToString(" · ")
                                if (parts.isNotBlank()) Text(parts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                            trailingContent = {
                                Icon(NextIcons.ArrowDownward, stringResource(R.string.restore_backup), tint = MaterialTheme.colorScheme.primary)
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun RestoreCompleteDialog(
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
) {
    NextDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore_complete_title)) },
        content = {
            Text(
                stringResource(R.string.backup_restore_complete_message),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onRestart) {
                Text(stringResource(R.string.backup_restart_app))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

// ==================== 辅助 ====================

private fun formatFileSize(bytes: Long): String? = when {
    bytes <= 0 -> null
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    else -> "${"%.2f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
}

// 本文件使用的类型引用
