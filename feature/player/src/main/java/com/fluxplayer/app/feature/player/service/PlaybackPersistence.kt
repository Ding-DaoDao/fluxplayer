package com.fluxplayer.app.feature.player.service

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.fluxplayer.app.core.common.CloudPlaylistCache
import com.fluxplayer.app.core.common.CloudUriScheme
import com.fluxplayer.app.core.common.extensions.fromUri
import com.fluxplayer.app.core.common.extensions.getFilenameFromUri
import com.fluxplayer.app.core.data.repository.MediaRepository
import com.fluxplayer.app.core.data.repository.PlaybackHistoryRepository
import com.fluxplayer.app.core.model.VideoSource
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val TAG = "PlaybackPersistence"

/**
 * 播放状态持久化助手：封装播放位置 / 倍速 / 播放历史的 DB 写入。
 *
 * 所有写入通过 [saveScope]（独立于 serviceScope 的 IO scope）执行，
 * 保证 Service 销毁时仍能完成最后的状态保存。
 */
@OptIn(UnstableApi::class)
class PlaybackPersistence(
    private val context: Context,
    private val mediaRepository: MediaRepository,
    private val playbackHistoryRepository: PlaybackHistoryRepository,
    private val saveScope: CoroutineScope,
) {

    private val audioExtensions = setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "wma", "opus")

    fun isAudioFile(uri: String): Boolean {
        val ext = uri.substringAfterLast('.', "").lowercase()
        return ext in audioExtensions
    }

    /** 保存播放位置（fire-and-forget）。 */
    fun savePosition(uri: String, position: Long) {
        saveScope.launch {
            mediaRepository.updateMediumPosition(uri, position)
        }
    }

    /** 保存目录级播放倍速（fire-and-forget）。 */
    fun savePlaybackSpeed(dirKey: String, speed: Float) {
        saveScope.launch {
            mediaRepository.updateMediumPlaybackSpeed(dirKey, speed)
        }
    }

    /**
     * 记录播放历史（fire-and-forget）。
     * 纯音频文件（本地文件 + 音频扩展名）不记录。
     */
    fun recordHistory(mediaItem: MediaItem, position: Long) {
        val uri = mediaItem.mediaId
        if (isAudioFile(uri)) return

        val title = mediaItem.mediaMetadata.title?.toString()
            ?: context.getFilenameFromUri(uri.toUri())
        val duration = mediaItem.mediaMetadata.durationMs ?: 0L
        val source = VideoSource.fromUri(uri)
        // 从 PlayerFrameCapture 取出退出时截取的缩略图路径
        val preCapturedPath = PlayerFrameCapture.take(uri)
        // 计算父目录名
        val parentPath = computeParentPath(uri, source)
        saveScope.launch {
            playbackHistoryRepository.recordPlayback(
                uriString = uri,
                title = title,
                source = source,
                position = position,
                duration = duration,
                originalUriString = if (source == VideoSource.WEBDAV) uri else null,
                thumbnailPath = preCapturedPath,
                parentPath = parentPath,
            )
        }
        Log.d(TAG, "recordHistory: uri=$uri position=$position thumbnailPath=${preCapturedPath ?: "<none>"}")
    }

    private fun computeParentPath(uriString: String, source: VideoSource): String? {
        val uri = uriString.toUri()
        return when (source) {
            VideoSource.LOCAL -> {
                try {
                    uri.path?.let { File(it).parentFile?.name }
                } catch (_: Exception) {
                    null
                }
            }
            VideoSource.WEBDAV -> {
                // 优先从 CloudPlaylistCache 获取完整 parentPath
                val cachePath = uri.path?.let { filePath ->
                    CloudPlaylistCache.getFileMetadata("webdav", filePath)?.parentPath
                }
                if (cachePath != null) return cachePath
                // 回退：从路径提取父目录名
                val segments = uri.path?.trimEnd('/')?.split("/")?.filter { it.isNotEmpty() } ?: return null
                segments.dropLast(1).lastOrNull()
            }
            VideoSource.OPENLIST -> {
                // OpenList URI 格式：http://127.0.0.1:5244/d/path/to/file → 去掉 /d 前缀即文件 path
                val filePath = uri.path?.removePrefix("/d") ?: uri.path
                if (filePath != null) {
                    CloudPlaylistCache.getFileMetadata("openlist", filePath)?.parentPath
                } else null
            }
            VideoSource.QUARK, VideoSource.UC,
            VideoSource.ALIYUN,
            VideoSource.PAN123,
            VideoSource.CLOUD189,
            VideoSource.YUN139,
            -> {
                val provider = CloudUriScheme.getProvider(uri) ?: return null
                val fileId = CloudUriScheme.getFileId(uri) ?: return null
                CloudPlaylistCache.getFileMetadata(provider, fileId)?.parentPath
            }
            else -> null
        }
    }
}
