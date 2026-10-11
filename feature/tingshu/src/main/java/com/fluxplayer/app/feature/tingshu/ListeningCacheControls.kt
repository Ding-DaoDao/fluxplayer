package com.fluxplayer.app.feature.tingshu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxplayer.app.core.tingshu.ListeningErrors
import com.fluxplayer.app.core.ui.cache.BookCoverCache
import com.fluxplayer.app.core.ui.theme.FluxTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AppListeningCacheSettings() {
    val context = LocalContext.current
    val downloads = remember(context) { com.fluxplayer.app.core.tingshu.ListeningDownloads(context) }
    var offlineBytes by remember { mutableStateOf(0L) }
    var offlineLimit by remember { mutableStateOf(downloads.limitBytes) }
    var confirmClear by remember { mutableStateOf(false) }
    val coverEnabled by BookCoverCache.enabled(context).collectAsStateWithLifecycle()
    var coverBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(context) {
        while (true) {
            coverBytes = BookCoverCache.size(context)
            offlineBytes = withContext(Dispatchers.IO) { downloads.reservedBytes() }
            delay(1500)
        }
    }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun operation(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = ListeningErrors.describe(e, "缓存操作失败")
            } finally {
                busy = false
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        error?.let { SourceErrorNotice(it, onDismiss = { error = null }) }
        SourceConfigSwitch("自动缓存封面\n已缓存的封面可直接显示，减少网盘请求。", coverEnabled, !busy) { BookCoverCache.setEnabled(context, it) }
        Text("${coverBytes / (1024 * 1024)} MB / ${BookCoverCache.LIMIT_BYTES / (1024 * 1024)} MB", style = FluxTheme.typography.titleMedium)
        LinearProgressIndicator(progress = { (coverBytes.toFloat() / BookCoverCache.LIMIT_BYTES).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = {
            operation {
                BookCoverCache.clear(context)
                coverBytes = 0
            }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("清除封面缓存") }
        Text("清理缓存会保留账号、书库和收听进度。", style = FluxTheme.typography.bodySmall, color = FluxTheme.colorScheme.onSurfaceVariant)
        Text("离线音频：${offlineBytes / (1024 * 1024)} MB / ${offlineLimit / (1024 * 1024)} MB")
        androidx.compose.foundation.layout.Row {
            listOf(512L, 1024L, 2048L, 5120L).forEach { megabytes ->
                androidx.compose.material3.TextButton(onClick = {
                    downloads.limitBytes = megabytes * 1024 * 1024
                    offlineLimit = downloads.limitBytes
                }) { Text(if (megabytes < 1024) "512 MB" else "${megabytes / 1024} GB") }
            }
        }
        OutlinedButton(onClick = { confirmClear = true }, enabled = !busy) { Text("清除离线音频") }
        Text("空间统计包含等待下载的文件。降低上限不会删除已下载音频。", style = FluxTheme.typography.bodySmall)
    }
    if (confirmClear) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除离线音频？") },
            text = { Text("已下载的音频和等待中的下载任务将被移除，收听进度会保留。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmClear = false
                    operation {
                        withContext(Dispatchers.IO) { downloads.clear() }
                        offlineBytes = 0
                    }
                }) { Text("清除") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}
