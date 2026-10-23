package com.fluxplayer.app.feature.videopicker.composables

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.data.cloud.CloudProviderFeatures
import com.fluxplayer.app.core.data.cloud.CloudRemoteFile
import com.fluxplayer.app.core.data.cloud.manageBatch
import com.fluxplayer.app.core.model.WebDavResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 选择状态与目录、账号绑定，普通列表和批量操作共用同一份选择。 */
class CloudBatchSelection internal constructor(
    val features: CloudProviderFeatures,
    val parentId: String,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onRefresh: () -> Unit,
    private val completeListing: () -> List<WebDavResource>? = { null },
) {
    var selected by mutableStateOf(emptyMap<String, CloudRemoteFile>())
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var progress by mutableStateOf(0 to 0)
        private set
    var selectingAll by mutableStateOf(false)
        private set
    private var allIds by mutableStateOf<Set<String>?>(null)
    val allSelected: Boolean get() = allIds?.let { it.isNotEmpty() && it == selected.keys } == true
    private var job: kotlinx.coroutines.Job? = null

    fun toggle(item: WebDavResource) {
        if (busy) return
        selected = if (item.path in selected) selected - item.path else selected + (item.path to CloudRemoteFile(item.path, item.name, item.isDirectory, item.size, parentId = parentId))
    }

    fun clear() {
        if (!busy) selected = emptyMap()
    }

    /** 已加载完整目录时直接复用列表，只有分页未读完才请求全部条目。 */
    fun toggleAll() {
        if (busy) return
        if (allSelected) {
            clear()
            return
        }
        val cached = completeListing()
        if (cached != null) {
            selected = cached.associate { it.path to CloudRemoteFile(it.path, it.name, it.isDirectory, it.size, parentId = parentId) }
            allIds = selected.keys.toSet()
            return
        }
        busy = true
        selectingAll = true
        message = null
        val owner = features.searchSessionKey()
        job = scope.launch {
            try {
                val files = features.listPersonalFiles(parentId)
                check(owner == features.searchSessionKey()) { "账号已变化，请重新打开目录" }
                selected = files.associateBy { it.id }
                allIds = selected.keys.toSet()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = error.message ?: "读取完整目录失败，请重试全选"
            } finally {
                busy = false
                selectingAll = false
            }
        }
    }

    fun runBatch(target: String?) {
        if (busy || selected.isEmpty()) return
        val snapshot = selected.values.toList()
        busy = true
        message = null
        progress = 0 to snapshot.size
        job = scope.launch {
            try {
                val result = features.manageBatch(parentId, snapshot, target) { done, total -> progress = done to total }
                selected = selected - result.completedIds
                allIds = null
                message = if (result.message == null) {
                    "已${if (target == null) "删除" else "移动"} ${result.completedIds.size} 项"
                } else {
                    "已确认完成 ${result.completedIds.size}/${snapshot.size} 项。${result.message}"
                }
                val current = features.listPersonalFiles(parentId).map { it.id }.toSet()
                selected = selected.filterKeys { it in current }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = error.message ?: "操作未完成，请刷新目录确认"
            } finally {
                busy = false
                onRefresh()
            }
        }
    }

    internal fun dispose() {
        job?.cancel()
    }
}

@Composable
fun rememberCloudBatchSelection(features: CloudProviderFeatures, parentId: String, loadedFiles: List<WebDavResource>, directoryComplete: Boolean, onRefresh: () -> Unit): CloudBatchSelection {
    val scope = rememberCoroutineScope()
    val refresh by androidx.compose.runtime.rememberUpdatedState(onRefresh)
    val complete by androidx.compose.runtime.rememberUpdatedState(if (directoryComplete) loadedFiles else null)
    val state = remember(features.searchSessionKey(), parentId) { CloudBatchSelection(features, parentId, scope, { refresh() }, { complete }) }
    androidx.compose.runtime.DisposableEffect(state) { onDispose { state.dispose() } }
    BackHandler(enabled = state.busy || state.selected.isNotEmpty()) { state.clear() }
    return state
}

/** 操作栏直接嵌入原目录，不再切换到单独的批量管理页面。 */
@Composable
fun CloudBatchToolbar(state: CloudBatchSelection) {
    var confirmDelete by remember { mutableStateOf(false) }
    var chooseDestination by remember { mutableStateOf(false) }
    if (state.selected.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("已选 ${state.selected.size} 项", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.selected.isNotEmpty()) {
                TextButton(enabled = !state.busy, onClick = state::toggleAll) { Text(if (state.allSelected) "取消全选" else "全选") }
                TextButton(enabled = !state.busy, onClick = { chooseDestination = true }) { Text("移动") }
                TextButton(enabled = !state.busy, onClick = { confirmDelete = true }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                androidx.compose.material3.IconButton(enabled = !state.busy, onClick = state::clear) {
                    androidx.compose.material3.Icon(com.fluxplayer.app.core.ui.designsystem.NextIcons.Close, "取消选择")
                }
            }
        }
    }
    if (state.busy) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(if (state.selectingAll) "正在选择当前目录的全部文件…" else "正在处理并核对结果 ${state.progress.first}/${state.progress.second}", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
    }
    state.message?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${state.selected.size} 项？") },
            text = { Text("${state.selected.values.take(3).joinToString("\n") { it.name }}\n\n所选文件夹及其中的内容也会删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    state.runBatch(null)
                }) { Text("确认删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
    }
    if (chooseDestination) {
        CloudBatchDestinationDialog(
            state.features,
            state.parentId,
            state.selected.values.filter { it.directory }.map { it.id }.toSet(),
            onDismiss = { chooseDestination = false },
            onConfirm = { target ->
                chooseDestination = false
                state.runBatch(target)
            },
        )
    }
}

@Composable
private fun CloudBatchDestinationDialog(
    features: CloudProviderFeatures,
    source: String,
    excludedFolders: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var path by remember { mutableStateOf(listOf(features.rootId to "根目录")) }
    var folders by remember { mutableStateOf(emptyList<CloudRemoteFile>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val current = path.last()
    fun navigate(next: List<Pair<String, String>>) {
        loading = true
        error = null
        path = next
    }
    LaunchedEffect(current.first, revision) {
        loading = true
        error = null
        folders = emptyList()
        try {
            folders = features.listFolders(current.first).filter { it.id !in excludedFolders }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message ?: "目标目录读取失败"
        } finally {
            loading = false
        }
    }
    BackHandler { if (path.size > 1) navigate(path.dropLast(1)) else onDismiss() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动到：${current.second}") },
        text = {
            Column(Modifier.fillMaxWidth().height(360.dp)) {
                if (path.size > 1) TextButton(onClick = { navigate(path.dropLast(1)) }) { Text("返回上一级") }
                Text("所选文件夹及其子目录不可作为目标", style = MaterialTheme.typography.bodySmall)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                error?.let {
                    Text(it)
                    TextButton(onClick = { revision++ }) { Text("重试") }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(folders, key = { it.id }) { folder ->
                        ListItem(
                            headlineContent = { Text(folder.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            leadingContent = { FileTypeIcon(WebDavResource(folder.name, folder.id, true), Modifier.size(32.dp)) },
                            modifier = Modifier.clickable { navigate(path + (folder.id to folder.name)) },
                        )
                    }
                }
                if (!loading && error == null && folders.isEmpty()) Text("此目录没有可选的子文件夹")
            }
        },
        confirmButton = { Button(enabled = !loading && error == null && current.first != source, onClick = { onConfirm(current.first) }) { Text("移动到此处") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
