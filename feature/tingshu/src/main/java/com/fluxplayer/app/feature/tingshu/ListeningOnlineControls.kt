package com.fluxplayer.app.feature.tingshu

import android.app.DownloadManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import com.fluxplayer.app.core.tingshu.ListeningBook
import com.fluxplayer.app.core.tingshu.ListeningDownload
import com.fluxplayer.app.core.tingshu.ListeningDownloads
import com.fluxplayer.app.core.tingshu.TingshuRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ListeningOnlineControls(book: ListeningBook, state: ListeningPlaybackState, repository: TingshuRepository) {
    val context = LocalContext.current
    val downloads = remember(context) { ListeningDownloads(context) }
    var showing by remember(book.key) { mutableStateOf(false) }
    var selected by remember(book.key) { mutableStateOf(emptySet<Int>()) }
    var statuses by remember(book) { mutableStateOf(emptyMap<Int, ListeningDownload>()) }
    var message by remember(book.key) { mutableStateOf<String?>(null) }
    var busy by remember(book.key) { mutableStateOf(false) }
    var allowMetered by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(showing, book) {
        if (!showing) return@LaunchedEffect
        while (true) {
            statuses = withContext(Dispatchers.IO) { downloads.states(book) }
            delay(2000)
        }
    }
    com.fluxplayer.app.feature.player.AudioDownloadButton(onClick = {
        selected = setOf(state.index)
        showing = true
    })
    if (showing) {
        AlertDialog(onDismissRequest = { if (!busy) showing = false }, title = { Text("下载章节") }, text = {
            Column {
                Text("每次最多选择 10 章。下载完成后自动优先使用离线音频。")
                Row {
                    Checkbox(checked = allowMetered, enabled = !busy, onCheckedChange = { allowMetered = it })
                    Text("允许使用移动网络下载")
                }
                message?.let { Text(it) }
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    itemsIndexed(book.episodes, key = { _, episode -> episode.url }) { index, episode ->
                        val status = statuses[index]
                        Row {
                            Checkbox(checked = index in selected, enabled = !busy && (index in selected || selected.size < 10), onCheckedChange = { checked -> selected = if (checked) selected + index else selected - index })
                            Column(Modifier.weight(1f)) {
                                Text("${index + 1}. ${episode.title}")
                                Text(
                                    when (status?.status) {
                                        DownloadManager.STATUS_SUCCESSFUL -> "已下载"
                                        DownloadManager.STATUS_FAILED -> "下载失败，请检查网络或重新下载"
                                        DownloadManager.STATUS_PAUSED -> "等待网络或系统恢复"
                                        DownloadManager.STATUS_RUNNING -> if (status.size > 0) "下载中 ${status.downloaded * 100 / status.size}%" else "下载中"
                                        DownloadManager.STATUS_PENDING -> "等待下载"
                                        else -> "未下载"
                                    },
                                )
                            }
                            if (status != null) {
                                TextButton(enabled = !busy && !state.playing, onClick = {
                                    downloads.remove(book, index)
                                    statuses = statuses - index
                                }) { Text("移除") }
                            }
                        }
                    }
                }
            }
        }, confirmButton = {
            TextButton(enabled = selected.isNotEmpty() && !busy, onClick = {
                busy = true
                message = "正在解析下载地址…"
                val selection = selected.sorted()
                scope.launch {
                    var accepted = 0
                    val failures = mutableListOf<String>()
                    try {
                        selection.forEach { index ->
                            try {
                                downloads.enqueue(repository, book, index, allowMetered)
                                accepted++
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                                failures += "第 ${index + 1} 章：${error.message}"
                            }
                        }
                        message = "已安排 $accepted 章下载" + failures.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "\n").orEmpty()
                        selected = emptySet()
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "准备中…" else "下载所选章节") }
        }, dismissButton = { TextButton(enabled = !busy, onClick = { showing = false }) { Text("关闭") } })
    }
}
