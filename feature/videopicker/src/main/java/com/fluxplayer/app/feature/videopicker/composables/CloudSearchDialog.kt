package com.fluxplayer.app.feature.videopicker.composables

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.data.cloud.CloudDirectorySearchCache
import com.fluxplayer.app.core.data.cloud.CloudProviderFeatures
import com.fluxplayer.app.core.data.cloud.CloudRemoteFile
import com.fluxplayer.app.core.data.cloud.CloudSearchCategory
import com.fluxplayer.app.core.data.cloud.CloudSearchProgress
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
    embedded: Boolean = false,
    queryRequest: String? = null,
    requestId: Int = 0,
    directoryCache: CloudDirectorySearchCache? = null,
    categoryRequest: CloudSearchCategory? = null,
    sessionKeyRequest: String? = null,
    historyScope: String = "personal",
) {
    var query by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(CloudSearchProgress(emptyList(), 0, 0, 0)) }
    var busy by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var folders by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var files by remember { mutableStateOf(emptyList<CloudRemoteFile>()) }
    val sessionKey = sessionKeyRequest ?: features.searchSessionKey()
    val history = rememberSearchHistory("$sessionKey:$historyScope")
    var category by remember(sessionKey) { mutableStateOf(categoryRequest ?: CloudSearchCategory.All) }
    var showIssues by remember { mutableStateOf(false) }
    val cache = directoryCache ?: remember(sessionKey, rootId, rootLabel) { CloudDirectorySearchCache() }
    var operation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    fun run(replace: Boolean = false, isSearch: Boolean = false, block: suspend () -> Unit) {
        if (busy && !replace) return
        if (replace) job?.cancel()
        val generation = ++operation
        busy = true
        searching = isSearch
        message = ""
        job = scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = error.message ?: "操作失败，请重试"
            } finally {
                if (generation == operation) busy = false
            }
        }
    }
    fun search(refresh: Boolean = false) {
        if (query.isBlank() && category == CloudSearchCategory.All) {
            job?.cancel()
            operation++
            busy = false
            searching = false
            progress = CloudSearchProgress(emptyList(), 0, 0, 0)
            message = ""
            return
        }
        if (refresh) cache.clear()
        if (!embedded) history.remember(query)
        keyboard?.hide()
        folders = emptyList()
        progress = CloudSearchProgress(emptyList(), 0, 0, 0)
        val keyword = query
        val selectedCategory = category
        val expectedSession = features.searchSessionKey()
        run(replace = true, isSearch = true) {
            val result = searchCloudTree(keyword, rootId, rootLabel, readDirectory, onProgress = { progress = it }, cache = cache, category = selectedCategory, validate = {
                check(features.searchSessionKey() == expectedSession) { "账号或盘空间已变化，请重新搜索" }
            })
            message = when {
                result.limited -> "已达到搜索上限，显示已找到的结果"
                result.failedFolders > 0 -> "搜索完成，${result.failedFolders} 个目录未能读取"
                else -> "搜索完成，共找到 ${result.hits.size} 项"
            }
        }
    }
    fun browse(file: CloudRemoteFile) = run(replace = true) {
        val listing = cache.get(file.id) ?: readDirectory(file.id).also { cache.put(file.id, it) }
        files = listing
        val trail = if (folders.isEmpty()) progress.hits.firstOrNull { it.file.id == file.id && it.file.parentId == file.parentId }?.trail.orEmpty() else folders
        folders = trail + (file.id to file.name)
    }
    LaunchedEffect(queryRequest, requestId, categoryRequest, rootId, sessionKey) {
        if (queryRequest != null) {
            category = categoryRequest ?: category
            query = queryRequest
            search()
        }
    }
    if (showIssues) {
        AlertDialog(onDismissRequest = { showIssues = false }, title = { Text("未能读取的目录") }, text = {
            LazyColumn {
                items(progress.issues) { issue ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text(issue.path, style = MaterialTheme.typography.bodyMedium)
                        Text(issue.message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showIssues = false }) { Text("关闭") } })
    }
    CloudSearchSurface(
        embedded = embedded,
        browsingFolder = folders.isNotEmpty(),
        title = "搜索网盘", subtitle = folders.lastOrNull()?.second ?: "搜索${rootLabel}中的文件和文件夹", busy = busy,
        onBack = {
            if (folders.size > 1) {
                run {
                    val previous = folders.dropLast(1)
                    files = readDirectory(previous.last().first)
                    folders = previous
                }
            } else if (folders.isNotEmpty()) folders = emptyList() else onDismiss()
        },
        // 忙碌时返回即停止搜索，不放独立的「停止」按钮
        onStop = {
            job?.cancel()
            message = "已停止操作，保留已找到的结果"
        },
        onClose = onDismiss,
        // 加载态由本页自己画（空结果用整页加载，有结果时用行内指示），避免两个动画
        showBusyIndicator = false,
        bottomBar = {},
    ) {
        if (folders.isEmpty()) {
            if (!embedded) {
                OutlinedTextField(
                    query, {
                        query = it
                        if (query.isBlank()) search()
                    }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    label = { Text("文件名关键词") }, singleLine = true, enabled = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
                    trailingIcon = { TextButton(enabled = query.isNotBlank() || category != CloudSearchCategory.All, onClick = { search() }) { Text("搜索") } },
                )
                CloudSearchControls(
                    category,
                    onCategory = {
                        category = it
                        search()
                    },
                    history = history,
                    showHistory = progress.folders == 0 && !busy,
                    onHistory = {
                        query = it
                        search()
                    },
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (busy) MediaLoadingIndicator(Modifier.size(16.dp))
                Text(
                    if (busy && searching) "已搜索 ${progress.folders} 个目录 · 找到 ${progress.hits.size} 项" else if (busy) "正在准备文件" else message.ifBlank { "输入关键词，开始搜索" },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (embedded && busy) {
                    TextButton(onClick = {
                        job?.cancel()
                        message = "已停止操作，保留已找到的结果"
                    }) { Text("停止") }
                }
                if (!busy && (query.isNotBlank() || category != CloudSearchCategory.All)) {
                    TextButton(onClick = { search(refresh = true) }) { Text("重新搜索") }
                }
            }
            if (progress.issues.isNotEmpty()) {
                TextButton(onClick = { showIssues = true }, modifier = Modifier.padding(horizontal = 12.dp)) { Text("查看失败详情（${progress.failedFolders}）") }
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
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(shown, key = { "${it.first.parentId}/${it.first.id}" }) { (file, path) ->
                    Column {
                        CloudFeatureFileRow(
                            file,
                            enabled = !busy || searching,
                            onOpen = if (file.directory) ({ browse(file) }) else null,
                            actions = if (file.directory || !canAccessFiles) {
                                emptyList()
                            } else {
                                buildList {
                                    val media = WebDavResource(file.name, file.id, false).let { it.isAudio || it.isVideo }
                                    add(
                                        downloadLabel to {
                                            run(replace = true) {
                                                val url = resolveDownload?.invoke(file) ?: features.resolvePersonalDownload(file)
                                                com.fluxplayer.app.core.common.CloudDownloadHelper(context).downloadFile(url, file.name.replace('/', '_').replace('\\', '_'), headers = features.fileHeaders())
                                                message = "已加入系统下载：${file.name}"
                                            }
                                        },
                                    )
                                    if (media) {
                                        add(
                                            playLabel to {
                                                run(replace = true) {
                                                    val uri = resolvePlay?.invoke(file) ?: kotlin.run {
                                                        Uri.Builder().scheme("cloud").authority(features.provider).appendQueryParameter("account", features.accountId).appendPath(file.id)
                                                            .appendQueryParameter("extra_parent", file.parentId).appendQueryParameter("file_name", file.name).build()
                                                    }
                                                    onPlay(uri, file.name)
                                                }
                                            },
                                        )
                                    }
                                }
                            },
                        )
                        if (folders.isEmpty()) {
                            Text(
                                path,
                                Modifier
                                    .semantics { contentDescription = "打开所在文件夹：${file.name}" }
                                    .clickable {
                                        run(replace = true) {
                                            val hit = progress.hits.first { it.file.id == file.id && it.file.parentId == file.parentId }
                                            files = cache.get(file.parentId) ?: readDirectory(file.parentId).also { cache.put(file.parentId, it) }
                                            folders = hit.trail.ifEmpty { listOf(file.parentId to path) }
                                        }
                                    }.padding(start = 84.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 根目录内直接显示搜索结果，分享和空间仍复用独立浏览页。 */
@Composable
private fun CloudSearchSurface(
    embedded: Boolean,
    browsingFolder: Boolean,
    title: String,
    subtitle: String,
    busy: Boolean,
    onBack: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    showBusyIndicator: Boolean,
    bottomBar: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (embedded) {
        BackHandler { if (busy) onStop() else onBack() }
        Column(Modifier.fillMaxSize()) {
            if (browsingFolder) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { if (busy) onStop() else onBack() }) { Text("返回") }
                    Text(subtitle, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
            content()
        }
    } else {
        CloudFeatureBrowser(title, subtitle, busy, onBack, onClose, onStop, showBusyIndicator, bottomBar = bottomBar, content = content)
    }
}
