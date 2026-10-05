package com.fluxplayer.app.settings.screens.audiobook

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxplayer.app.core.ui.components.ClickablePreferenceItem
import com.fluxplayer.app.core.ui.components.FluxSettingsScaffold
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.theme.FluxTheme
import com.fluxplayer.app.feature.tingshu.TingshuConfigContent

@Composable
fun AudiobookSettingsScreen(onNavigateUp: () -> Unit, viewModel: AudiobookSettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val preferences by viewModel.preferencesState.collectAsStateWithLifecycle()
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                viewModel.setRootUri(uri.toString())
                error = null
            } catch (_: SecurityException) {
                error = "无法读取该目录，请重新选择有读取权限的文件夹"
            }
        }
    }
    FluxSettingsScaffold(title = "听书配置", onNavigateUp = onNavigateUp) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("本地书库", style = FluxTheme.typography.titleMedium)
            ClickablePreferenceItem(
                title = "书库路径",
                description = preferences.audiobookRootUri.takeIf { it.isNotBlank() }?.let { Uri.decode(it.substringAfterLast('/')) } ?: "尚未选择，点击设置",
                icon = NextIcons.Folder,
                onClick = { picker.launch(preferences.audiobookRootUri.takeIf { it.isNotBlank() }?.let(Uri::parse)) },
                isFirstItem = true,
                isLastItem = true,
            )
            Text("每本书放在独立的子文件夹中，章节和封面会自动整理。修改路径后，返回本地书库即可查看。", style = FluxTheme.typography.bodySmall, color = FluxTheme.colorScheme.onSurfaceVariant)
            if (preferences.audiobookRootUri.isNotBlank()) TextButton(onClick = { viewModel.setRootUri("") }) { Text("清除书库路径") }
            error?.let { Text(it, color = FluxTheme.colorScheme.error) }
            com.fluxplayer.app.feature.tingshu.AppListeningCacheSettings()
            TingshuConfigContent()
        }
    }
}
