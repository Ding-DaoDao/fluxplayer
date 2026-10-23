package com.fluxplayer.app.feature.videopicker.composables

import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fluxplayer.app.core.data.cloud.CloudRemoteFile
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.core.ui.components.MediaDropdownMenu
import com.fluxplayer.app.core.ui.components.NextDialog
import com.fluxplayer.app.core.ui.designsystem.NextIcons

enum class CloudFeatureAction(val label: String) {
    Search("搜索网盘"),
    Upload("上传文件"),
    Create("创建分享"),
    Import("打开分享链接"),
    Spaces("家庭空间"),
    Account("账号容量"),
    Playback("播放设置"),
}

@Composable
internal fun CloudFeatureForm(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    dismissLabel: String = "取消",
    content: @Composable ColumnScope.() -> Unit,
) {
    NextDialog(
        title = { Text(title) },
        onDismissRequest = onDismiss,
        content = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        },
        confirmButton = { Button(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmLabel) } },
        dismissButton = { if (confirmLabel != "完成") TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CloudFeatureBrowser(
    title: String,
    subtitle: String,
    busy: Boolean,
    onBack: () -> Unit,
    onClose: () -> Unit,
    bottomBar: @Composable () -> Unit,
    toolbarActions: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!busy) onBack() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BackHandler { if (!busy) onBack() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Scaffold(
                    modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = {
                                Column {
                                    Text(title, style = MaterialTheme.typography.titleLarge)
                                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            },
                            navigationIcon = { IconButton(onClick = onBack, enabled = !busy) { Icon(NextIcons.ArrowBack, "返回") } },
                            actions = { toolbarActions(); TextButton(onClick = onClose) { Text(if (busy) "停止" else "关闭") } },
                        )
                    },
                    bottomBar = {
                        Surface(color = MaterialTheme.colorScheme.surface) {
                            Column(Modifier.navigationBarsPadding()) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                bottomBar()
                            }
                        }
                    },
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        content()
                    }
                }
            }
        }
    }
}

@Composable
internal fun CloudFeatureEmptyState(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(NextIcons.FolderOutline, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Text(text, Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun CloudFeatureFileRow(
    file: CloudRemoteFile,
    enabled: Boolean,
    selected: Boolean? = null,
    onSelect: () -> Unit = {},
    onOpen: (() -> Unit)? = null,
    actions: List<Pair<String, () -> Unit>> = emptyList(),
) {
    var expanded by remember(file.id) { mutableStateOf(false) }
    val context = LocalContext.current
    val resource = remember(file) { WebDavResource(file.name, file.id, file.directory, file.size) }
    val fileIcon = when {
        file.directory -> NextIcons.Folder
        resource.isVideo -> NextIcons.Movie
        resource.isAudio -> NextIcons.Audio
        else -> NextIcons.FileOpen
    }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled && (onOpen != null || selected != null || actions.isNotEmpty())) {
            when {
                onOpen != null -> onOpen()
                selected != null -> onSelect()
                else -> expanded = true
            }
        }.heightIn(min = 76.dp).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Icon(fileIcon, null, Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (file.directory) "文件夹" else Formatter.formatFileSize(context, file.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected != null) Checkbox(selected, onCheckedChange = { onSelect() }, enabled = enabled)
        if (actions.isNotEmpty()) {
            Box {
                IconButton(onClick = { expanded = true }, enabled = enabled) { Icon(NextIcons.MoreVert, "${file.name} 的更多操作") }
                MediaDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    actions.forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            expanded = false
                            action()
                        })
                    }
                }
            }
        }
        if (file.directory && onOpen != null) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { Icon(Icons.Default.ChevronRight, "打开文件夹", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
