package com.fluxplayer.app.feature.tingshu

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
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
import com.fluxplayer.app.core.ui.theme.FluxTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ListeningCacheSettings() {
    val context = LocalContext.current
    val cache = remember { ListeningAudioCache.get(context) }
    val state by cache.state.collectAsStateWithLifecycle()
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
                error = e.message
            } finally {
                busy = false
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("听书音频缓存", style = FluxTheme.typography.titleSmall)
        Text("已使用 ${state.usedBytes / (1024 * 1024)} MB / ${state.limitMb} MB · ${state.completedChapters} 个完整章节")
        Text("播放时自动缓存；在播放页可缓存章节或整本书。空间不足时清理最早使用的缓存。", style = FluxTheme.typography.bodySmall)
        Row {
            listOf(128, 256, 512).forEach { mb ->
                TextButton(onClick = { operation { cache.setLimit(mb) } }, enabled = !busy) { Text(if (state.limitMb == mb) "✓ $mb MB" else "$mb MB") }
            }
        }
        TextButton(onClick = { operation { cache.setLimit(1024) } }, enabled = !busy) { Text(if (state.limitMb == 1024) "✓ 1 GB" else "1 GB") }
        state.download?.let {
            Text("正在缓存：$it")
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = cache::cancelDownload) { Text("取消缓存任务") }
        }
        state.message?.let { Text(it) }
        error?.let { Text(it, color = FluxTheme.colorScheme.error) }
        TextButton(onClick = { operation { cache.clear() } }, enabled = !busy) { Text("清除音频缓存") }
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
