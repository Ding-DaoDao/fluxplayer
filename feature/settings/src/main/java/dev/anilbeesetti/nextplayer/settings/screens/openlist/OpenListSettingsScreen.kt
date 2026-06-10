package dev.anilbeesetti.nextplayer.settings.screens.openlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.anilbeesetti.nextplayer.core.data.openlist.OpenListServerState
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.CancelButton
import dev.anilbeesetti.nextplayer.core.ui.components.NextDialog
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import org.json.JSONObject

@Composable
fun OpenListSettingsScreen(
    onNavigateUp: () -> Unit,
    viewModel: OpenListSettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showChangePasswordDialog by rememberSaveable { mutableStateOf(false) }
    var showAddStorageSheet by rememberSaveable { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startService()
        }
    }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = "OpenList",
                navigationIcon = {
                    FilledTonalIconButton(onClick = onNavigateUp) {
                        Icon(
                            imageVector = NextIcons.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 服务控制
            ServiceControlCard(
                state = uiState.serverState,
                isAutoStart = uiState.isAutoStart,
                onStart = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.startService()
                    }
                },
                onStop = viewModel::stopService,
                onAutoStartChange = viewModel::setAutoStart,
            )

            // 密码管理
            AdminSection(
                password = uiState.password,
                onChangePassword = { showChangePasswordDialog = true },
                onRandomPassword = viewModel::randomPasswordViaCli,
            )

            // 存储管理（已移除）

            // 错误提示
            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (showChangePasswordDialog) {
        ChangePasswordDialog(
            onDismiss = { showChangePasswordDialog = false },
            onConfirm = { newPwd ->
                viewModel.changePasswordViaCli(newPwd)
                showChangePasswordDialog = false
            },
        )
    }


}

@Composable
internal fun ServiceControlCard(
    state: OpenListServerState,
    isAutoStart: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAutoStartChange: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = NextIcons.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "服务状态",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = state is OpenListServerState.Running ||
                        state is OpenListServerState.Starting,
                    onCheckedChange = { checked ->
                        if (checked) onStart() else onStop()
                    },
                )
            }

            Spacer(Modifier.height(8.dp))
            StatusRow(state)

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("开机自启", modifier = Modifier.weight(1f))
                Switch(checked = isAutoStart, onCheckedChange = onAutoStartChange)
            }
        }
    }
}

@Composable
internal fun StatusRow(state: OpenListServerState) {
    val (label, color) = when (state) {
        is OpenListServerState.Stopped -> "已停止" to MaterialTheme.colorScheme.outline
        is OpenListServerState.Starting -> "启动中" to MaterialTheme.colorScheme.tertiary
        is OpenListServerState.Running -> "运行中 (http://127.0.0.1:${state.port})" to MaterialTheme.colorScheme.primary
        is OpenListServerState.Error -> "错误: ${state.message}" to MaterialTheme.colorScheme.error
    }
    Text(label, color = color, style = MaterialTheme.typography.bodyMedium)
}

@Composable
internal fun AdminSection(
    password: String?,
    onChangePassword: () -> Unit,
    onRandomPassword: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("账户 & 密码", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(NextIcons.Priority, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("admin", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = password ?: "未设置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val clipboard = LocalClipboardManager.current
                if (password != null) {
                    IconButton(onClick = { clipboard.setText(AnnotatedString(password)) }, modifier = Modifier.size(32.dp)) {
                        Icon(NextIcons.Copy, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onChangePassword) { Text("修改密码") }
                OutlinedButton(onClick = onRandomPassword) { Text("随机密码") }
            }
        }
    }
}

// StorageSection / StorageItem 已移除

@Composable
internal fun ChangePasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    NextDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改密码") },
        content = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("新密码") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotBlank(),
                onClick = { onConfirm(password) },
            ) { Text("确认") }
        },
        dismissButton = { CancelButton(onClick = onDismiss) },
    )
}


