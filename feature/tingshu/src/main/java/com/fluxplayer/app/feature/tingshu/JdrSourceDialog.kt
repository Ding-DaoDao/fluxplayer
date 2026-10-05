package com.fluxplayer.app.feature.tingshu

import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.fluxplayer.app.core.tingshu.JdrConfiguration
import com.fluxplayer.app.core.tingshu.JdrFolder
import com.fluxplayer.app.core.tingshu.ListeningSource
import com.fluxplayer.app.core.tingshu.TingshuRepository
import com.fluxplayer.app.core.ui.theme.FluxTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import voice.core.extension.engine.SourceLogin

@Composable
internal fun JdrSourceDialog(source: ListeningSource, repository: TingshuRepository, onDismiss: () -> Unit) {
    var configuration by remember(source.id) { mutableStateOf<JdrConfiguration?>(null) }
    var values by remember(source.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var login by remember(source.id) { mutableStateOf(SourceLogin()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var directoryKey by remember { mutableStateOf<String?>(null) }
    var showWeb by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun runAction(block: suspend () -> Unit) {
        busy = true
        error = null
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "书源操作失败"
        } finally {
            busy = false
        }
    }
    suspend fun requestLogin(action: String, cookies: String = "") {
        val next = repository.jdrLogin(source.id, action, login.state, cookies)
        login = if (action == "poll" && next.authenticated != true) {
            next.copy(
                qrImage = next.qrImage.ifBlank { login.qrImage },
                state = next.state.takeIf { it.isNotEmpty() } ?: login.state,
            )
        } else {
            next
        }
        message = login.message
    }
    fun authenticate(action: String, cookies: String = "") {
        scope.launch {
            runAction {
                if (action == "login") repository.saveJdrConfiguration(source.id, values)
                requestLogin(action, cookies)
            }
        }
    }
    LaunchedEffect(source.id) {
        runAction {
            configuration = repository.jdrConfiguration(source.id)
            values = configuration!!.values
            if (configuration!!.canLogin) login = repository.jdrLogin(source.id, "status")
        }
    }
    LaunchedEffect(login.qrImage, login.authenticated) {
        if (login.qrImage.isBlank() || login.authenticated == true) return@LaunchedEffect
        repeat(40) {
            delay(3000)
            if (!busy) {
                runAction { requestLogin("poll") }
                if (login.authenticated == true || error != null) return@LaunchedEffect
            }
        }
        message = "二维码等待已结束，请重新登录"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(source.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = FluxTheme.colorScheme.error) }
                (message ?: login.message).takeIf { !it.isNullOrBlank() }?.let { Text(it) }
                configuration?.let { config ->
                    if (!config.canLogin) Text("可手动填写登录凭证；登录方式由书源提供")
                    config.fields.forEach { field ->
                        val value = values[field.key].orEmpty()
                        when (field.type) {
                            "button" -> TextButton(onClick = {
                                scope.launch {
                                    runAction {
                                        message = repository.jdrConfigAction(source.id, field.action.ifBlank { field.key }, values)
                                        configuration = repository.jdrConfiguration(source.id)
                                        values = configuration!!.values
                                        if (configuration!!.canLogin) login = repository.jdrLogin(source.id, "status")
                                    }
                                }
                            }, enabled = !busy) { Text(field.label) }
                            "multiselect" -> {
                                Text(field.label)
                                field.options.forEach { option ->
                                    Row {
                                        Checkbox(option in value.split(','), { checked ->
                                            val selected = value.split(',').filter { it.isNotBlank() }.toMutableSet()
                                            if (checked) selected.add(option) else selected.remove(option)
                                            values = values + (field.key to selected.joinToString(","))
                                        }, enabled = !busy)
                                        Text(option, Modifier.padding(top = 12.dp))
                                    }
                                }
                            }
                            "switch" -> Row {
                                Text(field.label, Modifier.weight(1f).padding(top = 12.dp))
                                Switch(value == "true", { values = values + (field.key to it.toString()) }, enabled = !busy)
                            }
                            "select" -> {
                                Text(field.label)
                                field.options.forEach { option -> TextButton(onClick = { values = values + (field.key to option) }, enabled = !busy) { Text(if (value == option) "✓ $option" else option) } }
                            }
                            else -> {
                                OutlinedTextField(
                                    value,
                                    { values = values + (field.key to it) },
                                    label = { Text(field.label) },
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                    visualTransformation = if (field.type == "password" || listOf("password", "token", "cookie").any { field.key.contains(it, true) }) PasswordVisualTransformation() else VisualTransformation.None,
                                )
                                if (field.type == "directory") {
                                    TextButton(onClick = {
                                        scope.launch {
                                            runAction {
                                                repository.saveJdrConfiguration(source.id, values)
                                                directoryKey = field.key
                                            }
                                        }
                                    }, enabled = !busy) { Text("选择网盘目录") }
                                }
                            }
                        }
                    }
                    TextButton(onClick = {
                        scope.launch {
                            runAction {
                                repository.clearJdrMetadataCache(source.id)
                                message = "书源临时缓存已清除，登录状态保留"
                            }
                        }
                    }, enabled = !busy) { Text("清除书源临时缓存") }
                    if (config.canLogin) {
                        Text(
                            when (login.authenticated) {
                                true -> "已登录"
                                false -> "未登录"
                                null -> "等待登录"
                            },
                        )
                        Row {
                            TextButton(onClick = { authenticate("login") }, enabled = !busy) { Text("登录") }
                            TextButton(onClick = { authenticate("logout") }, enabled = !busy) { Text("退出登录") }
                        }
                        if (login.qrImage.isNotBlank() && login.authenticated != true) {
                            val model = remember(login.qrImage) {
                                if (login.qrImage.startsWith("data:image/")) runCatching { Base64.decode(login.qrImage.substringAfter(','), Base64.DEFAULT) }.getOrNull() else login.qrImage
                            }
                            AsyncImage(model, "登录二维码", modifier = Modifier.fillMaxWidth().height(220.dp))
                            Text("使用网盘客户端扫码，登录状态会自动检查")
                            TextButton(onClick = { authenticate("poll") }, enabled = !busy) { Text("检查登录状态") }
                        }
                        if (login.webUrl.isNotBlank() && login.authenticated != true) TextButton(onClick = { showWeb = true }, enabled = !busy) { Text("打开网页登录") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    runAction {
                        repository.saveJdrConfiguration(source.id, values)
                        message = "配置已保存"
                    }
                }
            }, enabled = !busy && configuration != null) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
    directoryKey?.let { key ->
        JdrDirectoryDialog(source.id, repository, configuration?.initialDirectory ?: "0", onDismiss = { directoryKey = null }, onSelect = { folder ->
            values = values + (key to folder.id)
            directoryKey = null
            scope.launch {
                runAction {
                    repository.saveJdrConfiguration(source.id, values)
                    message = "听书目录：${folder.name}"
                }
            }
        })
    }
    if (showWeb) {
        JdrWebLoginDialog(login, onDismiss = { showWeb = false }, onComplete = { cookies ->
            showWeb = false
            authenticate("webComplete", cookies)
        })
    }
}

@Composable
private fun JdrDirectoryDialog(sourceId: String, repository: TingshuRepository, initialDirectory: String, onDismiss: () -> Unit, onSelect: (JdrFolder) -> Unit) {
    var path by remember { mutableStateOf(listOf(JdrFolder(initialDirectory, "网盘根目录"))) }
    var folders by remember { mutableStateOf<List<JdrFolder>>(emptyList()) }
    var current by remember { mutableStateOf<JdrFolder?>(null) }
    var nextPage by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun load(page: Int = 1) {
        busy = true
        error = null
        try {
            val data = repository.browseJdr(sourceId, path.last().id.takeIf { it.isNotBlank() }, page)
            current = JdrFolder(data.directoryId, path.last().name)
            folders = if (page == 1) data.folders else (folders + data.folders).distinctBy { it.id }
            nextPage = data.nextPage
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            current = null
            error = e.message
        } finally {
            busy = false
        }
    }
    LaunchedEffect(path) {
        folders = emptyList()
        load()
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择听书目录") }, text = {
        Column(Modifier.height(360.dp).verticalScroll(rememberScrollState())) {
            Text(path.joinToString(" / ") { it.name })
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = FluxTheme.colorScheme.error) }
            if (path.size > 1) TextButton(onClick = { path = path.dropLast(1) }, enabled = !busy) { Text("上一级") }
            folders.forEach { folder -> TextButton(onClick = { path = path + folder }, enabled = !busy) { Text(folder.name) } }
            nextPage?.let { page -> TextButton(onClick = { scope.launch { load(page) } }, enabled = !busy) { Text("加载更多目录") } }
            if (!busy && folders.isEmpty() && error == null) Text("这个目录没有子目录")
        }
    }, confirmButton = { TextButton(onClick = { current?.let(onSelect) }, enabled = !busy && current != null) { Text("使用当前目录") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun JdrWebLoginDialog(login: SourceLogin, onDismiss: () -> Unit, onComplete: (String) -> Unit) {
    val context = LocalContext.current
    val web = remember(login.webUrl) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest): Boolean = request.url.scheme !in setOf("http", "https")
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            loadUrl(login.webUrl)
        }
    }
    DisposableEffect(web) {
        onDispose {
            web.stopLoading()
            web.destroy()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface {
            Column(Modifier.padding(12.dp)) {
                Row {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = {
                        CookieManager.getInstance().flush()
                        onComplete(CookieManager.getInstance().getCookie(login.cookieUrl).orEmpty())
                    }) { Text("完成登录") }
                }
                AndroidView(factory = { web }, modifier = Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}
