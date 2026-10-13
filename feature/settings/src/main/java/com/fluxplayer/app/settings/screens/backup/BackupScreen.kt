package com.fluxplayer.app.settings.screens.backup

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxplayer.app.core.model.BackupWebDavConfig
import com.fluxplayer.app.core.model.WebDavServer
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.components.ClickablePreferenceItem
import com.fluxplayer.app.core.ui.components.FluxSettingsScaffold
import com.fluxplayer.app.core.ui.components.ListSectionTitle
import com.fluxplayer.app.core.ui.components.NextDialog
import com.fluxplayer.app.core.ui.components.PreferenceSwitch
import java.text.DateFormat
import java.util.Date

@Composable
fun BackupScreen(
    onNavigateUp: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showAccount by remember { mutableStateOf(false) }
    var showRemote by remember { mutableStateOf(false) }
    var showNewBackup by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(BackupWebDavConfig()) }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {
        it?.let(viewModel::createBackup)
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(viewModel::restoreBackup)
    }
    BackHandler(enabled = state.isLoading) { }
    LaunchedEffect(state.successMessage, state.errorMessage) {
        val message = state.errorMessage ?: state.successMessage ?: return@LaunchedEffect
        viewModel.clearMessages()
        snackbar.showSnackbar(message)
    }
    LaunchedEffect(config.hasPendingNewBackup) {
        if (config.hasPendingNewBackup) showNewBackup = true
    }
    LaunchedEffect(state.pendingBackup) {
        if (state.pendingBackup != null) showRemote = false
    }

    FluxSettingsScaffold(
        title = stringResource(R.string.backup_and_restore),
        onNavigateUp = { if (!state.isLoading) onNavigateUp() },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            ) {
                Text(
                    stringResource(R.string.backup_contents_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                ListSectionTitle(text = stringResource(R.string.backup_local_section))
                ClickablePreferenceItem(
                    title = stringResource(R.string.backup_export_local),
                    description = stringResource(R.string.backup_export_local_desc),
                    enabled = !state.isLoading,
                    isFirstItem = true,
                    onClick = { createFile.launch(config.generateBackupFileName()) },
                )
                HorizontalDivider()
                ClickablePreferenceItem(
                    title = stringResource(R.string.backup_restore_local),
                    description = stringResource(R.string.backup_restore_local_desc),
                    enabled = !state.isLoading,
                    isLastItem = true,
                    onClick = { openFile.launch(arrayOf("*/*")) },
                )

                ListSectionTitle(text = stringResource(R.string.backup_webdav_section))
                ClickablePreferenceItem(
                    title = stringResource(R.string.backup_webdav_settings),
                    description = config.url.ifBlank { stringResource(R.string.backup_not_set) },
                    enabled = !state.isLoading,
                    isFirstItem = true,
                    onClick = {
                        draft = config
                        showAccount = true
                    },
                )
                HorizontalDivider()
                ClickablePreferenceItem(
                    title = stringResource(R.string.backup_test_webdav),
                    description = state.connectionMessage ?: stringResource(R.string.backup_test_webdav_desc),
                    enabled = config.isConfigured && !state.isLoading,
                    onClick = viewModel::testConnection,
                )
                HorizontalDivider()
                ClickablePreferenceItem(
                    title = stringResource(R.string.backup_upload_to_cloud),
                    description = stringResource(R.string.backup_upload_to_cloud_desc),
                    enabled = config.isConfigured && !state.isLoading,
                    onClick = viewModel::uploadToCloud,
                )
                HorizontalDivider()
                ClickablePreferenceItem(
                    title = stringResource(R.string.backup_download_from_cloud),
                    description = stringResource(R.string.backup_restore_remote_desc),
                    enabled = config.isConfigured && !state.isLoading,
                    isLastItem = true,
                    onClick = {
                        showRemote = true
                        viewModel.listRemoteFiles()
                    },
                )

                ListSectionTitle(text = stringResource(R.string.backup_automatic_section))
                PreferenceSwitch(
                    title = stringResource(R.string.backup_auto_webdav),
                    description = stringResource(R.string.backup_auto_webdav_desc),
                    isChecked = config.autoBackupSyncMode == "remote",
                    enabled = config.isConfigured && !state.isLoading,
                    isFirstItem = true,
                    onClick = { viewModel.updateConfigField { it.copy(autoBackupSyncMode = if (it.autoBackupSyncMode == "remote") "off" else "remote") } },
                )
                HorizontalDivider()
                PreferenceSwitch(
                    title = stringResource(R.string.backup_auto_check),
                    description = stringResource(R.string.backup_auto_check_desc),
                    isChecked = config.autoCheckNewBackup,
                    enabled = config.isConfigured && !state.isLoading,
                    isLastItem = true,
                    onClick = { viewModel.updateConfigField { it.copy(autoCheckNewBackup = !it.autoCheckNewBackup) } },
                )
                Spacer(Modifier.height(24.dp))
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            if (state.isLoading) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)).clickable { },
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
        }
    }

    if (showAccount) {
        AccountDialog(
            draft = draft,
            onChange = { draft = it },
            servers = state.servers,
            busy = state.isLoading,
            onLoadServers = viewModel::loadServers,
            onConfirm = {
                viewModel.saveConfig(draft)
                showAccount = false
            },
            onDismiss = { if (!state.isLoading) showAccount = false },
        )
    }
    if (showRemote) {
        NextDialog(
            title = { Text(stringResource(R.string.backup_remote_files)) },
            onDismissRequest = { if (!state.isLoading) showRemote = false },
            confirmButton = {
                TextButton(onClick = viewModel::listRemoteFiles, enabled = !state.isLoading) { Text(stringResource(R.string.backup_refresh)) }
            },
            dismissButton = {
                TextButton(onClick = { showRemote = false }, enabled = !state.isLoading) { Text(stringResource(R.string.cancel)) }
            },
            content = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.isLoading) {
                        CircularProgressIndicator()
                    } else if (state.remoteFiles.isEmpty()) Text(stringResource(R.string.backup_no_remote_files))
                    state.remoteFiles.forEach { file ->
                        TextButton(
                            onClick = { viewModel.restoreFromRemoteFile(file.path) },
                            enabled = !state.isLoading,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(file.name) }
                    }
                }
            },
        )
    }
    state.pendingBackup?.let { backup ->
        val options = buildList {
            add("app_preferences" to R.string.backup_restore_app_data)
            add("player_preferences" to R.string.backup_ignore_player_prefs)
            add("webdav_servers" to R.string.backup_ignore_webdav)
            if (backup.openListConfig != null || state.hasOpenListFiles) add("openlist_config" to R.string.backup_restore_openlist_data)
            if (backup.alipanConfig != null || backup.cloud189Config != null || backup.pan123Config != null || backup.quarkConfig != null || backup.yun139Config != null) add("cloud_credentials" to R.string.backup_ignore_cloud_creds)
            if (backup.backupWebDavConfig != null) add("backup_settings" to R.string.backup_restore_server_settings)
        }
        NextDialog(
            title = { Text(stringResource(R.string.backup_restore_preview)) },
            onDismissRequest = viewModel::cancelRestore,
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmRestore,
                    enabled = !state.isLoading && options.any { it.first !in state.ignoredItems },
                ) { Text(stringResource(R.string.restore_backup)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRestore, enabled = !state.isLoading) { Text(stringResource(R.string.cancel)) }
            },
            content = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.backup_created_at, DateFormat.getDateTimeInstance().format(Date(backup.backupTime))))
                    Text(stringResource(R.string.backup_restore_selected_description), modifier = Modifier.padding(vertical = 12.dp))
                    options.forEach { (key, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !state.isLoading) { viewModel.toggleRestoreItem(key) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = key !in state.ignoredItems, onCheckedChange = null, enabled = !state.isLoading)
                            Text(stringResource(label))
                        }
                    }
                    if (state.hasOpenListFiles && "openlist_config" !in state.ignoredItems) {
                        Text(stringResource(R.string.backup_openlist_stopped_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    if (state.isLoading) CircularProgressIndicator()
                    state.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
        )
    }
    if (state.restoreCompleted) {
        NextDialog(
            title = { Text(stringResource(R.string.backup_restore_complete_title)) },
            content = { Text(stringResource(R.string.backup_restore_complete_message)) },
            onDismissRequest = viewModel::clearRestoreCompleted,
            confirmButton = { TextButton(onClick = viewModel::restartApp) { Text(stringResource(R.string.backup_restart_app)) } },
            dismissButton = { TextButton(onClick = viewModel::clearRestoreCompleted) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (showNewBackup) {
        NextDialog(
            title = { Text(stringResource(R.string.backup_new_available_title)) },
            content = { Text(stringResource(R.string.backup_new_available_message)) },
            onDismissRequest = {
                showNewBackup = false
                viewModel.clearPendingNewBackup()
            },
            confirmButton = {
                TextButton(onClick = {
                    showNewBackup = false
                    showRemote = true
                    viewModel.listRemoteFiles()
                }) { Text(stringResource(R.string.backup_remote_files)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNewBackup = false
                    viewModel.clearPendingNewBackup()
                }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** 所有账号字段都编辑临时副本，取消时不会改变已保存的配置。 */
@Composable
private fun AccountDialog(
    draft: BackupWebDavConfig,
    onChange: (BackupWebDavConfig) -> Unit,
    servers: List<WebDavServer>,
    busy: Boolean,
    onLoadServers: () -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var showServers by remember { mutableStateOf(false) }
    NextDialog(
        title = { Text(stringResource(R.string.backup_webdav_settings)) },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy && (draft.url.isBlank() || draft.isConfigured)) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
        content = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft.url,
                    onValueChange = { onChange(draft.copy(url = it.trim())) },
                    label = { Text(stringResource(R.string.server_url)) },
                    isError = draft.url.isNotBlank() && !draft.isConfigured,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.username,
                    onValueChange = { onChange(draft.copy(username = it)) },
                    label = { Text(stringResource(R.string.backup_webdav_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.password,
                    onValueChange = { onChange(draft.copy(password = it)) },
                    label = { Text(stringResource(R.string.backup_webdav_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.subfolder,
                    onValueChange = { onChange(draft.copy(subfolder = it.trim())) },
                    label = { Text(stringResource(R.string.backup_subfolder)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.deviceName,
                    onValueChange = { onChange(draft.copy(deviceName = it)) },
                    label = { Text(stringResource(R.string.backup_device_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = {
                    showServers = true
                    onLoadServers()
                }, enabled = !busy) {
                    Text(stringResource(R.string.backup_import_from_server))
                }
                if (showServers && !busy) {
                    if (servers.isEmpty()) Text(stringResource(R.string.backup_no_saved_servers))
                    servers.forEach { server ->
                        TextButton(onClick = {
                            onChange(draft.copy(url = server.url, username = server.username, password = server.password))
                            showServers = false
                        }) { Text(server.name.ifBlank { server.url }) }
                    }
                }
            }
        },
    )
}
