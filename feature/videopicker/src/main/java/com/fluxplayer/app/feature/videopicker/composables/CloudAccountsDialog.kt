package com.fluxplayer.app.feature.videopicker.composables

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.common.CloudAccountIdentity
import com.fluxplayer.app.core.data.cloud.CloudAccountLoginInfo
import com.fluxplayer.app.core.data.cloud.CloudAccountRecovery
import com.fluxplayer.app.core.data.cloud.CloudAccountStore
import com.fluxplayer.app.core.data.quark.UcTvService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CloudAccountsDialog(provider: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var details by remember { mutableStateOf<CloudAccountStore.Account?>(null) }
    var tvAccount by remember { mutableStateOf<CloudAccountStore.Account?>(null) }
    var menuId by remember { mutableStateOf<String?>(null) }
    var recoveryVisible by remember { mutableStateOf(false) }
    var accounts by remember { mutableStateOf(emptyList<CloudAccountStore.Account>()) }
    var refresh by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var remove by remember { mutableStateOf<CloudAccountStore.Account?>(null) }
    var passwordAction by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var imported by remember { mutableStateOf<ByteArray?>(null) }
    var exported by remember { mutableStateOf<ByteArray?>(null) }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { action() }
                refresh++
            } catch (error: Exception) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                message = error.message ?: "操作失败，请重试"
            } finally {
                busy = false
            }
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val data = exported
        exported = null
        if (uri != null && data != null) {
            run {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(data) } ?: error("无法写入备份文件")
                    message = "账号备份已保存"
                } finally {
                    data.fill(0)
                }
            }
        } else {
            data?.fill(0)
        }
    }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            run {
                imported = context.contentResolver.openInputStream(uri)?.use { stream ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 8 * 1024 * 1024) { "备份文件过大" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } ?: error("无法读取备份")
                passwordAction = "import"
            }
        }
    }
    LaunchedEffect(refresh) {
        runCatching { withContext(Dispatchers.IO) { CloudAccountStore.accounts(context, provider) } }
            .onSuccess { accounts = it }.onFailure { message = it.message }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (recoveryVisible) "账号备份与恢复" else (mapOf("alipan" to "阿里云盘", "pan123" to "123 云盘", "quark" to "夸克云盘", "uc" to "UC 网盘", "yun139" to "移动云盘", "cloud189" to "天翼云盘")[provider] ?: "网盘") + "账号管理") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!recoveryVisible) {
                    Text("点击账号切换。账号凭证独立保存，播放记录保留所属账号。", style = MaterialTheme.typography.bodySmall)
                    accounts.forEach { account ->
                        Column(Modifier.fillMaxWidth().clickable(enabled = !busy && !account.active) { run { CloudAccountStore.select(context, provider, account.id) } }) {
                            Text((if (account.active) "✓ " else "") + account.name, style = MaterialTheme.typography.titleSmall)
                            Text(if (!account.readable) "本地凭证无法读取，请恢复或重新登录" else if (!account.configured) "未登录" else if (account.active) "当前账号" else "已保存", style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(enabled = !busy, onClick = {
                                    run {
                                        CloudAccountStore.beginLogin(context, provider, account.id)
                                        withContext(Dispatchers.Main) { onDismiss() }
                                    }
                                }) { Text(if (account.configured) "重新登录" else "登录") }
                                Box {
                                    TextButton(enabled = !busy, onClick = { menuId = account.id }) { Text("更多") }
                                    DropdownMenu(expanded = menuId == account.id, onDismissRequest = { menuId = null }) {
                                        DropdownMenuItem(text = { Text("账号详情与登录信息") }, onClick = {
                                            menuId = null
                                            details = account
                                        })
                                        DropdownMenuItem(text = { Text("复制登录信息") }, enabled = account.configured && account.readable, onClick = {
                                            menuId = null
                                            run {
                                                val field = CloudAccountLoginInfo.read(context, provider, account.id).preferred ?: error("暂无可复制的登录凭据")
                                                withContext(Dispatchers.Main) { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(field.label, field.value)) }
                                                message = "已复制登录信息"
                                            }
                                        })
                                        if (provider == "uc") {
                                            DropdownMenuItem(text = { Text("TV 播放授权") }, enabled = account.configured && account.readable, onClick = {
                                                menuId = null
                                                tvAccount = account
                                            })
                                        }
                                        DropdownMenuItem(text = { Text("改名") }, onClick = {
                                            menuId = null
                                            editId = account.id
                                            name = account.name
                                        })
                                        DropdownMenuItem(text = { Text("移除") }, onClick = {
                                            menuId = null
                                            remove = account
                                        })
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                    TextButton(enabled = !busy, onClick = {
                        editId = "new"
                        name = ""
                    }) { Text("添加账号") }
                } else {
                    Text("本地数据恢复", style = MaterialTheme.typography.titleSmall)
                    Text("设置恢复密码后，本地副本会随登录凭证更新。密码备份包含六家的全部账号与当前账号选择。", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = { passwordAction = "enable" }) { Text("设置 / 更换恢复密码") }
                    TextButton(enabled = !busy && CloudAccountRecovery.available(context), onClick = { passwordAction = "unlock" }) { Text("使用密码恢复本地凭证") }
                    Row {
                        TextButton(enabled = !busy, onClick = { passwordAction = "export" }) { Text("导出密码备份") }
                        TextButton(enabled = !busy, onClick = { load.launch(arrayOf("*/*")) }) { Text("导入密码备份") }
                    }
                    TextButton(enabled = !busy, onClick = { run { CloudAccountStore.restorePrevious(context, provider) } }) { Text("恢复上一份账号列表") }
                    TextButton(enabled = !busy, onClick = { refresh++ }) { Text("重新读取") }
                }
                if (busy) Text("正在处理，请稍候…")
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = { if (recoveryVisible) recoveryVisible = false else onDismiss() }) { Text(if (recoveryVisible) "返回" else "完成") } },
        dismissButton = { if (!recoveryVisible) TextButton(enabled = !busy, onClick = { recoveryVisible = true }) { Text("备份与恢复") } },
    )
    details?.let { account -> CloudAccountDetailsDialog(provider, account) { details = null } }
    tvAccount?.let { account ->
        val service = remember(account.id) { CloudAccountIdentity.runAs("uc", account.id) { UcTvService(context) } }
        UcTvAuthorizationDialog(service, onDismiss = { tvAccount = null }, accountId = account.id, accountName = account.name)
    }
    if (editId != null) {
        AlertDialog(
            onDismissRequest = { editId = null },
            title = { Text(if (editId == "new") "添加账号" else "账号名称") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    val id = editId
                    val label = name
                    editId = null
                    run {
                        if (id == "new") {
                            CloudAccountStore.add(context, provider, label)
                            message = "已切换至新账号，请关闭管理界面完成登录"
                        } else {
                            CloudAccountStore.rename(context, provider, id!!, label)
                        }
                    }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editId = null }) { Text("取消") } },
        )
    }
    remove?.let { account ->
        AlertDialog(
            onDismissRequest = { remove = null },
            title = { Text("移除账号") },
            text = { Text("移除“${account.name}”后，该账号的播放和下载将无法刷新链接。网盘内文件不会删除。") },
            confirmButton = {
                TextButton(onClick = {
                    remove = null
                    run { CloudAccountStore.remove(context, provider, account.id) }
                }) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { remove = null }) { Text("取消") } },
        )
    }
    passwordAction?.let { action ->
        val creating = action == "enable" || action == "export"
        AlertDialog(
            onDismissRequest = {
                passwordAction = null
                password = ""
                confirmation = ""
                imported?.fill(0)
                imported = null
            },
            title = {
                Text(
                    when (action) {
                        "enable" -> "设置恢复密码"
                        "export" -> "导出账号备份"
                        "import" -> "恢复账号备份"
                        else -> "恢复本地凭证"
                    },
                )
            },
            text = {
                Column {
                    Text(if (creating) "密码至少 8 位，请妥善保管。" else "恢复将替换六家网盘的账号配置，原配置会先在本机存档。")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(password, { password = it }, label = { Text("恢复密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    if (creating) OutlinedTextField(confirmation, { confirmation = it }, label = { Text("确认密码") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
            },
            confirmButton = {
                TextButton(enabled = password.length >= 8 && (!creating || password == confirmation), onClick = {
                    val secret = password.toCharArray()
                    val bytes = imported
                    imported = null
                    passwordAction = null
                    password = ""
                    confirmation = ""
                    run {
                        try {
                            when (action) {
                                "enable" -> CloudAccountRecovery.enable(context, secret)
                                "export" -> {
                                    exported = CloudAccountRecovery.export(context, secret)
                                    withContext(Dispatchers.Main) { save.launch("FluxPlayer-网盘账号备份.fluxaccounts") }
                                }
                                "import" -> CloudAccountRecovery.restore(context, bytes ?: error("请选择备份文件"), secret)
                                "unlock" -> CloudAccountRecovery.unlock(context, secret)
                            }
                            if (action != "export") message = "操作完成"
                        } finally {
                            secret.fill('\u0000')
                            bytes?.fill(0)
                        }
                    }
                }) { Text(if (creating) "保存" else "确认恢复") }
            },
            dismissButton = {
                TextButton(onClick = {
                    passwordAction = null
                    password = ""
                    confirmation = ""
                    imported?.fill(0)
                    imported = null
                }) { Text("取消") }
            },
        )
    }
}
