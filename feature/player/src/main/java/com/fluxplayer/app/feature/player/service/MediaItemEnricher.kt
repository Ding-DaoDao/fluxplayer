package com.fluxplayer.app.feature.player.service

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import coil3.ImageLoader
import coil3.request.ImageRequest
import com.fluxplayer.app.core.common.CloudAwareCacheKeyRegistry
import com.fluxplayer.app.core.common.CloudPlaylistCache
import com.fluxplayer.app.core.common.CloudUriScheme
import com.fluxplayer.app.core.common.extensions.getFilenameFromUri
import com.fluxplayer.app.core.common.extensions.getLocalSubtitles
import com.fluxplayer.app.core.common.extensions.getPath
import com.fluxplayer.app.core.common.sanitizeUrl
import com.fluxplayer.app.core.data.cloud.CloudUriResolver
import com.fluxplayer.app.core.data.repository.MediaRepository
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.feature.player.R
import com.fluxplayer.app.feature.player.extensions.audioTrackIndex
import com.fluxplayer.app.feature.player.extensions.introMs
import com.fluxplayer.app.feature.player.extensions.outroMs
import com.fluxplayer.app.feature.player.extensions.playbackSpeed
import com.fluxplayer.app.feature.player.extensions.positionMs
import com.fluxplayer.app.feature.player.extensions.setExtras
import com.fluxplayer.app.feature.player.extensions.subtitleDelayMilliseconds
import com.fluxplayer.app.feature.player.extensions.subtitleSpeed
import com.fluxplayer.app.feature.player.extensions.subtitleTrackIndex
import com.fluxplayer.app.feature.player.extensions.uriToSubtitleConfiguration
import com.fluxplayer.app.feature.player.extensions.videoZoom
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

private const val TAG = "MediaItemEnricher"

/**
 * 媒体项元数据增强：在播放会话建立前为每个 [MediaItem] 补齐
 * 云盘 URL 预解析、字幕配置、标题、片头片尾、倍速、播放位置等元数据。
 *
 * 从 PlayerService 拆分而来，职责单一、依赖注入明确，便于独立测试。
 */
@OptIn(UnstableApi::class)
class MediaItemEnricher(
    private val context: Context,
    private val mediaRepository: MediaRepository,
    private val cloudUriResolver: CloudUriResolver,
    private val preferencesRepository: PreferencesRepository,
    private val imageLoader: ImageLoader,
    private val cacheKeyRegistry: CloudAwareCacheKeyRegistry,
) {

    /**
     * 为播放列表中的每个 [MediaItem] 补齐元数据（并行执行）。
     */
    suspend fun enrich(mediaItems: List<MediaItem>): List<MediaItem> = supervisorScope {
        mediaItems.map { mediaItem ->
            async {
                val mediaId = mediaItem.mediaId
                val uri = mediaId.toUri()

                // Pre-resolve cloud URIs on background thread to avoid blocking ExoPlayer start
                // 但如果 MediaItem 已有有效的 HTTP URL（如画质切换传入的新 URL），不要覆盖
                val existingUri = mediaItem.localConfiguration?.uri
                val resolvedUri = if (existingUri != null && existingUri.scheme in listOf("http", "https")) {
                    // 已有 HTTP URL，保持原样（画质切换场景）
                    Log.d(TAG, "Pre-resolve: keeping existing HTTP URI: ${existingUri.toString().take(120)}")
                    null
                } else if (CloudUriScheme.isCloudUri(uri)) {
                    try {
                        val resolved = cloudUriResolver.resolve(uri)
                        if (resolved != null) {
                            cacheKeyRegistry.register(resolved.toString(), uri.toString())
                            Log.d(TAG, "Pre-resolved cloud URI: $uri -> ${sanitizeUrl(resolved.toString())}")
                        }
                        resolved
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to pre-resolve cloud URI: $uri", e)
                        null
                    }
                } else {
                    null
                }

                val video = mediaRepository.getVideoByUri(uri = mediaId)
                val videoState = mediaRepository.getVideoState(uri = mediaId)

                // 片头片尾 & 倍速使用带来源前缀的目录级 key
                val parentDirKey = computeDirKey(mediaId)
                val dirVideoState = if (parentDirKey.isNotEmpty()) mediaRepository.getVideoState(parentDirKey) else null

                val externalSubs = videoState?.externalSubs ?: emptyList()
                val localSubs = (videoState?.path ?: context.getPath(uri))?.let {
                    File(it).getLocalSubtitles(
                        context = context,
                        excludeSubsList = externalSubs,
                    )
                } ?: emptyList()

                val existingSubConfigurations = mediaItem.localConfiguration?.subtitleConfigurations ?: emptyList()
                val subConfigurations = (localSubs + externalSubs).map { subtitleUri ->
                    context.uriToSubtitleConfiguration(
                        uri = subtitleUri,
                        subtitleEncoding = preferencesRepository.playerPreferences.value.subtitleTextEncoding,
                    )
                }

                // Use placeholder artwork initially - actual artwork will be loaded in background
                val artworkUri = getDefaultArtworkUri()

                val title = mediaItem.mediaMetadata.title
                    ?: video?.nameWithExtension
                    ?: getTitleForUri(uri)
                val positionMs = mediaItem.mediaMetadata.positionMs ?: videoState?.position
                val videoScale = mediaItem.mediaMetadata.videoZoom ?: videoState?.videoScale
                // 倍速优先目录级，回退文件级（同目录所有剧集共享）
                val playbackSpeed = mediaItem.mediaMetadata.playbackSpeed
                    ?: dirVideoState?.playbackSpeed
                    ?: videoState?.playbackSpeed
                val audioTrackIndex = mediaItem.mediaMetadata.audioTrackIndex ?: videoState?.audioTrackIndex
                val subtitleTrackIndex = mediaItem.mediaMetadata.subtitleTrackIndex ?: videoState?.subtitleTrackIndex
                val subtitleDelay = mediaItem.mediaMetadata.subtitleDelayMilliseconds ?: videoState?.subtitleDelayMilliseconds
                val subtitleSpeed = mediaItem.mediaMetadata.subtitleSpeed ?: videoState?.subtitleSpeed
                // 片头片尾优先使用目录级 key（同目录所有剧集共享）
                val introMs = dirVideoState?.introMs?.takeIf { it != -1L } ?: -1L
                val outroMs = dirVideoState?.outroMs?.takeIf { it != -1L } ?: -1L

                Log.d(TAG, "updatedMediaItems: mediaId=$mediaId, parentDirKey=$parentDirKey, " +
                    "dbPosition=${videoState?.position}, positionMs=$positionMs, " +
                    "playbackSpeed=$playbackSpeed, introMs=$introMs, outroMs=$outroMs")

                mediaItem.buildUpon().apply {
                    // Use pre-resolved HTTP URL so ExoPlayer starts buffering immediately
                    if (resolvedUri != null) {
                        setUri(resolvedUri)
                        setMediaId(mediaId)
                    }
                    setSubtitleConfigurations(existingSubConfigurations + subConfigurations)
                    setMediaMetadata(
                        MediaMetadata.Builder().apply {
                            setTitle(title)
                            setArtworkUri(artworkUri)
                            setExtras(
                                positionMs = positionMs,
                                videoScale = videoScale,
                                playbackSpeed = playbackSpeed,
                                audioTrackIndex = audioTrackIndex,
                                subtitleTrackIndex = subtitleTrackIndex,
                                subtitleDelayMilliseconds = subtitleDelay,
                                subtitleSpeed = subtitleSpeed,
                                introMs = introMs,
                                outroMs = outroMs,
                            )
                        }.build(),
                    )
                }.build()
            }
        }.awaitAll()
    }

    /** 计算来源前缀的目录级 key，与 intro/outro 逻辑一致 */
    suspend fun computeDirKey(mediaId: String): String {
        val video = mediaRepository.getVideoByUri(mediaId)
        return when {
            mediaId.startsWith("content://") || mediaId.startsWith("file://") -> {
                val localPath = video?.parentPath
                    ?: mediaId.substringAfter("file://").substringBeforeLast('/').takeIf { it.isNotEmpty() && it != mediaId }
                    ?: mediaId.substringBeforeLast('/').takeIf { it.isNotEmpty() && it != mediaId }
                if (!localPath.isNullOrEmpty()) "local:$localPath" else ""
            }
            mediaId.startsWith("cloud:") -> {
                val uri = android.net.Uri.parse(mediaId)
                val folder = CloudUriScheme.getCloudFolder(uri)
                val provider = CloudUriScheme.getProvider(uri) ?: ""
                if (!folder.isNullOrEmpty()) {
                    "cloud:$provider/$folder"
                } else {
                    val cloudKey = mediaId.substringBeforeLast('/').takeIf { it.isNotEmpty() && it != mediaId }
                    if (!cloudKey.isNullOrEmpty()) "cloud:$cloudKey" else ""
                }
            }
            else -> {
                mediaId.substringBeforeLast('/').takeIf { it.isNotEmpty() && it != mediaId }
                    ?.let { "other:$it" } ?: ""
            }
        }
    }

    /** 加载媒体项封面缩略图，返回磁盘缓存的 Uri（无则 null）。 */
    suspend fun loadArtworkForMediaItem(mediaItem: MediaItem): Uri? = withContext(Dispatchers.IO) {
        val uri = mediaItem.mediaId.toUri()
        return@withContext try {
            val request = ImageRequest.Builder(context)
                .data(uri)
                .size(512, 512)
                .build()
            imageLoader.execute(request)
            val diskCache = imageLoader.diskCache ?: return@withContext null
            return@withContext diskCache.openSnapshot(uri.toString())?.use { snapshot ->
                snapshot.data.toFile().toUri()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 默认占位封面（Android resource URI）。 */
    fun getDefaultArtworkUri(): Uri = Uri.Builder().apply {
        val defaultArtwork = R.drawable.artwork_default
        scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        authority(context.resources.getResourcePackageName(defaultArtwork))
        appendPath(context.resources.getResourceTypeName(defaultArtwork))
        appendPath(context.resources.getResourceEntryName(defaultArtwork))
    }.build()

    /** 为 URI 获取可读的标题，对 cloud:// URI 做特殊处理。 */
    private fun getTitleForUri(uri: Uri): String {
        // 云盘 URI：尝试从 CloudPlaylistCache 获取缓存的视频名
        if (CloudUriScheme.isCloudUri(uri)) {
            val provider = CloudUriScheme.getProvider(uri)
            val fileId = CloudUriScheme.getFileId(uri)
            if (provider != null && fileId != null) {
                val metadata = CloudPlaylistCache.getFileMetadata(provider, fileId)
                if (metadata != null) {
                    return metadata.fileName
                }
                // fallback: provider/fileId
                return "$provider/$fileId"
            }
        }
        // 普通 URI：用 getFilenameFromUri
        return context.getFilenameFromUri(uri)
    }
}
