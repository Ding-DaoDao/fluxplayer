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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fluxplayer.app.core.ui.components.FluxIcon
import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.model.WebDavServer
import com.fluxplayer.app.feature.videopicker.aliyun.AliyunBrowserTabContent
import com.fluxplayer.app.feature.videopicker.cloud189.C189BrowserTabContent
import com.fluxplayer.app.feature.videopicker.openlist.OpenListBrowserTabContent
import com.fluxplayer.app.feature.videopicker.pan123.Pan123BrowserTabContent
import com.fluxplayer.app.feature.videopicker.quark.QuarkBrowserTabContent
import com.fluxplayer.app.feature.videopicker.screens.webdav.WebDavBrowserTabContent
import com.fluxplayer.app.feature.videopicker.yun139.Yun139BrowserTabContent
import com.fluxplayer.app.core.ui.R as UiR
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

data class BrowseProvider(
    val id: String,
    val name: String,
    val desc: String,
    val iconRes: Int,
)

private val defaultProviders = listOf(
    BrowseProvider("openlist", "OpenList", "浏览本地文件服务器", UiR.drawable.ic_provider_openlist),
    BrowseProvider("alipan", "阿里云盘", "阿里云盘文件浏览", UiR.drawable.ic_provider_alipan),
    BrowseProvider("yun139", "移动云盘", "移动云盘文件浏览", UiR.drawable.ic_provider_yun139),
    BrowseProvider("pan123", "123云盘", "123云盘文件浏览", UiR.drawable.ic_provider_pan123),
    BrowseProvider("quark", "夸克网盘", "夸克网盘文件浏览", UiR.drawable.ic_provider_quark),
    BrowseProvider("cloud189", "天翼云盘", "天翼云盘文件浏览", UiR.drawable.ic_provider_cloud189),
    BrowseProvider("uc", "UC网盘", "UC网盘文件浏览", UiR.drawable.ic_provider_uc),
)

private fun orderedProviders(providerOrder: List<String>): List<BrowseProvider> {
    if (providerOrder.isEmpty()) return defaultProviders
    val map = defaultProviders.associateBy { it.id }
    val ordered = providerOrder.mapNotNull { map[it] }
    val missing = defaultProviders.filter { it.id !in providerOrder }
    return ordered + missing
}

/** 将 defaultProviders（不含 webdav）与动态 WebDAV 服务器列表合并 */
private fun buildProviderList(
    providerOrder: List<String>,
    webDavServers: List<WebDavServer>,
): List<BrowseProvider> {
    val webdavEntries = webDavServers.map { server ->
        BrowseProvider(
            id = "webdav:${server.id}",
            name = server.name,
            desc = server.url,
            iconRes = UiR.drawable.ic_provider_webdav,
        )
    }
    val allStatic = defaultProviders
    val allEntries = webdavEntries + allStatic
    val map = allEntries.associateBy { it.id }
    if (providerOrder.isEmpty()) return allEntries
    // 按 providerOrder 排序，不在 order 中的放尾部
    val ordered = providerOrder.mapNotNull { map[it] }
    val missing = allEntries.filter { it.id !in providerOrder.toSet() }
    return ordered + missing
}

/**
 * "浏览"页 — 可拖拽排序的垂直列表，每行一个平台（图标 + 名称 + 描述），点击进入对应内容
 */
@Composable
fun BrowseTabs(
    onPlayVideo: (Uri, String?) -> Unit,
    onPlayVideos: (List<Uri>, Uri) -> Unit,
    onSettingsClick: () -> Unit,
    selectedProvider: String?,
    onProviderSelected: (String?) -> Unit,
    onProviderLogoutChanged: ((() -> Unit)?) -> Unit,
    preferences: ApplicationPreferences = ApplicationPreferences(),
    onProviderReordered: (List<String>) -> Unit = {},
    navigateToDirParam: Pair<String, String>? = null,
    onNavigateToDirConsumed: () -> Unit = {},
    webDavServers: List<WebDavServer> = emptyList(),
    modifier: Modifier = Modifier,
) {
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
            var items by remember(webDavServers, preferences.providerOrder) {
                mutableStateOf(buildProviderList(preferences.providerOrder, webDavServers))
            }
            val hapticFeedback = LocalHapticFeedback.current
            val lazyListState = rememberLazyListState()
            val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
                val newList = items.toMutableList().apply { add(to.index, removeAt(from.index)) }
                items = newList
                onProviderReordered(newList.map { it.id })
                hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = lazyListState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(items = items, key = { it.id }) { item ->
                    ReorderableItem(state = reorderableState, key = item.id) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .draggableHandle(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onProviderSelected(item.id) }
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
                                FluxIcon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // 已选中平台的内容页
            LaunchedEffect(provider) {
                if (provider == "webdav" || provider.startsWith("webdav:") || provider == "openlist") {
                    onProviderLogoutChanged(null)
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (provider) {
                    "alipan" -> AliyunBrowserTabContent(
                        modifier = Modifier.fillMaxSize(),
                        onPlayVideo = onPlayVideo,
                        onPlayVideos = onPlayVideos,
                        onLogoutReady = onProviderLogoutChanged,
                        onSettingsClick = onSettingsClick,
                        navigateToDirParam = navigateToDirParam,
                        onNavigateToDirConsumed = onNavigateToDirConsumed,
                    )
                    "quark" -> QuarkBrowserTabContent(
                        modifier = Modifier.fillMaxSize(),
                        onPlayVideo = onPlayVideo,
                        onPlayVideos = onPlayVideos,
                        onLogoutReady = onProviderLogoutChanged,
                        onSettingsClick = onSettingsClick,
                        navigateToDirParam = navigateToDirParam,
                        onNavigateToDirConsumed = onNavigateToDirConsumed,
                    )
                    "uc" -> QuarkBrowserTabContent(
                        modifier = Modifier.fillMaxSize(),
                        driveType = "uc",
                        onPlayVideo = onPlayVideo,
                        onPlayVideos = onPlayVideos,
                        onLogoutReady = onProviderLogoutChanged,
                        onSettingsClick = onSettingsClick,
                        navigateToDirParam = navigateToDirParam,
                        onNavigateToDirConsumed = onNavigateToDirConsumed,
                    )
                    "cloud189" -> C189BrowserTabContent(
                        modifier = Modifier.fillMaxSize(),
                        onPlayVideo = onPlayVideo,
                        onPlayVideos = onPlayVideos,
                        onLogoutReady = onProviderLogoutChanged,
                        onSettingsClick = onSettingsClick,
                        navigateToDirParam = navigateToDirParam,
                        onNavigateToDirConsumed = onNavigateToDirConsumed,
                    )
                    "pan123" -> Pan123BrowserTabContent(
                        modifier = Modifier.fillMaxSize(),
                        onPlayVideo = onPlayVideo,
                        onPlayVideos = onPlayVideos,
                        onLogoutReady = onProviderLogoutChanged,
                        onSettingsClick = onSettingsClick,
                        navigateToDirParam = navigateToDirParam,
                        onNavigateToDirConsumed = onNavigateToDirConsumed,
                    )
                    "yun139" -> Yun139BrowserTabContent(
                        modifier = Modifier.fillMaxSize(),
                        onPlayVideo = onPlayVideo,
                        onPlayVideos = onPlayVideos,
                        onLogoutReady = onProviderLogoutChanged,
                        onSettingsClick = onSettingsClick,
                        navigateToDirParam = navigateToDirParam,
                        onNavigateToDirConsumed = onNavigateToDirConsumed,
                    )
                    "openlist" -> OpenListBrowserTabContent(
                        modifier = Modifier.fillMaxSize(),
                        onPlayVideo = onPlayVideo,
                        onPlayVideos = onPlayVideos,
                        onSettingsClick = onSettingsClick,
                        navigateToDirParam = navigateToDirParam,
                        onNavigateToDirConsumed = onNavigateToDirConsumed,
                    )
                    // 旧格式（历史跳转）—— 不带 serverId，由 ViewModel 内部自动选择第一个服务器
                    "webdav" -> WebDavBrowserTabContent(
                        modifier = Modifier.fillMaxSize(),
                        onPlayVideo = onPlayVideo,
                        onPlayVideos = onPlayVideos,
                        onSettingsClick = onSettingsClick,
                        navigateToDirParam = navigateToDirParam,
                        onNavigateToDirConsumed = onNavigateToDirConsumed,
                    )
                    else -> {
                        // 动态 WebDAV 实例（id 格式: webdav:{serverId}）
                        if (provider.startsWith("webdav:")) {
                            val serverId = provider.removePrefix("webdav:")
                            WebDavBrowserTabContent(
                                modifier = Modifier.fillMaxSize(),
                                serverId = serverId,
                                onPlayVideo = onPlayVideo,
                                onPlayVideos = onPlayVideos,
                                onSettingsClick = onSettingsClick,
                                navigateToDirParam = navigateToDirParam,
                                onNavigateToDirConsumed = onNavigateToDirConsumed,
                            )
                        }
                    }
                }
            }
        }
    }
}
