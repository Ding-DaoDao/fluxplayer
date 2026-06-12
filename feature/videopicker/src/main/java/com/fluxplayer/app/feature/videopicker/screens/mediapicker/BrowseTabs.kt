package com.fluxplayer.app.feature.videopicker.screens.mediapicker

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.feature.videopicker.aliyun.AliyunBrowserTabContent
import com.fluxplayer.app.feature.videopicker.cloud189.C189BrowserTabContent
import com.fluxplayer.app.feature.videopicker.openlist.OpenListBrowserTabContent
import com.fluxplayer.app.feature.videopicker.pan123.Pan123BrowserTabContent
import com.fluxplayer.app.feature.videopicker.quark.QuarkBrowserTabContent
import com.fluxplayer.app.feature.videopicker.screens.webdav.WebDavBrowserTabContent
import com.fluxplayer.app.feature.videopicker.yun139.Yun139BrowserTabContent
import com.fluxplayer.app.core.ui.R as UiR

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
    navigateToDirParam: Pair<String, String>? = null,
    onNavigateToDirConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 返回键：在平台选择列表时不做处理；进入云盘后在根目录时返回列表
    BackHandler(enabled = selectedProvider != null) {
        onProviderSelected(null)
    }

    val animTween = tween<IntOffset>(280, easing = EaseOutCubic)
    val alphaTween = tween<Float>(280, easing = EaseOutCubic)

    AnimatedContent(
        targetState = selectedProvider,
        modifier = modifier,
        transitionSpec = {
            if (targetState != null) {
                slideInHorizontally(animTween) { it / 4 } + fadeIn(alphaTween) togetherWith
                    slideOutHorizontally(animTween) { -it / 4 } + fadeOut(alphaTween)
            } else {
                slideInHorizontally(animTween) { -it / 4 } + fadeIn(alphaTween) togetherWith
                    slideOutHorizontally(animTween) { it / 4 } + fadeOut(alphaTween)
            }.using(SizeTransform(clip = false))
        },
        label = "BrowseTransition",
    ) { provider ->
        if (provider == null) {
            // 平台选择列表
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                providers.forEach { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onProviderSelected(item.id) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                painter = painterResource(item.iconRes),
                                contentDescription = item.name,
                                modifier = Modifier.size(48.dp),
                                contentScale = ContentScale.Fit,
                            )
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = item.desc,
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
            LaunchedEffect(provider) {
                if (provider == "webdav" || provider == "openlist") {
                    onProviderLogoutChanged(null)
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                when (provider) {
                    "webdav" -> WebDavBrowserTabContent(
                        onPlayVideo = onPlayVideo,
                        onPlayVideos = onPlayVideos,
                        onSettingsClick = onSettingsClick,
                        navigateToDirParam = navigateToDirParam,
                        onNavigateToDirConsumed = onNavigateToDirConsumed,
                    )
                    "alipan" -> AliyunBrowserTabContent(
                        onPlayVideo = onPlayVideo,
                        onPlayVideos = onPlayVideos,
                        onLogoutReady = onProviderLogoutChanged,
                        onSettingsClick = onSettingsClick,
                        navigateToDirParam = navigateToDirParam,
                        onNavigateToDirConsumed = onNavigateToDirConsumed,
                    )
                    "quark" -> QuarkBrowserTabContent(
                        onPlayVideo = onPlayVideo,
                        onPlayVideos = onPlayVideos,
                        onLogoutReady = onProviderLogoutChanged,
                        onSettingsClick = onSettingsClick,
                        navigateToDirParam = navigateToDirParam,
                        onNavigateToDirConsumed = onNavigateToDirConsumed,
                    )
                    "uc" -> QuarkBrowserTabContent(
                        driveType = "uc",
                        onPlayVideo = onPlayVideo,
                        onPlayVideos = onPlayVideos,
                        onLogoutReady = onProviderLogoutChanged,
                        onSettingsClick = onSettingsClick,
                        navigateToDirParam = navigateToDirParam,
                        onNavigateToDirConsumed = onNavigateToDirConsumed,
                    )
                    "cloud189" -> C189BrowserTabContent(
                        onPlayVideo = onPlayVideo,
                        onPlayVideos = onPlayVideos,
                        onLogoutReady = onProviderLogoutChanged,
                        onSettingsClick = onSettingsClick,
                        navigateToDirParam = navigateToDirParam,
                        onNavigateToDirConsumed = onNavigateToDirConsumed,
                    )
                    "pan123" -> Pan123BrowserTabContent(
                        onPlayVideo = onPlayVideo,
                        onPlayVideos = onPlayVideos,
                        onLogoutReady = onProviderLogoutChanged,
                        onSettingsClick = onSettingsClick,
                        navigateToDirParam = navigateToDirParam,
                        onNavigateToDirConsumed = onNavigateToDirConsumed,
                    )
                    "yun139" -> Yun139BrowserTabContent(
                        onPlayVideo = onPlayVideo,
                        onPlayVideos = onPlayVideos,
                        onLogoutReady = onProviderLogoutChanged,
                        onSettingsClick = onSettingsClick,
                        navigateToDirParam = navigateToDirParam,
                        onNavigateToDirConsumed = onNavigateToDirConsumed,
                    )
                    "openlist" -> OpenListBrowserTabContent(
                        onPlayVideo = onPlayVideo,
                        onPlayVideos = onPlayVideos,
                        onSettingsClick = onSettingsClick,
                        navigateToDirParam = navigateToDirParam,
                        onNavigateToDirConsumed = onNavigateToDirConsumed,
                    )
                    else -> { /* 不应该到达 */ }
                }
            }
        }
    }
}
