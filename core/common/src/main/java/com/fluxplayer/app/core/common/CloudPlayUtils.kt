package com.fluxplayer.app.core.common

import android.net.Uri
import com.fluxplayer.app.core.model.WebDavResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 统一的云盘/WebDAV/OpenList 视频播放触发逻辑。
 *
 * 先调用 resolveUrl 预热缓存（云盘 API 调用 + 缓存写入），
 * 然后通过 buildPlaylistUri 构造播放列表传给播放器。
 *
 * 云盘：resolveUrl 调用 API 写入 CloudPlaylistCache，player 侧解析时命中缓存秒返。
 * OpenList/WebDAV：resolveUrl 同步构造 URL，buildPlaylistUri 直接返回 HTTP URL。
 *
 * 注意：不使用 resolveUrl 的返回值作为播放 URI，避免 HTTP CDN URL 成为 mediaId
 * 导致播放器标题显示乱码（mediaId 被用于 DB 标题查询，cloud:// URI 才能正确匹配）。
 *
 * @param item      用户点击的视频资源
 * @param allItems  当前目录全部资源（用于构建播放列表）
 * @param resolveUrl  异步解析/构造真实播放 URL（云盘调 API 预热缓存 / OpenList/WebDAV 同步拼接）
 * @param buildPlaylistUri  为播放列表构造 URI（cloud:// 或 webdav URL）
 * @param onPlayVideos  播放回调
 * @param scope  协程作用域
 */
fun onCloudVideoClick(
    item: WebDavResource,
    allItems: List<WebDavResource>,
    resolveUrl: suspend () -> Uri?,
    buildPlaylistUri: (WebDavResource) -> Uri,
    onPlayVideos: (List<Uri>, Uri) -> Unit,
    scope: CoroutineScope,
) {
    scope.launch {
        // 预热缓存：云盘走 API → 写 CloudPlaylistCache，OpenList/WebDAV 同步构造直接返回
        resolveUrl() ?: return@launch
        val videoItems = allItems.filter { !it.isDirectory }
        val allUris = videoItems.map { buildPlaylistUri(it) }
        val startUri = buildPlaylistUri(item)
        onPlayVideos(allUris, startUri)
    }
}
