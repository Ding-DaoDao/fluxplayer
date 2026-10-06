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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AppListeningCacheSettings() {
    val context = LocalContext.current
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
    }
}
