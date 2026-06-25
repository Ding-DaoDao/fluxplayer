package com.fluxplayer.app.core.common

/**
 * 123 云盘 HLS 备用 URL 缓存。
 *
 * 部分 MKV 视频容器元数据损坏（SeekHead 偏移 > 实际文件大小），
 * 导致 ExoPlayer seek 时 CDN 返回 416 → Source error。
 * HLS (.m3u8) 没有容器级 seek 问题，作为备用播放路径。
 */
object Pan123FallbackCache {
    private val fallbackMap = mutableMapOf<String, String>()

    fun put(fileId: String, hlsUrl: String) {
        fallbackMap[fileId] = hlsUrl
    }

    fun get(fileId: String): String? = fallbackMap[fileId]

    fun remove(fileId: String) {
        fallbackMap.remove(fileId)
    }
}
