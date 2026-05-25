package dev.anilbeesetti.nextplayer.feature.player.danmaku

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anilbeesetti.nextplayer.core.ui.R

/**
 * 弹幕来源选择小窗。
 *
 * 未加载弹幕时弹出，居中紧凑卡片，让用户选择：
 * - 从本地文件选择
 * - 在线搜索弹幕
 */
@Composable
fun DanmakuSourceSelectorSheet(
    show: Boolean,
    onPickLocalFile: () -> Unit,
    onSearchOnline: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return

    Box(modifier = Modifier.fillMaxSize()) {
        // 半透明背景 — 点击关闭
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onDismiss),
        )

        // 右上角紧凑小浮窗
        Card(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 100.dp, end = 16.dp),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1E1E2E),
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
            ) {
                // 本地文件
                MenuOption(
                    icon = R.drawable.ic_danmaku,
                    label = "本地文件",
                    onClick = onPickLocalFile,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 在线搜索
                MenuOption(
                    icon = R.drawable.ic_search,
                    label = "在线搜索",
                    onClick = onSearchOnline,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 取消
                MenuOption(
                    icon = R.drawable.ic_close,
                    label = "取消",
                    tint = Color.Gray,
                    onClick = onDismiss,
                )
            }
        }
    }
}

@Composable
private fun MenuOption(
    icon: Int,
    label: String,
    onClick: () -> Unit,
    tint: Color = Color(0xFF00A0FF),
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                color = Color(0xFF2A2A3E),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            color = if (tint == Color.Gray) Color.Gray else Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
