package com.fluxplayer.app.feature.tingshu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
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
    remember(context) { ListeningStreamCache.initialize(context) }
    val streamEnabled by ListeningStreamCache.enabled.collectAsStateWithLifecycle()
    val streamLimit by ListeningStreamCache.limit.collectAsStateWithLifecycle()
    var streamBytes by remember { mutableStateOf(0L) }
    var confirmClearStream by remember { mutableStateOf(false) }
    val downloads = remember(context) { com.fluxplayer.app.core.tingshu.ListeningDownloads(context) }
    var offlineBytes by remember { mutableStateOf(0L) }
    var offlineLimit by remember { mutableStateOf(downloads.limitBytes) }
    var confirmClear by remember { mutableStateOf(false) }
    val coverEnabled by BookCoverCache.enabled(context).collectAsStateWithLifecycle()
    val coverLimit by BookCoverCache.capacity(context).collectAsStateWithLifecycle()
    val activeCoverLimit = remember(context) { BookCoverCache.activeCapacity(context) }
    var coverBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(context) {
        while (true) {
            coverBytes = BookCoverCache.size(context)
            offlineBytes = withContext(Dispatchers.IO) { downloads.reservedBytes() }
            streamBytes = withContext(Dispatchers.IO) { ListeningStreamCache.size(context) }
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
        Text("封面缓存：${coverBytes / (1024 * 1024)} MB / ${cacheCapacityLabel(activeCoverLimit)}", style = FluxTheme.typography.titleMedium)
        LinearProgressIndicator(progress = { (coverBytes.toFloat() / activeCoverLimit).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BookCoverCache.capacityOptions.forEach { bytes ->
                FilterChip(
                    selected = coverLimit == bytes,
                    onClick = { BookCoverCache.setCapacity(context, bytes) },
                    enabled = !busy,
                    label = { Text(cacheCapacityLabel(bytes)) },
                )
            }
        }
        Text(
            if (coverLimit == activeCoverLimit) "空间上限按需使用，不会预先占用。更改容量在下次启动应用时生效。" else "已保存 ${cacheCapacityLabel(coverLimit)}，下次启动应用时生效；当前使用 ${cacheCapacityLabel(activeCoverLimit)} 上限。",
            style = FluxTheme.typography.bodySmall,
            color = FluxTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = {
            operation {
                BookCoverCache.clear(context)
                coverBytes = 0
            }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("清除封面缓存") }
        Text("清理缓存会保留账号、书库和收听进度。", style = FluxTheme.typography.bodySmall, color = FluxTheme.colorScheme.onSurfaceVariant)
        SourceConfigSwitch("在线播放缓存\n边播放边存入磁盘，再次播放优先读取已缓存的音频。", streamEnabled, !busy) { ListeningStreamCache.setEnabled(context, it) }
        Text("在线播放缓存：${streamBytes / (1024 * 1024)} MB / ${cacheCapacityLabel(streamLimit)}", style = FluxTheme.typography.titleMedium)
        if (streamLimit != Long.MAX_VALUE) {
            LinearProgressIndicator(progress = { (streamBytes.toFloat() / streamLimit).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ListeningStreamCache.capacities.forEach { bytes ->
                FilterChip(
                    selected = streamLimit == bytes,
                    onClick = {
                        operation {
                            withContext(Dispatchers.IO) { ListeningStreamCache.setLimit(context, bytes) }
                            streamBytes = withContext(Dispatchers.IO) { ListeningStreamCache.size(context) }
                        }
                    },
                    enabled = !busy,
                    label = { Text(cacheCapacityLabel(bytes)) },
                )
            }
        }
        Text("容量调整立即生效，满后自动清理较久未使用的内容。开关在下次音频请求时生效。缓存播放过的部分；完整保存请使用下载。", style = FluxTheme.typography.bodySmall, color = FluxTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { confirmClearStream = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("清除在线播放缓存") }
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
    if (confirmClearStream) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmClearStream = false },
            title = { Text("清除在线播放缓存？") },
            text = { Text("封面、手动下载和收听进度会保留。若正在播放，后续读取的音频会继续缓存。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmClearStream = false
                    operation {
                        withContext(Dispatchers.IO) { ListeningStreamCache.clear(context) }
                        streamBytes = withContext(Dispatchers.IO) { ListeningStreamCache.size(context) }
                    }
                }) { Text("清除") }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmClearStream = false }) { Text("取消") } },
        )
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

private fun cacheCapacityLabel(bytes: Long): String = when {
    bytes == Long.MAX_VALUE -> "不限"
    bytes >= 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024 * 1024)} GB"
    else -> "${bytes / (1024 * 1024)} MB"
}
