package com.fluxplayer.app.feature.tingshu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.fluxplayer.app.core.tingshu.ListeningBook
import com.fluxplayer.app.core.tingshu.ListeningErrors
import com.fluxplayer.app.core.ui.cache.BookCoverCache
import com.fluxplayer.app.core.ui.theme.FluxTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AppListeningCacheSettings() {
    val context = LocalContext.current
    val cache = remember { ListeningAudioCache.get(context) }
    val state by cache.state.collectAsStateWithLifecycle()
    val coverEnabled by BookCoverCache.enabled(context).collectAsStateWithLifecycle()
    var coverBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(context) {
        while (true) {
            coverBytes = BookCoverCache.size(context)
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
        SourceConfigSwitch("自动缓存音频\n播放时保存音频，空间不足时自动清理较早的缓存。", state.automatic, !busy, cache::setAutomatic)
        Text("${state.usedBytes / (1024 * 1024)} MB / ${state.limitMb} MB", style = FluxTheme.typography.titleMedium)
        LinearProgressIndicator(progress = { (state.usedBytes.toFloat() / (state.limitMb * 1024L * 1024)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        val limits = linkedMapOf("128 MB" to 128, "256 MB" to 256, "512 MB" to 512, "1 GB" to 1024)
        SourceConfigOptions("音频缓存上限", limits.keys.toList(), limits.filterValues { it == state.limitMb }.keys, !busy) { label -> operation { cache.setLimit(limits.getValue(label)) } }
        state.download?.let {
            Text("正在缓存：$it")
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = cache::cancelDownload) { Text("取消缓存任务") }
        }
        state.message?.let { Text(it) }
        error?.let { SourceErrorNotice(it, onDismiss = { error = null }) }
        OutlinedButton(onClick = { operation { cache.clear() } }, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("清除音频缓存") }
        HorizontalDivider(color = FluxTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
    }
}

@Composable
internal fun ListeningCacheControls(book: ListeningBook, index: Int) {
    val context = LocalContext.current
    val cache = remember { ListeningAudioCache.get(context) }
    val state by cache.state.collectAsStateWithLifecycle()
    var offline by remember(book.key, index) { mutableStateOf(false) }
    LaunchedEffect(book.key, index, state.completedChapters, state.usedBytes) { offline = cache.offlineResource(book, index) != null }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row {
            TextButton(onClick = { cache.download(book, index) }, enabled = state.download == null) { Text(if (offline) "本章已缓存" else "缓存本章") }
            TextButton(onClick = { cache.download(book, index, true) }, enabled = state.download == null) { Text("缓存整本") }
            if (state.download != null) TextButton(onClick = cache::cancelDownload) { Text("取消缓存") }
        }
        state.download?.let { Text("缓存中：$it ${state.percent?.toInt()?.let { percent -> "$percent%" }.orEmpty()}", style = FluxTheme.typography.bodySmall) }
        state.message?.let { Text(it, style = FluxTheme.typography.bodySmall) }
    }
}
