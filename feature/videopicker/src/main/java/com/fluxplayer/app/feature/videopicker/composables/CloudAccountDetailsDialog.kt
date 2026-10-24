package com.fluxplayer.app.feature.videopicker.composables

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.data.cloud.CloudAccountLoginInfo
import com.fluxplayer.app.core.data.cloud.CloudAccountScope
import com.fluxplayer.app.core.data.cloud.CloudAccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun CloudAccountDetailsDialog(provider: String, account: CloudAccountStore.Account, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var info by remember { mutableStateOf(CloudAccountLoginInfo(emptyList(), null)) }
    var visible by remember { mutableStateOf(emptySet<String>()) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(account.id) {
        runCatching {
            withContext(Dispatchers.IO) {
                CloudAccountScope.withAccount(context, provider, account.id) {
                    CloudAccountLoginInfo.read(context, provider, account.id)
                }
            }
        }.onSuccess { info = it }.onFailure { message = it.message }
    }
    fun copy(key: String?) {
        try {
            check(CloudAccountStore.contains(context, provider, account.id)) { "账号已移除" }
            // 点击时重新读取所选账号，避免把菜单打开时的旧凭证复制出去。
            val current = CloudAccountLoginInfo.read(context, provider, account.id)
            val text = if (key == null) current.json else current.fields.firstOrNull { it.key == key }?.value ?: error("登录信息已变化")
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("网盘登录信息", text))
            message = "已复制"
        } catch (_: Exception) {
            message = "复制失败，请重新打开账号详情"
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(account.name + " · 账号配置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (info.preferred == null) "暂无可复用的登录凭据" else if (info.fields.any { it.key == "cookie" }) "Cookie 登录 · 凭证独立加密保存" else "Token 登录 · 凭证独立加密保存", style = MaterialTheme.typography.bodySmall)
                info.fields.forEach { (key, label, value) ->
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    Text(if (key in visible) value else "••••••••", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { visible = if (key in visible) visible - key else visible + key }) { Text(if (key in visible) "隐藏" else "显示") }
                        TextButton(onClick = { copy(key) }) { Text("复制") }
                    }
                }
                message?.let { Text(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = { TextButton(enabled = info.fields.isNotEmpty(), onClick = { copy(null) }) { Text("复制全部登录信息") } },
    )
}
