package com.fluxplayer.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.components.FluxSettingsScaffold
import com.fluxplayer.app.core.ui.designsystem.NextIcons

@Composable
fun SettingsScreen(
    onNavigateUp: () -> Unit,
    onItemClick: (Setting) -> Unit,
) {
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    FluxSettingsScaffold(
        title = stringResource(R.string.settings),
        onNavigateUp = onNavigateUp,
    ) { innerPadding ->
        val groups = remember {
            listOf(
                R.string.settings_group_interface to listOf(SettingRow.APPEARANCE, SettingRow.MEDIA_LIBRARY),
                R.string.settings_group_playback to listOf(SettingRow.PLAYER, SettingRow.GESTURES, SettingRow.AUDIO, SettingRow.SUBTITLE),
                R.string.settings_group_content to listOf(SettingRow.AUDIOBOOK, SettingRow.WEBDAV, SettingRow.OPENLIST),
                R.string.settings_group_data to listOf(SettingRow.GENERAL, SettingRow.ABOUT),
            )
        }
        Column(
            Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            com.fluxplayer.app.core.ui.avbox.AppInfoHeaderCard(version)
            groups.forEach { (label, rows) ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(label), Modifier.padding(start = 16.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        rows.forEachIndexed { index, row ->
                            SettingsDestination(row, index == 0, index == rows.lastIndex) { onItemClick(row.setting) }
                        }
                    }
                }
            }
        }
    }
}

/** 设置首页使用独立的目的地卡片，图标、标题、说明和箭头各自占据固定区域。 */
@Composable
private fun SettingsDestination(row: SettingRow, first: Boolean, last: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceBright,
        shape = RoundedCornerShape(
            topStart = if (first) 32.dp else 4.dp,
            topEnd = if (first) 32.dp else 4.dp,
            bottomStart = if (last) 32.dp else 4.dp,
            bottomEnd = if (last) 32.dp else 4.dp,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            com.fluxplayer.app.core.ui.components.MediaIconBadge(row.icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                Text(stringResource(row.titleResId), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(row.descriptionResId), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", fontSize = 28.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

enum class Setting {
    APPEARANCE,
    MEDIA_LIBRARY,
    PLAYER,
    GESTURES,
    AUDIO,
    AUDIOBOOK,
    SUBTITLE,
    WEBDAV,
    OPENLIST,
    GENERAL,
    ABOUT,
}

private enum class SettingRow(
    val titleResId: Int,
    val descriptionResId: Int,
    val icon: ImageVector,
    val setting: Setting,
) {
    APPEARANCE(
        titleResId = R.string.appearance_name,
        descriptionResId = R.string.appearance_description,
        icon = NextIcons.Appearance,
        setting = Setting.APPEARANCE,
    ),
    MEDIA_LIBRARY(
        titleResId = R.string.media_library,
        descriptionResId = R.string.media_library_description,
        icon = NextIcons.Movie,
        setting = Setting.MEDIA_LIBRARY,
    ),
    PLAYER(
        titleResId = R.string.player_name,
        descriptionResId = R.string.player_description,
        icon = NextIcons.Player,
        setting = Setting.PLAYER,
    ),
    GESTURES(
        titleResId = R.string.gestures_name,
        descriptionResId = R.string.gestures_description,
        icon = NextIcons.SwipeHorizontal,
        setting = Setting.GESTURES,
    ),
    AUDIO(
        titleResId = R.string.audio,
        descriptionResId = R.string.audio_desc,
        icon = NextIcons.Audio,
        setting = Setting.AUDIO,
    ),
    AUDIOBOOK(
        titleResId = R.string.audiobook_settings,
        descriptionResId = R.string.audiobook_settings_description,
        icon = NextIcons.Audio,
        setting = Setting.AUDIOBOOK,
    ),
    SUBTITLE(
        titleResId = R.string.subtitle,
        descriptionResId = R.string.subtitle_desc,
        icon = NextIcons.Subtitle,
        setting = Setting.SUBTITLE,
    ),
    WEBDAV(
        titleResId = R.string.webdav_settings,
        descriptionResId = R.string.webdav_settings_description,
        icon = NextIcons.Link,
        setting = Setting.WEBDAV,
    ),
    OPENLIST(
        titleResId = R.string.openlist_settings,
        descriptionResId = R.string.openlist_settings_description,
        icon = NextIcons.Settings,
        setting = Setting.OPENLIST,
    ),
    GENERAL(
        titleResId = R.string.general_name,
        descriptionResId = R.string.general_description,
        icon = NextIcons.ExtraSettings,
        setting = Setting.GENERAL,
    ),
    ABOUT(
        titleResId = R.string.about_name,
        descriptionResId = R.string.about_description,
        icon = NextIcons.Info,
        setting = Setting.ABOUT,
    ),
}
