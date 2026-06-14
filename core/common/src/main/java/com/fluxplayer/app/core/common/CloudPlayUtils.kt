package com.fluxplayer.app.core.common

import android.net.Uri
import com.fluxplayer.app.core.model.WebDavResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 统一的云盘/WebDAV/OpenList 媒体播放触发逻辑。
 *
 * 先调用 resolveUrl 预热缓存（云盘 API 调用 + 缓存写入），
 * 然后通过 buildPlaylistUri 构造播放列表传给播放器。
 *
 * @param item           用户点击的资源
 * @param allItems       当前目录全部资源
 * @param mediaFilter    过滤播放列表包含的文件类型（如 { it.isVideo } 或 { it.isAudio }）
 * @param resolveUrl     异步解析/构造真实播放 URL
 * @param buildPlaylistUri  为播放列表构造 URI
 * @param onPlayVideos   播放回调 (uris, startUri, isAudioOnly)
 * @param scope          协程作用域
 */
fun onCloudMediaClick(
    item: WebDavResource,
    allItems: List<WebDavResource>,
    mediaFilter: (WebDavResource) -> Boolean,
    resolveUrl: suspend () -> Uri?,
    buildPlaylistUri: (WebDavResource) -> Uri,
    onPlayVideos: (List<Uri>, Uri, Boolean) -> Unit,
    scope: CoroutineScope,
) {
    scope.launch {
        resolveUrl() ?: return@launch
        val mediaItems = allItems.filter(mediaFilter)
        val isAudio = mediaItems.firstOrNull()?.isAudio == true
        val allUris = mediaItems.map { res ->
            val base = buildPlaylistUri(res)
            if (isAudio) {
                // 把文件名和大小写入 fragment，播放器侧用于显示标题和信息
                val meta = "${Uri.encode(res.name)}|${res.size}"
                base.buildUpon().encodedFragment(meta).build()
            } else {
                base
            }
        }
        val startUri = allUris[mediaItems.indexOf(item).coerceAtLeast(0)]
        onPlayVideos(allUris, startUri, isAudio)
    }
}

/**
 * 视频播放快捷包装（向后兼容）。
 */
fun onCloudVideoClick(
    item: WebDavResource,
    allItems: List<WebDavResource>,
    resolveUrl: suspend () -> Uri?,
    buildPlaylistUri: (WebDavResource) -> Uri,
    onPlayVideos: (List<Uri>, Uri, Boolean) -> Unit,
    scope: CoroutineScope,
) {
    onCloudMediaClick(
        item = item,
        allItems = allItems,
        mediaFilter = { !it.isDirectory },
        resolveUrl = resolveUrl,
        buildPlaylistUri = buildPlaylistUri,
        onPlayVideos = onPlayVideos,
        scope = scope,
    )
}
