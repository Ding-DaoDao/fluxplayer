package com.fluxplayer.app.feature.player.ui.controls

import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.core.ui.extensions.copy
import com.fluxplayer.app.feature.player.R as PlayerR
import com.fluxplayer.app.feature.player.buttons.PlayerButton

@OptIn(UnstableApi::class)
@Composable
fun ControlsTopView(
    modifier: Modifier = Modifier,
    title: String,
    videoInfoLine: String = "",
    danmakuEnabled: Boolean = false,
    danmakuHasData: Boolean = false,
    onSettingsClick: () -> Unit = {},
    onDanmakuToggleClick: () -> Unit = {},
    onDanmakuSearchClick: () -> Unit = {},
    onDanmakuSettingsClick: () -> Unit = {},
    onBackClick: () -> Unit,
) {
    val systemBarsPadding = WindowInsets.systemBars.union(WindowInsets.displayCutout).asPaddingValues()
    Row(
        modifier = modifier
            .padding(systemBarsPadding.copy(bottom = 0.dp))
            .padding(horizontal = 8.dp)
            .padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PlayerButton(onClick = onBackClick) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_left),
                contentDescription = stringResource(PlayerR.string.back),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (videoInfoLine.isNotEmpty()) {
                Text(
                    text = videoInfoLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 弹幕搜索/本地文件按钮（始终可见，图标同 mpv 的弹幕设置按钮）
            PlayerButton(onClick = onDanmakuSearchClick) {
                Icon(
                    painter = painterResource(R.drawable.comment_note_24_filled),
                    contentDescription = stringResource(PlayerR.string.danmaku_search),
                )
            }
            // 弹幕开关按钮（始终可见，图标同 mpv 的弹幕开关按钮）
            val context = LocalContext.current
            PlayerButton(onClick = {
                if (danmakuHasData) {
                    onDanmakuToggleClick()
                } else {
                    Toast.makeText(context, "请先选择弹幕文件", Toast.LENGTH_SHORT).show()
                }
            }) {
                Icon(
                    painter = painterResource(
                        if (danmakuEnabled && danmakuHasData) R.drawable.ic_danmaku_visible
                        else R.drawable.ic_danmaku_hidden
                    ),
                    contentDescription = stringResource(PlayerR.string.danmaku_toggle),
                )
            }
            PlayerButton(onClick = onDanmakuSettingsClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_danmaku_settings),
                    contentDescription = stringResource(PlayerR.string.danmaku_settings),
                )
            }
            // 设置按钮（音轨/字幕）
            PlayerButton(onClick = onSettingsClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.settings),
                )
            }
        }
    }
}
