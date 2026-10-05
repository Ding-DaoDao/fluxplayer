package com.fluxplayer.app.core.common

/**
 * 本地视频帧缩略图的磁盘缓存键前缀。
 *
 * 云盘封面与本地视频帧共用同一个 Coil DiskCache 时，若缓存键不加区分，两类缩略图会互相
 * LRU 淘汰：本地视频数量一多，云盘封面被挤掉，用户回看目录时每个封面都要重新走网络
 * （表现为封面加载很慢）。
 *
 * 写入侧：[com.fluxplayer.app.VideoThumbnailDecoder.diskCacheKey]
 * 删除侧：[com.fluxplayer.app.core.media.sync.LocalMediaSynchronizer]
 * **两侧必须复用此常量**，否则缓存键对不上、旧缩略图删不掉。
 */
const val LOCAL_VIDEO_THUMBNAIL_PREFIX = "local_video:"
