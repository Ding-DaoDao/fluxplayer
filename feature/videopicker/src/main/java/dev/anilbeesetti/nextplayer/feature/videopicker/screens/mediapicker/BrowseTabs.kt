package dev.anilbeesetti.nextplayer.feature.videopicker.screens.mediapicker

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.anilbeesetti.nextplayer.feature.videopicker.aliyun.AliyunBrowserTabContent
import dev.anilbeesetti.nextplayer.feature.videopicker.cloud189.C189BrowserTabContent
import dev.anilbeesetti.nextplayer.feature.videopicker.openlist.OpenListBrowserTabContent
import dev.anilbeesetti.nextplayer.feature.videopicker.pan123.Pan123BrowserTabContent
import dev.anilbeesetti.nextplayer.feature.videopicker.quark.QuarkBrowserTabContent
import dev.anilbeesetti.nextplayer.feature.videopicker.screens.webdav.WebDavBrowserTabContent
import dev.anilbeesetti.nextplayer.feature.videopicker.yun139.Yun139BrowserTabContent
import dev.anilbeesetti.nextplayer.core.ui.R as UiR

data class BrowseProvider(
    val id: String,
    val name: String,
    val desc: String,
    val iconRes: Int,
)

internal val providers = listOf(
    BrowseProvider("webdav", "WebDAV", "浏览远程服务器文件", UiR.drawable.ic_provider_webdav),
    BrowseProvider("openlist", "OpenList", "浏览本地文件服务器", UiR.drawable.ic_provider_openlist),
    BrowseProvider("alipan", "阿里云盘", "阿里云盘文件浏览", UiR.drawable.ic_provider_alipan),
    BrowseProvider("yun139", "移动云盘", "移动云盘文件浏览", UiR.drawable.ic_provider_yun139),
    BrowseProvider("pan123", "123云盘", "123云盘文件浏览", UiR.drawable.ic_provider_pan123),
    BrowseProvider("quark", "夸克网盘", "夸克网盘文件浏览", UiR.drawable.ic_provider_quark),
    BrowseProvider("cloud189", "天翼云盘", "天翼云盘文件浏览", UiR.drawable.ic_provider_cloud189),
    BrowseProvider("uc", "UC网盘", "UC网盘文件浏览", UiR.drawable.ic_provider_uc),
)

/**
 * "浏览"页 — 垂直列表，每行一个平台（图标 + 名称 + 描述），点击进入对应内容
 */
@Composable
fun BrowseTabs(
    onPlayVideo: (Uri, String?) -> Unit,
    onPlayVideos: (List<Uri>, Uri) -> Unit,
    onSettingsClick: () -> Unit,
    selectedProvider: String?,
    onProviderSelected: (String?) -> Unit,
    onProviderLogoutChanged: ((() -> Unit)?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 返回键：在平台选择列表时不做处理；进入云盘后在根目录时返回列表
    BackHandler(enabled = selectedProvider != null) {
        onProviderSelected(null)
    }

    if (selectedProvider == null) {
        // 平台选择列表 — 每个 provider 一个独立圆角卡片，卡片间有间隙
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            providers.forEach { provider ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onProviderSelected(provider.id) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            painter = painterResource(provider.iconRes),
                            contentDescription = provider.name,
                            modifier = Modifier.size(40.dp),
                            contentScale = ContentScale.Fit,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = provider.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = provider.desc,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    } else {
        // 已选中平台的内容页
        // WebDAV/OpenList 无退出登录，清除回调
        LaunchedEffect(selectedProvider) {
            if (selectedProvider == "webdav" || selectedProvider == "openlist") {
                onProviderLogoutChanged(null)
            }
        }

        Column(modifier = modifier.fillMaxSize()) {
            when (selectedProvider) {
                "webdav" -> WebDavBrowserTabContent(
                    onPlayVideo = onPlayVideo,
                    onPlayVideos = onPlayVideos,
                    onSettingsClick = onSettingsClick,
                )
                "alipan" -> AliyunBrowserTabContent(
                    onPlayVideo = onPlayVideo,
                    onPlayVideos = onPlayVideos,
                    onLogoutReady = onProviderLogoutChanged,
                    onSettingsClick = onSettingsClick,
                )
                "quark" -> QuarkBrowserTabContent(
                    onPlayVideo = onPlayVideo,
                    onPlayVideos = onPlayVideos,
                    onLogoutReady = onProviderLogoutChanged,
                    onSettingsClick = onSettingsClick,
                )
                "uc" -> QuarkBrowserTabContent(
                    driveType = "uc",
                    onPlayVideo = onPlayVideo,
                    onPlayVideos = onPlayVideos,
                    onLogoutReady = onProviderLogoutChanged,
                    onSettingsClick = onSettingsClick,
                )
                "cloud189" -> C189BrowserTabContent(
                    onPlayVideo = onPlayVideo,
                    onPlayVideos = onPlayVideos,
                    onLogoutReady = onProviderLogoutChanged,
                    onSettingsClick = onSettingsClick,
                )
                "pan123" -> Pan123BrowserTabContent(
                    onPlayVideo = onPlayVideo,
                    onPlayVideos = onPlayVideos,
                    onLogoutReady = onProviderLogoutChanged,
                    onSettingsClick = onSettingsClick,
                )
                "yun139" -> Yun139BrowserTabContent(
                    onPlayVideo = onPlayVideo,
                    onPlayVideos = onPlayVideos,
                    onLogoutReady = onProviderLogoutChanged,
                    onSettingsClick = onSettingsClick,
                )
                "openlist" -> OpenListBrowserTabContent(
                    onPlayVideo = onPlayVideo,
                    onPlayVideos = onPlayVideos,
                    onSettingsClick = onSettingsClick,
                )
                else -> { /* 不应该到达 */ }
            }
        }
    }
}
