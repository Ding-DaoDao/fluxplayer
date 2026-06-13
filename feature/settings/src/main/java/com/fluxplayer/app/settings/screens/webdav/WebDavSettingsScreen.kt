package com.fluxplayer.app.settings.screens.webdav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxplayer.app.core.model.WebDavServer
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.components.CancelButton
import com.fluxplayer.app.core.ui.components.ClickablePreferenceItem
import com.fluxplayer.app.core.ui.components.DoneButton
import com.fluxplayer.app.core.ui.components.ListSectionTitle
import com.fluxplayer.app.core.ui.components.NextDialog
import com.fluxplayer.app.core.ui.components.FluxSettingsScaffold
import com.fluxplayer.app.core.ui.designsystem.NextIcons

@Composable
fun WebDavSettingsScreen(
    onNavigateUp: () -> Unit,
    viewModel: WebDavSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    WebDavSettingsContent(
        uiState = uiState,
        onNavigateUp = onNavigateUp,
        onAddClick = viewModel::showAddDialog,
        onEditClick = viewModel::showEditDialog,
        onDeleteClick = viewModel::showDeleteConfirmation,
        onSetActive = viewModel::toggleServerActive,
    )

    // Add dialog
    if (uiState.showAddDialog) {
        ServerEditDialog(
            title = "添加 WebDAV 服务器",
            server = uiState.editingServer,
            onNameChange = viewModel::updateEditingName,
            onUrlChange = viewModel::updateEditingUrl,
            onUsernameChange = viewModel::updateEditingUsername,
            onPasswordChange = viewModel::updateEditingPassword,
            onSave = viewModel::saveServer,
            onDismiss = viewModel::dismissDialog,
            onTest = viewModel::testConnection,
            isTesting = uiState.testingServer,
            testResult = uiState.testResult,
        )
    }

    // Edit dialog
    uiState.showEditDialog?.let {
        ServerEditDialog(
            title = "编辑 WebDAV 服务器",
            server = uiState.editingServer,
            onNameChange = viewModel::updateEditingName,
            onUrlChange = viewModel::updateEditingUrl,
            onUsernameChange = viewModel::updateEditingUsername,
            onPasswordChange = viewModel::updateEditingPassword,
            onSave = viewModel::saveServer,
            onDismiss = viewModel::dismissDialog,
            onTest = viewModel::testConnection,
            isTesting = uiState.testingServer,
            testResult = uiState.testResult,
        )
    }

    // Delete confirmation
    uiState.showDeleteConfirmation?.let { server ->
        NextDialog(
            onDismissRequest = viewModel::dismissDialog,
            title = {
                Text(
                    text = "删除服务器",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteServer(server) }) {
                    Text(text = "删除")
                }
            },
            dismissButton = { CancelButton(onClick = viewModel::dismissDialog) },
            content = {
                Text(
                    text = "确定要删除「${server.name}」吗？",
                    style = MaterialTheme.typography.titleSmall,
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WebDavSettingsContent(
    uiState: WebDavSettingsUiState,
    onNavigateUp: () -> Unit,
    onAddClick: () -> Unit,
    onEditClick: (WebDavServer) -> Unit,
    onDeleteClick: (WebDavServer) -> Unit,
    onSetActive: (String) -> Unit,
) {
    FluxSettingsScaffold(
        title = "WebDAV",
        onNavigateUp = onNavigateUp,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(state = rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            ListSectionTitle(text = "服务器列表")

            if (uiState.servers.isEmpty()) {
                Text(
                    text = "暂无服务器，点击下方按钮添加",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                Column {
                    uiState.servers.forEachIndexed { index, server ->
                        ServerCard(
                            server = server,
                            isActive = server.id in uiState.activeServerIds,
                            onActivate = { onSetActive(server.id) },
                            onEdit = { onEditClick(server) },
                            onDelete = { onDeleteClick(server) },
                            isFirstItem = index == 0,
                            isLastItem = index == uiState.servers.lastIndex,
                        )
                        if (index < uiState.servers.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            ClickablePreferenceItem(
                title = "添加服务器",
                icon = NextIcons.FileOpen,
                onClick = onAddClick,
                isFirstItem = true,
                isLastItem = true,
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ServerCard(
    server: WebDavServer,
    isActive: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    isFirstItem: Boolean,
    isLastItem: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = server.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = isActive,
                onCheckedChange = { onActivate() },
            )
            if (!isActive) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = NextIcons.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (!isLastItem) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun ServerEditDialog(
    title: String,
    server: WebDavServer,
    onNameChange: (String) -> Unit,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onTest: () -> Unit,
    isTesting: Boolean,
    testResult: String?,
) {
    NextDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = title, modifier = Modifier.fillMaxWidth())
        },
        content = {
            OutlinedTextField(
                value = server.name,
                onValueChange = onNameChange,
                label = { Text("名称") },
                placeholder = { Text("例如：我的NAS") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = server.url,
                onValueChange = onUrlChange,
                label = { Text("服务器地址") },
                placeholder = { Text("http://192.168.1.100:5005") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = server.username,
                onValueChange = onUsernameChange,
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = server.password,
                onValueChange = onPasswordChange,
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Test connection button
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onTest,
                    enabled = !isTesting && server.url.isNotBlank(),
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("测试连接")
                }
                testResult?.let { result ->
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (result == "ok") "✓ 连接成功" else "✗ $result",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (result == "ok")
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            DoneButton(
                enabled = server.name.isNotBlank() && server.url.isNotBlank(),
                onClick = onSave,
            )
        },
        dismissButton = { CancelButton(onClick = onDismiss) },
    )
}
