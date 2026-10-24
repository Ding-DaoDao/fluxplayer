package com.fluxplayer.app.settings.screens.backup

import android.net.Uri
import android.provider.DocumentsContract
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
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
import com.fluxplayer.app.core.ui.components.MediaGroupSpacer as HorizontalDivider
import com.fluxplayer.app.core.ui.components.MediaLoadingIndicator
import com.fluxplayer.app.core.ui.components.MediaTextField as OutlinedTextField
import com.fluxplayer.app.core.ui.components.NextDialog
import com.fluxplayer.app.core.ui.components.PreferenceSwitch
import com.fluxplayer.app.core.ui.components.RadioTextButton
import com.fluxplayer.app.settings.composables.OptionsDialog
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
    var showPasswordSetup by remember { mutableStateOf(false) }
    var backupPassword by remember { mutableStateOf("") }
    var passwordConfirmation by remember { mutableStateOf("") }
    var showAccount by remember { mutableStateOf(false) }
    var showRemote by remember { mutableStateOf(false) }
    var showNewBackup by remember { mutableStateOf(false) }
    var showInterval by remember { mutableStateOf(false) }
    var showTrigger by remember { mutableStateOf(false) }
    var showRetention by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(BackupWebDavConfig()) }
    val chooseDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(viewModel::setLocalDirectory) }
    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        it?.let(viewModel::createBackup)
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(viewModel::restoreBackup)
    }
    BackHandler(enabled = state.isLoading) { }
    LaunchedEffect(state.successMessage, state.errorMessage) {
        val message = state.errorMessage ?: state.successMessage ?: return@LaunchedEffect
        // 提示显示结束后再消费消息，避免状态变化取消正在显示的提示。
        snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
        viewModel.clearMessages()
    }
    LaunchedEffect(config.hasPendingNewBackup) {
        if (config.hasPendingNewBackup) showNewBackup = true
    }
    LaunchedEffect(state.pendingBackup) {
        if (state.pendingBackup != null) showRemote = false
    }
    // 新的导入目标出现时清空密码输入（密码错误重试时目标不变，保留用户输入以便修改）

    if (showPasswordSetup || state.passwordSetupRequired || state.passwordRequired) {
        val restoring = state.passwordRequired
        NextDialog(
            onDismissRequest = {
                if (!state.isLoading) {
                    showPasswordSetup = false
                    backupPassword = ""
                    passwordConfirmation = ""
                    viewModel.cancelPassword()
                }
            },
            title = { Text(if (restoring) "解锁备份" else "设置备份密码") },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (restoring) "请输入创建这份备份时使用的密码。" else "至少 8 位。本地、WebDAV 和自动备份均使用此密码。修改密码不会改变已有备份。")
                    OutlinedTextField(value = backupPassword, onValueChange = { backupPassword = it }, label = { Text("备份密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    if (!restoring) OutlinedTextField(value = passwordConfirmation, onValueChange = { passwordConfirmation = it }, label = { Text("确认密码") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    state.passwordError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(enabled = !state.isLoading && backupPassword.length >= 8 && (restoring || backupPassword == passwordConfirmation), onClick = {
                    val password = backupPassword.toCharArray()
                    if (restoring) viewModel.unlockBackup(password) else viewModel.setBackupPassword(password)
                    if (!restoring) {
                        showPasswordSetup = false
                        backupPassword = ""
                        passwordConfirmation = ""
                    }
                }) { Text(if (restoring) "解锁" else "保存") }
            },
            dismissButton = {
                TextButton(enabled = !state.isLoading, onClick = {
                    showPasswordSetup = false
                    backupPassword = ""
                    passwordConfirmation = ""
                    viewModel.cancelPassword()
                }) { Text("取消") }
            },
        )
    }
    LaunchedEffect(state.passwordRequired) { if (!state.passwordRequired) backupPassword = "" }
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
                ClickablePreferenceItem(
                    title = "备份密码",
                    description = if (state.passwordConfigured) "已设置 · 新备份将加密保存" else "先设置密码，再创建备份或启用自动备份",
                    enabled = !state.isLoading,
                    onClick = { showPasswordSetup = true },
                )
                ListSectionTitle(text = stringResource(R.string.backup_local_section))
                ClickablePreferenceItem(
                    title = "本地备份目录",
                    description = if (config.localDirectoryUri.isBlank()) "选择文件夹，之后直接保存到这里" else runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(config.localDirectoryUri)).replace("primary:", "内部存储/") }.getOrDefault(config.localDirectoryUri),
                    enabled = !state.isLoading,
                    isFirstItem = true,
                    onClick = { chooseDirectory.launch(config.localDirectoryUri.takeIf { it.isNotBlank() }?.let(Uri::parse)) },
                )
                HorizontalDivider()
                ClickablePreferenceItem(
                    title = stringResource(R.string.backup_export_local),
                    description = stringResource(R.string.backup_export_local_desc),
                    enabled = !state.isLoading,
                    isFirstItem = false,
                    onClick = {
                        if (!state.passwordConfigured) showPasswordSetup = true else if (config.localDirectoryUri.isNotBlank()) viewModel.exportToLocalDirectory() else createFile.launch(config.generateBackupFileName())
                    },
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
                    onClick = { if (state.passwordConfigured) viewModel.uploadToCloud() else showPasswordSetup = true },
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
                    title = stringResource(R.string.backup_auto_local),
                    description = stringResource(R.string.backup_auto_local_desc),
                    isChecked = config.autoBackupToLocal,
                    enabled = config.localDirectoryUri.isNotBlank() && !state.isLoading,
                    isFirstItem = true,
                    onClick = {
                        if (!state.passwordConfigured && !config.autoBackupToLocal) showPasswordSetup = true else viewModel.updateConfigField { it.withLocalAutoBackup(!it.autoBackupToLocal) }
                    },
                )
                HorizontalDivider()
                PreferenceSwitch(
                    title = stringResource(R.string.backup_auto_webdav),
                    description = stringResource(R.string.backup_auto_webdav_desc),
                    isChecked = config.autoBackupToRemote,
                    enabled = config.isConfigured && !state.isLoading,
                    onClick = {
                        if (!state.passwordConfigured && !config.autoBackupToRemote) showPasswordSetup = true else viewModel.updateConfigField { it.withRemoteAutoBackup(!it.autoBackupToRemote) }
                    },
                )
                HorizontalDivider()
                ClickablePreferenceItem(
                    title = stringResource(R.string.backup_auto_interval),
                    description = stringResource(
                        R.string.backup_auto_interval_value,
                        backupIntervalLabel(config.effectiveAutoBackupIntervalMinutes),
                    ),
                    enabled = config.autoBackupEnabled && !state.isLoading,
                    onClick = { showInterval = true },
                )
                HorizontalDivider()
                ClickablePreferenceItem(
                    title = stringResource(R.string.backup_auto_trigger),
                    description = backupTriggerLabel(config.autoBackupTrigger),
                    enabled = config.autoBackupEnabled && !state.isLoading,
                    onClick = { showTrigger = true },
                )
                HorizontalDivider()
                ClickablePreferenceItem(
                    title = stringResource(R.string.backup_auto_retention),
                    description = stringResource(
                        R.string.backup_auto_retention_value,
                        retentionLabel(config.effectiveAutoBackupRetentionCount),
                    ),
                    enabled = config.autoBackupEnabled && !state.isLoading,
                    onClick = { showRetention = true },
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
                ) { MediaLoadingIndicator() }
            }
        }
    }

    if (showInterval) {
        OptionsDialog(
            text = stringResource(R.string.backup_auto_interval),
            onDismissClick = { showInterval = false },
        ) {
            items(BackupWebDavConfig.AUTO_BACKUP_INTERVAL_PRESETS) { minutes ->
                RadioTextButton(
                    text = backupIntervalLabel(minutes),
                    selected = minutes == config.effectiveAutoBackupIntervalMinutes,
                    onClick = {
                        viewModel.updateConfigField { it.copy(autoBackupIntervalMinutes = minutes) }
                        showInterval = false
                    },
                )
            }
        }
    }
    if (showTrigger) {
        OptionsDialog(
            text = stringResource(R.string.backup_auto_trigger),
            onDismissClick = { showTrigger = false },
        ) {
            items(BackupWebDavConfig.AUTO_BACKUP_TRIGGERS) { trigger ->
                RadioTextButton(
                    text = backupTriggerLabel(trigger),
                    selected = trigger == config.autoBackupTrigger,
                    onClick = {
                        viewModel.updateConfigField { it.copy(autoBackupTrigger = trigger) }
                        showTrigger = false
                    },
                )
            }
        }
    }

    if (showRetention) {
        OptionsDialog(
            text = stringResource(R.string.backup_auto_retention),
            onDismissClick = { showRetention = false },
        ) {
            items(BackupWebDavConfig.AUTO_BACKUP_RETENTION_PRESETS) { count ->
                RadioTextButton(
                    text = retentionLabel(count),
                    selected = count == config.effectiveAutoBackupRetentionCount,
                    onClick = {
                        viewModel.updateConfigField { it.copy(autoBackupRetentionCount = count) }
                        showRetention = false
                    },
                )
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
    if (state.coverWarnings.isNotEmpty()) {
        NextDialog(
            title = { Text("备份已完成，部分封面未包含") },
            onDismissRequest = viewModel::dismissCoverWarnings,
            confirmButton = { TextButton(onClick = viewModel::dismissCoverWarnings) { Text("知道了") } },
            content = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("设置与书库已保存。以下封面无法取得，书籍信息和原封面地址仍已保留：")
                    state.coverWarnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            },
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
                        MediaLoadingIndicator()
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
                    if (state.isLoading) MediaLoadingIndicator()
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

/** 把分钟数换算成 30 分钟 / 3 小时 / 2 天 / 1 周这类展示文案。 */
@Composable
private fun backupIntervalLabel(minutes: Int): String = when {
    minutes % (7 * 24 * 60) == 0 -> stringResource(R.string.weeks, minutes / (7 * 24 * 60))
    minutes % (24 * 60) == 0 -> stringResource(R.string.days, minutes / (24 * 60))
    minutes % 60 == 0 -> stringResource(R.string.hours, minutes / 60)
    else -> stringResource(R.string.minutes, minutes)
}

@Composable
private fun retentionLabel(count: Int): String =
    if (count <= 0) {
        stringResource(R.string.backup_auto_retention_unlimited)
    } else {
        stringResource(R.string.backup_auto_retention_count, count)
    }

@Composable
private fun backupTriggerLabel(trigger: String): String = stringResource(
    when (trigger) {
        BackupWebDavConfig.AUTO_BACKUP_TRIGGER_ON_LAUNCH -> R.string.backup_auto_trigger_on_launch
        BackupWebDavConfig.AUTO_BACKUP_TRIGGER_ON_BOTH -> R.string.backup_auto_trigger_on_both
        else -> R.string.backup_auto_trigger_on_stop
    },
)
