package com.fluxplayer.app.feature.tingshu

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import com.fluxplayer.app.core.tingshu.JdrConfiguration
import com.fluxplayer.app.core.tingshu.JdrFolder
import com.fluxplayer.app.core.tingshu.ListeningErrors
import com.fluxplayer.app.core.tingshu.ListeningSource
import com.fluxplayer.app.core.tingshu.SourceHost
import com.fluxplayer.app.core.tingshu.TingshuRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var webLogin by remember { mutableStateOf<SourceLogin?>(null) }
    suspend fun runAction(block: suspend () -> Unit) {
        busy = true
        error = null
        try {
            block()
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            error = ListeningErrors.describe(e, "书源操作失败")
        } finally {
            busy = false
        }
    }
    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val started = webLogin
        webLogin = null
        if (started != null) {
            scope.launch {
                runAction {
                    val cookies = SourceHost.cookie(started.cookieUrl.ifBlank { started.webUrl }).orEmpty()
                    if (cookies.isNotBlank()) {
                        login = repository.jdrLogin(source.id, "webComplete", started.state, cookies)
                        message = login.message
                    } else {
                        login = repository.jdrLogin(source.id, "status")
                        message = "未获取到登录凭证，请先在网页中完成登录"
                    }
                    configuration = repository.jdrConfiguration(source.id)
                    values = configuration!!.values
                }
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
    SourceSettingsSheet(
        title = source.name,
        busy = busy,
        onDismiss = onDismiss,
        canSave = configuration != null,
        onSave = {
            scope.launch {
                runAction {
                    repository.saveJdrConfiguration(source.id, values)
                    configuration = repository.jdrConfiguration(source.id)
                    values = configuration!!.values
                    if (configuration!!.canLogin) login = repository.jdrLogin(source.id, "status")
                    message = "配置已保存"
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            error?.let { SourceErrorNotice(it, onDismiss = { error = null }) }
            SourceStatusCard(
                when (login.authenticated) {
                    true -> "已登录"
                    false -> "尚未登录"
                    null -> "书源偏好"
                },
                message ?: login.message,
            )
            configuration?.let { config ->
                config.fields.forEach { field ->
                    val value = values[field.key].orEmpty()
                    when (field.type) {
                        "button" -> OutlinedButton(onClick = {
                            scope.launch {
                                runAction {
                                    message = repository.jdrConfigAction(source.id, field.action.ifBlank { field.key }, values)
                                    configuration = repository.jdrConfiguration(source.id)
                                    values = configuration!!.values
                                    if (configuration!!.canLogin) login = repository.jdrLogin(source.id, "status")
                                }
                            }
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(field.label) }
                        "multiselect" -> SourceConfigOptions(field.label, field.options, value.split(',').toSet(), !busy) { option ->
                            val selected = value.split(',').filter { it.isNotBlank() }.toMutableSet()
                            if (!selected.remove(option)) selected.add(option)
                            values = values + (field.key to selected.joinToString(","))
                        }
                        "switch" -> SourceConfigSwitch(field.label, value == "true", !busy) { values = values + (field.key to it.toString()) }
                        "select" -> SourceConfigOptions(field.label, field.options, setOf(value), !busy) { values = values + (field.key to it) }
                        else -> {
                            SourceConfigField(
                                label = field.label,
                                value = value,
                                onValueChange = { values = values + (field.key to it) },
                                enabled = !busy,
                                secret = field.type == "password" || listOf("password", "token", "cookie", "authorization").any { field.key.contains(it, true) },
                                hint = field.hint,
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
                if (login.webUrl.isNotBlank()) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            runAction {
                                repository.saveJdrConfiguration(source.id, values)
                                webLogin = login
                                loginLauncher.launch(SourceLoginActivity.intent(context, source.name, login.webUrl, if (login.desktopUserAgent) SourceHost.DESKTOP_UA else SourceHost.MOBILE_UA))
                            }
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("打开网页登录") }
                }
                if (config.canLogin) {
                    TextButton(onClick = {
                        scope.launch {
                            runAction {
                                login = repository.jdrLogin(source.id, "logout", login.state)
                                configuration = repository.jdrConfiguration(source.id)
                                values = configuration!!.values
                                message = login.message
                            }
                        }
                    }, enabled = !busy) { Text("退出登录") }
                }
            }
        }
    }
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
    var retryPage by remember { mutableStateOf(1) }
    suspend fun load(page: Int = 1) {
        retryPage = page
        busy = true
        error = null
        try {
            val data = repository.browseJdr(sourceId, path.last().id.takeIf { it.isNotBlank() }, page)
            current = JdrFolder(data.directoryId, path.last().name)
            folders = if (page == 1) data.folders else (folders + data.folders).distinctBy { it.id }
            nextPage = data.nextPage
        } catch (e: Exception) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            current = null
            error = ListeningErrors.describe(e, "目录加载失败")
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
            error?.let { SourceErrorNotice(it, onRetry = { scope.launch { load(retryPage) } }) }
            if (path.size > 1) TextButton(onClick = { path = path.dropLast(1) }, enabled = !busy) { Text("上一级") }
            folders.forEach { folder -> TextButton(onClick = { path = path + folder }, enabled = !busy) { Text(folder.name) } }
            nextPage?.let { page -> TextButton(onClick = { scope.launch { load(page) } }, enabled = !busy) { Text("加载更多目录") } }
            if (!busy && folders.isEmpty() && error == null) Text("这个目录没有子目录")
        }
    }, confirmButton = { TextButton(onClick = { current?.let(onSelect) }, enabled = !busy && current != null) { Text("使用当前目录") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
