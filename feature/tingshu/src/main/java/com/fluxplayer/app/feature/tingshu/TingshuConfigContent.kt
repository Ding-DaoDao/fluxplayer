package com.fluxplayer.app.feature.tingshu

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fluxplayer.app.core.tingshu.ListeningSource
import com.fluxplayer.app.core.tingshu.SourceHost
import com.fluxplayer.app.core.tingshu.WebLoginWarmUp
import com.fluxplayer.app.core.ui.components.MediaAlertDialog as AlertDialog
import com.fluxplayer.app.core.ui.components.MediaGroupSpacer as HorizontalDivider
import com.fluxplayer.app.core.ui.components.MediaLoadingRow
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.theme.FluxTheme
import com.github.eprendre.tingshu.utils.ConfigItem

@Composable
fun TingshuConfigContent(modifier: Modifier = Modifier, viewModel: TingshuViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sources by viewModel.repository.sources.collectAsStateWithLifecycle()
    val packages by viewModel.repository.packages.collectAsStateWithLifecycle()
    var configuringSource by remember { mutableStateOf<ListeningSource?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var showImportInfo by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importSource(uri)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("我的书源", style = FluxTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("${sources.size} 个可用书源", style = FluxTheme.typography.bodySmall, color = FluxTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = { showImportInfo = true }, enabled = !state.loading, shape = RoundedCornerShape(14.dp)) { Text("导入书源") }
        }
        if (state.loading) MediaLoadingRow(Modifier.fillMaxWidth())
        if (state.configItems == null) state.error?.let { SourceErrorNotice(it, onRetry = viewModel::retry, onDismiss = viewModel::dismissError) }
        if (packages.isEmpty()) {
            ListeningSettingsCard("添加第一个书源", "导入书源后，就能在听书首页浏览和收听。") {
                Text("支持 JDR、JAR 书源文件和 JAR 集合包（.zip）", style = FluxTheme.typography.bodyMedium)
            }
        }
        packages.forEach { pkg ->
            val entries = pkg.sources
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(32.dp), colors = CardDefaults.cardColors(containerColor = FluxTheme.colorScheme.surfaceContainerLow)) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(14.dp), color = FluxTheme.colorScheme.primaryContainer) {
                        Icon(NextIcons.Headset, null, Modifier.padding(12.dp).size(24.dp), tint = FluxTheme.colorScheme.onPrimaryContainer)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(pkg.displayName, style = FluxTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(if (pkg.enabled) "已启用 · ${entries.size} 个书源" else "已停用", style = FluxTheme.typography.bodySmall, color = FluxTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(pkg.enabled, { viewModel.enable(pkg.entry, it) }, enabled = !state.loading)
                }
                pkg.error?.let { Text(it, color = FluxTheme.colorScheme.error, style = FluxTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
                entries.forEach { source ->
                    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = FluxTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ListItem(
                        headlineContent = { Text(source.name, style = FluxTheme.typography.bodyLarge) },
                        supportingContent = { Text(if (pkg.enabled) "账号、目录与偏好" else "已停用，启用源包后可配置", style = FluxTheme.typography.bodySmall) },
                        trailingContent = { if (pkg.enabled) Icon(NextIcons.Settings, "配置", tint = FluxTheme.colorScheme.onSurfaceVariant) },
                        colors = ListItemDefaults.colors(containerColor = FluxTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clickable(enabled = pkg.enabled && pkg.error == null && !state.loading) { configuringSource = source }.padding(horizontal = 4.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { deleting = pkg.entry }, enabled = !state.loading) {
                        Text("移除源包", color = FluxTheme.colorScheme.onSurfaceVariant, style = FluxTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
    if (showImportInfo) {
        AlertDialog(
            onDismissRequest = { showImportInfo = false },
            title = { Text("导入听书书源") },
            text = {
                Text(
                    "支持三种：Timbre 的 .jdr 源包、单个听书 JAR、以及把多个 JAR 打成一个 .zip 的书源集合包。\n\n" +
                        "JAR 需保留原名，例如 sources_by_pan123.jar（集合包里允许带子目录）。重新导入同名包会更新书源，配置和进度保留。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showImportInfo = false
                    picker.launch(arrayOf("*/*"))
                }) { Text("选择文件") }
            },
            dismissButton = { TextButton(onClick = { showImportInfo = false }) { Text("取消") } },
        )
    }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除书源包？") },
            text = { Text("该包内的书源将无法继续解析，听书进度和配置会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.remove(entry)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
    TingshuSourceConfigPanel(
        source = configuringSource,
        viewModel = viewModel,
        onDismiss = { configuringSource = null },
    )
}

/**
 * 弹出指定书源的配置面板。
 *
 * 「设置 → 听书配置」的书源列表与听书首页长按书源共用此入口：JDR 源交给
 * [JdrSourceDialog]，其余源先经 [TingshuViewModel.configure] 载入配置项，再由
 * [SourceConfigDialog] 呈现。
 *
 * @param source 待配置的书源；传 null 表示不展示面板。
 * @param onDismiss 面板关闭时回调，调用方应据此把 [source] 置空。
 */
@Composable
fun TingshuSourceConfigPanel(
    source: ListeningSource?,
    viewModel: TingshuViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 网页登录的 ActivityResult 必须挂在「两处入口都会走到」的组件上：
    // 设置 → 听书配置 与 听书首页长按书源 都渲染这个面板，缺了它点击只会写 pendingLogin 而没人启动页面。
    WebLoginHost(viewModel, source)
    LaunchedEffect(source?.id) {
        val target = source ?: return@LaunchedEffect
        if (!target.id.startsWith("jdr:")) viewModel.configure(target)
    }

    // 直接由所选书源决定唯一弹窗，避免首次组合显示上一书源的输入框。
    if (source != null && source.id.startsWith("jdr:")) {
        JdrSourceDialog(source, viewModel.repository, onDismiss)
    }

    val active = state.source
    val items = state.configItems
    // 选中书源就立刻弹面板：配置项可能还在读（书源调用排在单线程队列里），
    // 之前要等 items 到位才弹，队列一忙就像「点了没反应」。现在先出面板 + 加载条，
    // 失败时错误也显示在面板里，不再需要单独的失败弹窗。
    if (source != null && !source.id.startsWith("jdr:") && active != null && active.id == source.id) {
        SourceConfigDialog(
            active.id,
            active.name,
            items.orEmpty(),
            state.loading,
            onDismiss = {
                viewModel.dismissConfig()
                onDismiss()
            },
            onSave = { values ->
                // 保存成功才关面板；失败时留着面板显示错误
                viewModel.saveConfig(values) {
                    viewModel.dismissConfig()
                    onDismiss()
                }
            },
            onAction = viewModel::configAction,
            revision = state.configRevision,
            error = state.error,
            message = state.configMessage,
            onRetry = viewModel::retry,
            onWebLogin = { viewModel.startWebLogin(active) },
            canWebLogin = state.webLoginAvailable,
        )
    }
}

/**
 * 承载 JAR 书源的 WebView 登录页。
 *
 * [TingshuViewModel.startWebLogin] 只负责把登录信息写进 [TingshuUiState.pendingLogin]，
 * 真正启动 [SourceLoginActivity] 需要 ActivityResult 启动器；阿里云这类「网页令牌」源还要
 * 从返回结果里取 token。所以这个宿主必须和配置面板一起组合，否则点「打开网页登录」没有任何反应。
 */
@Composable
private fun WebLoginHost(viewModel: TingshuViewModel, source: ListeningSource?) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(source?.id, state.webLoginAvailable) {
        val target = source ?: return@LaunchedEffect
        if (!state.webLoginAvailable) return@LaunchedEffect
        // 面板一打开就预热：WebView 提供者冷启动 + 登录页首屏都提前做掉
        viewModel.warmUpWebLogin(target)
    }
    // 面板关闭或换源时把预热用的离屏 WebView 归还内存（没关的话 2 分钟后自动释放）
    DisposableEffect(source?.id) {
        onDispose { WebLoginWarmUp.release() }
    }
    val context = LocalContext.current
    var loginSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val sourceId = loginSourceId
        loginSourceId = null
        val token = SourceLoginActivity.tokenFromResult(result.data)
        if (result.resultCode == Activity.RESULT_OK && sourceId != null && token.isNotBlank()) {
            viewModel.completeWebLogin(sourceId, token)
        } else {
            viewModel.reloadConfigAfterLogin()
        }
    }
    LaunchedEffect(state.pendingLogin) {
        state.pendingLogin?.let { login ->
            loginSourceId = login.sourceId
            viewModel.consumePendingLogin()
            WebLoginWarmUp.release()
            launcher.launch(SourceLoginActivity.intent(context, login.sourceName, login.url, login.userAgent))
        }
    }
}

@Composable
private fun SourceConfigDialog(
    sourceId: String,
    sourceName: String,
    items: List<ConfigItem>,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit,
    onAction: (() -> Unit, Map<String, String>) -> Unit,
    revision: Int,
    error: String?,
    message: String?,
    onRetry: () -> Unit,
    onWebLogin: () -> Unit,
    canWebLogin: Boolean,
) {
    var values by remember(sourceId, items, revision) {
        mutableStateOf(
            items.filterNot { it is ConfigItem.Button }.associate { item ->
                val default = when (item) {
                    is ConfigItem.Text -> item.default
                    is ConfigItem.Switch -> item.default.toString()
                    is ConfigItem.Select -> item.default
                    is ConfigItem.MultiSelect -> item.default.joinToString(",")
                    else -> ""
                }
                item.key to SourceHost.getString("$sourceId.${item.key}", default).orEmpty()
            },
        )
    }
    fun update(key: String, value: String) {
        values = values + (key to value)
    }
    SourceSettingsSheet(
        title = sourceName,
        busy = busy,
        onDismiss = onDismiss,
        onSave = { onSave(values) },
        canSave = items.isNotEmpty(),
    ) {
        error?.let { SourceErrorNotice(it, onRetry = onRetry.takeUnless { busy }) }
        message?.let { SourceStatusCard("书源提示", it) }
        if (items.isEmpty() && !busy && error == null) Text("该书源没有配置项", color = FluxTheme.colorScheme.onSurfaceVariant)
        if (canWebLogin) {
            OutlinedButton(onClick = onWebLogin, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text("打开网页登录") }
        }
        items.forEach { item ->
            val value = values[item.key].orEmpty()
            when (item) {
                is ConfigItem.Text -> SourceConfigField(
                    label = item.label,
                    value = value,
                    onValueChange = { update(item.key, it) },
                    enabled = !busy,
                    secret = listOf("password", "token", "cookie", "authorization").any { item.key.contains(it, true) },
                )
                is ConfigItem.Switch -> SourceConfigSwitch(item.label, value == "true", !busy) { update(item.key, it.toString()) }
                is ConfigItem.Select -> SourceConfigOptions(item.label, item.options, setOf(value), !busy) { update(item.key, it) }
                is ConfigItem.MultiSelect -> SourceConfigOptions(item.label, item.options, value.split(',').toSet(), !busy) { option ->
                    val selected = value.split(',').filter { it.isNotBlank() }.toMutableSet()
                    if (!selected.remove(option)) selected.add(option)
                    update(item.key, selected.joinToString(","))
                }
                is ConfigItem.Button -> OutlinedButton(onClick = { onAction(item.click, values) }, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(item.label) }
            }
        }
    }
}
