package com.fluxplayer.app.feature.videopicker.composables

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.data.cloud.CloudCreatedShare
import com.fluxplayer.app.core.data.cloud.CloudProviderFeatures
import com.fluxplayer.app.core.data.cloud.CloudRemoteFile
import com.fluxplayer.app.core.data.cloud.CloudShareOptions
import com.fluxplayer.app.core.data.cloud.CloudShareSession
import com.fluxplayer.app.core.data.cloud.CloudSpace
import com.fluxplayer.app.core.data.cloud.CloudUploadSource
import com.fluxplayer.app.core.data.cloud.accountUsage
import com.fluxplayer.app.core.data.cloud.listSpace
import com.fluxplayer.app.core.data.cloud.listSpaces
import com.fluxplayer.app.core.data.cloud.resolveSpaceFile
import com.fluxplayer.app.core.data.cloud.spaceFileUri
import com.fluxplayer.app.core.data.quark.UcTvService
import com.fluxplayer.app.core.model.WebDavResource
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CloudFeaturesDialog(
    features: CloudProviderFeatures,
    parentId: String,
    currentItems: List<WebDavResource>,
    onRefresh: () -> Unit,
    onPlay: (Uri, String?) -> Unit,
    onDismiss: () -> Unit,
    isLoggedIn: Boolean = true,
    action: CloudFeatureAction = if (isLoggedIn) CloudFeatureAction.Create else CloudFeatureAction.Import,
) {
    if (action == CloudFeatureAction.Search) {
        CloudSearchDialog(features, onPlay, onDismiss)
        return
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tvService = remember(context) { UcTvService(context) }
    var showTvAuthorization by remember { mutableStateOf(false) }
    var tvAuthorized by remember { mutableStateOf(tvService.isAuthorized()) }
    val playbackPreferences = remember(context) { context.getSharedPreferences("quark", Context.MODE_PRIVATE) }
    var preferOriginal by remember { mutableStateOf(playbackPreferences.getBoolean("prefer_original_playback", false)) }
    var mode by remember { mutableStateOf(if (action == CloudFeatureAction.Create) "create" else if (action == CloudFeatureAction.Spaces) "spaces" else "import") }
    var showSearch by remember { mutableStateOf(false) }
    var configuringShare by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf<com.fluxplayer.app.core.data.cloud.CloudAccountUsage?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var title by remember { mutableStateOf("分享文件") }
    var password by remember { mutableStateOf("") }
    var sharePassword by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf<Int?>(null) }
    var createdShare by remember { mutableStateOf<CloudCreatedShare?>(null) }
    var shareLink by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<CloudShareSession?>(null) }
    var remoteFiles by remember { mutableStateOf(emptyList<CloudRemoteFile>()) }
    var path by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var spaces by remember { mutableStateOf(emptyList<CloudSpace>()) }
    var space by remember { mutableStateOf<CloudSpace?>(null) }
    var target by remember { mutableStateOf(parentId) }
    var targetLabel by remember { mutableStateOf("当前目录") }
    var choosingTarget by remember { mutableStateOf(false) }
    var targetPath by remember { mutableStateOf(listOf(features.rootId to "根目录")) }
    var folders by remember { mutableStateOf(emptyList<CloudRemoteFile>()) }

    fun runOperation(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = ""
        job = scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = error.message ?: "操作失败，请重试"
            } finally {
                busy = false
                progress = -1f
            }
        }
    }

    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            runOperation {
                var completed = 0
                for (uri in uris) {
                    currentCoroutineContext().ensureActive()
                    message = "准备上传 ${completed + 1}/${uris.size}"
                    val upload = withContext(Dispatchers.IO) { prepareUpload(context, uri) }
                    try {
                        var lastProgressAt = 0L
                        val source = CloudUploadSource(upload.first, upload.second, context.contentResolver.getType(uri) ?: "application/octet-stream") { sent, total ->
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (sent == total || now - lastProgressAt >= 150) {
                                progress = if (total > 0) sent.toFloat() / total else 0f
                                lastProgressAt = now
                            }
                        }
                        message = "正在上传：${upload.second}（${completed + 1}/${uris.size}）"
                        features.upload(parentId, source)
                        completed++
                    } finally {
                        withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { upload.first.delete() }
                        onRefresh()
                    }
                }
                message = "已完成 $completed 个文件上传"
            }
        }
    }

    fun browse(folder: Pair<String, String>) {
        runOperation {
            val files = if (mode == "import") features.listShare(checkNotNull(session), folder.first) else features.listSpace(checkNotNull(space), folder.first)
            remoteFiles = files
            selected = emptySet()
            path = path + folder
        }
    }

    val personalFiles = remember(currentItems) { currentItems.map { CloudRemoteFile(it.path, it.name, it.isDirectory, it.size) } }
    val shownFiles = if (mode == "create") personalFiles else remoteFiles

    fun download(url: String, file: CloudRemoteFile) {
        val host = Uri.parse(url).host.orEmpty()
        val referer = when (features.provider) {
            "alipan" -> "https://www.alipan.com/"
            "cloud189" -> "https://cloud.189.cn/"
            "yun139" -> "https://yun.139.com/"
            "uc" -> "https://drive.uc.cn/"
            "quark" -> "https://pan.quark.cn/"
            else -> "https://yun.123pan.cn/"
        }
        val headers = mapOf("Referer" to referer, "User-Agent" to "Mozilla/5.0") +
            com.fluxplayer.app.core.common.CloudPlayHeaders.getHeaders(host).filterKeys { !it.equals("Authorization", true) }
        val name = file.name.replace('/', '_').replace('\\', '_').ifBlank { "下载文件" }
        com.fluxplayer.app.core.common.CloudDownloadHelper(context).downloadFile(url, name, headers = headers)
        message = "已加入系统下载：$name"
    }

    LaunchedEffect(action) {
        when (action) {
            CloudFeatureAction.Upload -> uploadPicker.launch(arrayOf("*/*"))
            CloudFeatureAction.Spaces -> runOperation { spaces = features.listSpaces() }
            CloudFeatureAction.Account -> runOperation { usage = features.accountUsage() }
            else -> Unit
        }
    }

    fun stopOrClose() {
        if (busy) {
            job?.cancel()
            message = "已停止，请刷新网盘确认已完成的操作"
        } else {
            onDismiss()
        }
    }

    @Composable
    fun status() {
        if (busy) {
            if (progress < 0) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        if (message.isNotBlank()) Text(message, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
    }

    when {
        action == CloudFeatureAction.Upload -> CloudFeatureForm(
            title = "上传文件",
            onDismiss = ::stopOrClose,
            confirmLabel = if (busy) "停止上传" else "完成",
            onConfirm = ::stopOrClose,
        ) {
            Text("上传至当前网盘目录", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            status()
            if (!busy) TextButton(onClick = { uploadPicker.launch(arrayOf("*/*")) }) { Text("选择文件") }
        }
        action == CloudFeatureAction.Account -> CloudFeatureForm(
            title = "账号容量",
            onDismiss = ::stopOrClose,
            confirmLabel = "完成",
            onConfirm = ::stopOrClose,
        ) {
            usage?.let { account ->
                Text(account.name, style = MaterialTheme.typography.titleMedium)
                Text(android.text.format.Formatter.formatFileSize(context, account.usedBytes), style = MaterialTheme.typography.headlineMedium)
                Text("已使用 · 共 ${android.text.format.Formatter.formatFileSize(context, account.totalBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (account.totalBytes > 0) LinearProgressIndicator(progress = { (account.usedBytes.toFloat() / account.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            status()
        }
        action == CloudFeatureAction.Playback -> CloudFeatureForm(
            title = "播放设置",
            onDismiss = onDismiss,
            confirmLabel = "完成",
            onConfirm = onDismiss,
        ) {
            if (features.provider == "quark") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("优先原画", style = MaterialTheme.typography.titleSmall)
                        Text("播放时优先使用快传原画链接", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(preferOriginal, {
                        preferOriginal = it
                        playbackPreferences.edit().putBoolean("prefer_original_playback", it).apply()
                        com.fluxplayer.app.core.common.CloudPlaylistCache.clear()
                    })
                }
            } else {
                Text(if (tvAuthorized) "TV 播放已授权" else "授权 TV 播放", style = MaterialTheme.typography.titleMedium)
                Text("授权后优先使用最高可用画质。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { showTvAuthorization = true }) { Text(if (tvAuthorized) "重新授权" else "扫码授权") }
                if (tvAuthorized) {
                    TextButton(onClick = {
                        tvService.clearAuthorization()
                        tvAuthorized = false
                    }) { Text("撤销授权") }
                }
            }
        }
        mode == "import" && session == null -> CloudFeatureForm(
            title = "打开分享链接",
            onDismiss = ::stopOrClose,
            dismissLabel = if (busy) "停止" else "取消",
            confirmLabel = if (busy) "正在打开…" else "打开分享",
            confirmEnabled = !busy && shareLink.isNotBlank(),
            onConfirm = {
                runOperation {
                    val opened = features.openShare(shareLink, sharePassword)
                    val files = features.listShare(opened, opened.rootId)
                    selected = emptySet()
                    session = opened
                    path = listOf(opened.rootId to "分享根目录")
                    remoteFiles = files
                }
            },
        ) {
            Text("粘贴分享链接，浏览其中的文件", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(shareLink, { shareLink = it }, label = { Text("分享链接") }, modifier = Modifier.fillMaxWidth(), enabled = !busy, minLines = 2, maxLines = 4)
            OutlinedTextField(sharePassword, { sharePassword = it }, label = { Text("提取码（选填）") }, modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true)
            status()
        }
        else -> CloudFeatureBrowser(
            title = if (mode == "create") "选择分享文件" else if (mode == "import") "分享文件" else if (features.provider == "alipan") "相册" else "家庭空间",
            subtitle = if (mode == "create") "当前目录" else path.lastOrNull()?.second ?: "选择要浏览的空间",
            onBack = {
                if (path.size > 1) {
                    val previous = path.dropLast(1)
                    runOperation {
                        remoteFiles = if (mode == "import") features.listShare(checkNotNull(session), previous.last().first) else features.listSpace(checkNotNull(space), previous.last().first)
                        path = previous
                        selected = emptySet()
                    }
                } else if (mode == "spaces" && space != null) {
                    space = null
                    path = emptyList()
                    remoteFiles = emptyList()
                } else if (mode == "import") {
                    session = null
                    message = ""
                } else {
                    onDismiss()
                }
            },
            onClose = ::stopOrClose,
            toolbarActions = {
                if (mode == "import" || mode == "spaces" && space != null) TextButton(enabled = !busy, onClick = { showSearch = true }) { Text("搜索") }
            },
            busy = busy,
            bottomBar = {
                if (mode == "create" || (mode == "import" && isLoggedIn)) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (mode == "import") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("保存到", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(targetLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                TextButton(enabled = !busy, onClick = {
                                    choosingTarget = true
                                    runOperation {
                                        folders = features.listFolders(features.rootId)
                                        targetPath = listOf(features.rootId to "根目录")
                                    }
                                }) { Text("更改") }
                            }
                        }
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy && selected.isNotEmpty(),
                            onClick = {
                                if (mode == "create") {
                                    configuringShare = true
                                } else {
                                    runOperation {
                                        features.saveShare(checkNotNull(session), remoteFiles.filter { it.id in selected }, target)
                                        selected = emptySet()
                                        message = "转存完成"
                                        onRefresh()
                                    }
                                }
                            },
                        ) { Text(if (mode == "create") "下一步（${selected.size}）" else "转存所选（${selected.size}）") }
                    }
                }
            },
        ) {
            if (path.size > 1) Text(path.joinToString(" / ") { it.second }, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (mode != "spaces") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("已选择 ${selected.size} 项", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(enabled = !busy && shownFiles.isNotEmpty(), onClick = { selected = if (selected.size == shownFiles.size) emptySet() else shownFiles.map { it.id }.toSet() }) { Text(if (selected.size == shownFiles.size && shownFiles.isNotEmpty()) "取消全选" else "全选") }
                }
            }
            if (message.isNotBlank()) Text(message, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
            val displayingSpaces = mode == "spaces" && space == null
            if (!busy && (if (displayingSpaces) spaces.isEmpty() else shownFiles.isEmpty())) {
                CloudFeatureEmptyState(if (displayingSpaces) "暂无可用空间" else "此目录暂无文件", Modifier.weight(1f))
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    if (displayingSpaces) {
                        items(spaces, key = { it.id }) { candidate ->
                            CloudFeatureFileRow(CloudRemoteFile(candidate.id, candidate.name, true), enabled = !busy, onOpen = {
                                space = candidate
                                browse((if (features.provider == "alipan") "root" else "") to candidate.name)
                            })
                        }
                    } else {
                        items(shownFiles, key = { it.id }) { file ->
                            val media = WebDavResource(file.name, file.id, false).let { it.isVideo || it.isAudio }
                            val actions = mutableListOf<Pair<String, () -> Unit>>()
                            if (!file.directory && mode == "spaces") {
                                if (media) {
                                    actions += "播放" to {
                                        runOperation {
                                            features.resolveSpaceFile(checkNotNull(space), file)
                                            onPlay(features.spaceFileUri(checkNotNull(space), file), file.name)
                                        }
                                    }
                                }
                                actions += "下载" to { runOperation { download(features.resolveSpaceFile(checkNotNull(space), file), file) } }
                            }
                            if (!file.directory && mode == "import") {
                                if (isLoggedIn) {
                                    actions += (if (media) "转存并播放" else "转存并下载") to {
                                        runOperation {
                                            val (id, url) = features.transferAndResolve(checkNotNull(session), file, target, forPlayback = media)
                                            onRefresh()
                                            if (media) {
                                                val uri = Uri.Builder().scheme("cloud").authority(features.provider).appendPath(id).appendQueryParameter("extra_parent", target).appendQueryParameter("file_name", file.name).build()
                                                onPlay(uri, file.name)
                                            } else {
                                                download(url, file)
                                            }
                                        }
                                    }
                                }
                            }
                            CloudFeatureFileRow(
                                file,
                                enabled = !busy,
                                selected = if (mode == "create" || (mode == "import" && isLoggedIn)) file.id in selected else null,
                                onSelect = { selected = if (file.id in selected) selected - file.id else selected + file.id },
                                onOpen = if (file.directory && mode != "create") ({ browse(file.id to file.name) }) else null,
                                actions = actions,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSearch) CloudSearchDialog(
        features, onPlay, onDismiss = { showSearch = false },
        rootId = if (mode == "import") checkNotNull(session).rootId else if (features.provider == "alipan") "root" else "",
        rootLabel = if (mode == "import") "分享" else checkNotNull(space).name,
        readDirectory = { directory -> if (mode == "import") features.listShare(checkNotNull(session), directory) else features.listSpace(checkNotNull(space), directory) },
        canAccessFiles = mode != "import" || isLoggedIn,
        playLabel = if (mode == "import") "转存并播放" else "播放",
        downloadLabel = if (mode == "import") "转存并下载" else "下载",
        resolvePlay = { file ->
            if (mode == "import") {
                val (id, _) = features.transferAndResolve(checkNotNull(session), file, target)
                onRefresh()
                Uri.Builder().scheme("cloud").authority(features.provider).appendPath(id).appendQueryParameter("extra_parent", target).appendQueryParameter("file_name", file.name).build()
            } else {
                features.resolveSpaceFile(checkNotNull(space), file)
                features.spaceFileUri(checkNotNull(space), file)
            }
        },
        resolveDownload = { file ->
            if (mode == "import") features.transferAndResolve(checkNotNull(session), file, target, forPlayback = false).second.also { onRefresh() } else features.resolveSpaceFile(checkNotNull(space), file)
        },
    )

    if (configuringShare) {
        val share = createdShare
        CloudFeatureForm(
            title = if (share == null) "创建分享" else "分享已创建",
            onDismiss = {
                if (busy) {
                    job?.cancel()
                } else {
                    configuringShare = false
                    createdShare = null
                }
            },
            dismissLabel = if (busy) "停止" else "取消",
            confirmLabel = if (share == null) (if (busy) "正在创建…" else "创建分享") else "复制分享链接",
            confirmEnabled = !busy && (share != null || title.isNotBlank()),
            onConfirm = {
                if (share == null) {
                    runOperation { createdShare = features.createShare(personalFiles.filter { it.id in selected }, CloudShareOptions(title, expiry, password)) }
                } else {
                    val text = share.url + if (share.password.isNotBlank()) " 提取码：${share.password}" else ""
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("网盘分享", text))
                    message = "分享链接已复制"
                    configuringShare = false
                    createdShare = null
                }
            },
        ) {
            if (share == null) {
                Text("已选择 ${selected.size} 个文件或文件夹", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(title, { title = it }, label = { Text("分享名称") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("有效期", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val choices = if (features.provider == "cloud189") listOf(null, 1, 7) else listOf(null, 1, 7, 30)
                    choices.forEach { days -> FilterChip(selected = expiry == days, enabled = !busy, onClick = { expiry = days }, label = { Text(days?.let { "$it 天" } ?: "永久") }) }
                }
                if (features.provider !in setOf("yun139", "cloud189")) OutlinedTextField(password, { password = it }, label = { Text("提取码（选填）") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth()) else Text("提取码由网盘自动生成", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SelectionContainer { Text(share.url, style = MaterialTheme.typography.bodyLarge) }
                        if (share.password.isNotBlank()) Text("提取码  ${share.password}", style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            status()
        }
    }
    if (choosingTarget) {
        CloudFeatureBrowser(
            title = "选择转存目录",
            subtitle = targetPath.last().second,
            busy = busy,
            onBack = {
                if (targetPath.size > 1) {
                    val previous = targetPath.dropLast(1)
                    runOperation {
                        folders = features.listFolders(previous.last().first)
                        targetPath = previous
                    }
                } else {
                    choosingTarget = false
                }
            },
            onClose = { if (!busy) choosingTarget = false },
            bottomBar = {
                Button(enabled = !busy, modifier = Modifier.fillMaxWidth().padding(20.dp), onClick = {
                    target = targetPath.last().first
                    targetLabel = targetPath.joinToString(" / ") { it.second }
                    choosingTarget = false
                }) { Text("保存到此目录") }
            },
        ) {
            Text(targetPath.joinToString(" / ") { it.second }, Modifier.padding(20.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (message.isNotBlank()) Text(message, Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.error)
            if (folders.isEmpty() && !busy) {
                CloudFeatureEmptyState("此目录没有子文件夹", Modifier.weight(1f))
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(folders, key = { it.id }) { folder ->
                        CloudFeatureFileRow(folder, enabled = !busy, onOpen = {
                            runOperation {
                                folders = features.listFolders(folder.id)
                                targetPath = targetPath + (folder.id to folder.name)
                            }
                        })
                    }
                }
            }
        }
    }
    if (showTvAuthorization) {
        UcTvAuthorizationDialog(tvService) {
            showTvAuthorization = false
            tvAuthorized = tvService.isAuthorized()
        }
    }
}

private suspend fun prepareUpload(context: Context, uri: Uri): Pair<File, String> {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "上传文件"
    val file = File.createTempFile("cloud-upload-", ".tmp", context.cacheDir)
    try {
        val coroutine = currentCoroutineContext()
        resolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutine.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        } ?: error("无法读取所选文件")
        return file to name
    } catch (error: Throwable) {
        file.delete()
        throw error
    }
}
