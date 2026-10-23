package com.fluxplayer.app.feature.videopicker.composables

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.data.cloud.CloudProviderFeatures
import com.fluxplayer.app.core.data.cloud.CloudSearchProgress
import com.fluxplayer.app.core.data.cloud.CloudRemoteFile
import com.fluxplayer.app.core.data.cloud.searchCloudTree
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.core.ui.components.MediaLoadingIndicator
import com.fluxplayer.app.core.ui.components.MediaPageLoading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun CloudSearchDialog(
    features: CloudProviderFeatures,
    onPlay: (Uri, String?) -> Unit,
    onDismiss: () -> Unit,
    rootId: String = features.rootId,
    rootLabel: String = "网盘",
    readDirectory: suspend (String) -> List<CloudRemoteFile> = features::listPersonalFiles,
    resolvePlay: (suspend (CloudRemoteFile) -> Uri)? = null,
    resolveDownload: (suspend (CloudRemoteFile) -> String)? = null,
    canAccessFiles: Boolean = true,
    playLabel: String = "播放",
    downloadLabel: String = "下载",
) {
    var query by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(CloudSearchProgress(emptyList(), 0, 0, 0)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var folders by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var files by remember { mutableStateOf(emptyList<CloudRemoteFile>()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    fun run(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = ""
        job = scope.launch {
            try { block() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) { message = error.message ?: "操作失败，请重试" } finally { busy = false }
        }
    }
    fun search() {
        if (query.isBlank()) return
        keyboard?.hide()
        folders = emptyList()
        progress = CloudSearchProgress(emptyList(), 0, 0, 0)
        run {
            val result = searchCloudTree(query, rootId, rootLabel, readDirectory) { progress = it }
            message = when {
                result.limited -> "已达到搜索上限，显示已找到的结果"
                result.failedFolders > 0 -> "搜索完成，${result.failedFolders} 个目录未能读取"
                else -> "搜索完成，共找到 ${result.hits.size} 项"
            }
        }
    }
    fun browse(file: CloudRemoteFile) = run {
        val listing = readDirectory(file.id)
        files = listing
        folders = folders + (file.id to file.name)
    }
    CloudFeatureBrowser(
        title = "搜索网盘", subtitle = folders.lastOrNull()?.second ?: "搜索${rootLabel}中的文件和文件夹", busy = busy,
        onBack = {
            if (folders.size > 1) run {
                val previous = folders.dropLast(1)
                files = readDirectory(previous.last().first)
                folders = previous
            } else if (folders.isNotEmpty()) folders = emptyList() else onDismiss()
        },
        // 忙碌时返回即停止搜索，不放独立的「停止」按钮
        onStop = { job?.cancel(); message = "已停止搜索，保留已找到的结果" },
        onClose = onDismiss,
        // 加载态由本页自己画（空结果用整页加载，有结果时用行内指示），避免两个动画
        showBusyIndicator = false,
        bottomBar = {},
    ) {
        if (folders.isEmpty()) {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                label = { Text("文件名关键词") }, singleLine = true, enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
                trailingIcon = { TextButton(enabled = !busy && query.isNotBlank(), onClick = ::search) { Text("搜索") } },
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (busy) MediaLoadingIndicator(Modifier.size(16.dp))
                Text(
                    if (busy) "已搜索 ${progress.folders} 个目录 · 找到 ${progress.hits.size} 项" else message.ifBlank { "输入关键词，开始搜索" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (message.isNotBlank()) Text(message, Modifier.padding(20.dp))
        val shown = if (folders.isEmpty()) progress.hits.map { it.file to it.folderPath } else files.map { it to folders.joinToString(" / ") { entry -> entry.second } }
        if (shown.isEmpty()) {
            if (busy) {
                // 与全站一致的形状变换加载动画（居中的页面级加载）
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    MediaPageLoading(if (folders.isEmpty()) "正在查找文件" else "正在读取目录")
                }
            } else {
                CloudFeatureEmptyState(if (folders.isNotEmpty()) "此目录暂无文件" else "暂无搜索结果", Modifier.weight(1f))
            }
        } else LazyColumn(Modifier.weight(1f)) {
            items(shown, key = { "${it.first.parentId}/${it.first.id}" }) { (file, path) ->
                Column {
                    CloudFeatureFileRow(
                        file, enabled = !busy, onOpen = if (file.directory) ({ browse(file) }) else null,
                        actions = if (file.directory || !canAccessFiles) emptyList() else buildList {
                            val media = WebDavResource(file.name, file.id, false).let { it.isAudio || it.isVideo }
                            add(downloadLabel to {
                                run {
                                    val url = resolveDownload?.invoke(file) ?: features.resolvePersonalDownload(file)
                                    com.fluxplayer.app.core.common.CloudDownloadHelper(context).downloadFile(url, file.name.replace('/', '_').replace('\\', '_'), headers = features.fileHeaders())
                                    message = "已加入系统下载：${file.name}"
                                }
                            })
                            if (media) add(playLabel to {
                                run {
                                    val uri = resolvePlay?.invoke(file) ?: kotlin.run {
                                        features.resolvePersonalFile(file.id, file.parentId)
                                        Uri.Builder().scheme("cloud").authority(features.provider).appendPath(file.id)
                                        .appendQueryParameter("extra_parent", file.parentId).appendQueryParameter("file_name", file.name).build()
                                    }
                                    onPlay(uri, file.name)
                                }
                            })
                        },
                    )
                    if (folders.isEmpty()) Text(path, Modifier.padding(start = 84.dp, end = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
