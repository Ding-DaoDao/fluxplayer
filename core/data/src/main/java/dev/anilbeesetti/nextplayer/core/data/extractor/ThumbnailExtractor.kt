package dev.anilbeesetti.nextplayer.core.data.extractor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.anilbeesetti.nextplayer.core.data.openlist.OpenListTokenProvider
import dev.anilbeesetti.nextplayer.core.model.VideoSource
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 从视频文件中提取一帧作为缩略图，保存到本地缓存。
 *
 * 支持三种来源：
 * - [VideoSource.LOCAL] — [MediaMetadataRetriever.setDataSource] 直接读取
 * - [VideoSource.WEBDAV] — 解析 URI 中 userinfo 为 Basic Auth header
 * - [VideoSource.OPENLIST] — 使用 [OpenListTokenProvider] 的 Bearer token
 */
@Singleton
class ThumbnailExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 提取视频帧并保存到本地文件。
     *
     * @param uriString 视频 URI
     * @param source 视频来源
     * @param positionMs 提取帧的位置（毫秒）
     * @return 保存的缩略图文件路径，失败返回 null
     */
    suspend fun extract(
        uriString: String,
        source: VideoSource,
        positionMs: Long,
    ): String? = withContext(Dispatchers.IO) {
        val thumbnailsDir = getThumbnailsDir()
        val outputFile = File(thumbnailsDir, "${hashUri(uriString)}.jpg")

        // 如果已有缓存且文件有效，直接返回
        if (outputFile.exists() && outputFile.length() > 0) {
            return@withContext outputFile.absolutePath
        }

        val retriever = MediaMetadataRetriever()
        try {
            when (source) {
                VideoSource.LOCAL -> {
                    retriever.setDataSource(context, Uri.parse(uriString))
                }
                VideoSource.WEBDAV -> {
                    val uri = Uri.parse(uriString)
                    val userInfo = uri.userInfo ?: return@withContext null
                    val encoded = Base64.encodeToString(
                        userInfo.toByteArray(Charsets.UTF_8),
                        Base64.NO_WRAP,
                    )
                    val cleanUrl = stripUserInfo(uri)
                    retriever.setDataSource(cleanUrl, mapOf("Authorization" to "Basic $encoded"))
                }
                VideoSource.OPENLIST -> {
                    val token = OpenListTokenProvider.bearerToken
                    if (token == null) return@withContext null
                    retriever.setDataSource(uriString, mapOf("Authorization" to "Bearer $token"))
                }
                VideoSource.PAN123,
                VideoSource.QUARK,
                VideoSource.UC,
                VideoSource.ALIYUN,
                VideoSource.CLOUD189,
                VideoSource.YUN139,
                VideoSource.OTHER -> return@withContext null
            }

            val bitmap = retriever.getFrameAtTime(positionMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return@withContext null

            saveBitmap(bitmap, outputFile)
            outputFile.absolutePath
        } catch (e: Exception) {
            // 静默失败 — 缩略图不是关键功能
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * 直接将 Bitmap 保存为缩略图文件（用于 TextureView 截图）。
     *
     * @param uriString 视频 URI（用于生成文件名）
     * @param bitmap 要保存的 Bitmap
     * @return 保存的缩略图文件路径，失败返回 null
     */
    suspend fun saveDirect(
        uriString: String,
        bitmap: Bitmap,
    ): String? = withContext(Dispatchers.IO) {
        val thumbnailsDir = getThumbnailsDir()
        val baseName = hashUri(uriString)
        val timestamp = System.currentTimeMillis()
        val outputFile = File(thumbnailsDir, "${baseName}_${timestamp}.jpg")

        if (saveBitmap(bitmap, outputFile)) {
            // 清理同名旧文件
            thumbnailsDir.listFiles()?.filter {
                it.name.startsWith(baseName) && it.name != outputFile.name
            }?.forEach { it.delete() }
            outputFile.absolutePath
        } else {
            null
        }
    }

    /** 删除指定 URI 的缩略图缓存文件。 */
    fun deleteThumbnail(uriString: String) {
        val baseName = hashUri(uriString)
        getThumbnailsDir().listFiles()?.filter {
            it.name.startsWith(baseName)
        }?.forEach { it.delete() }
    }

    /** 删除所有缩略图缓存。 */
    fun clearAll() {
        getThumbnailsDir().listFiles()?.forEach { it.delete() }
    }

    private fun getThumbnailsDir(): File {
        val dir = File(context.filesDir, THUMBNAILS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 缩放大图（最长边 maxPx）后写入临时文件再原子重命名，避免残缺缓存。 */
    private fun saveBitmap(bitmap: Bitmap, file: File): Boolean {
        return try {
            val scaled = scaleDown(bitmap, MAX_THUMB_PX)
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            val success = FileOutputStream(tempFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)
            }
            if (success) {
                tempFile.renameTo(file)
            } else {
                tempFile.delete()
            }
            if (scaled !== bitmap) scaled.recycle()
            success
        } catch (_: Exception) {
            false
        }
    }

    /** 保持宽高比缩放 Bitmap，最长边不超过 maxPx。 */
    private fun scaleDown(bitmap: Bitmap, maxPx: Int): Bitmap {
        val (w, h) = bitmap.width to bitmap.height
        if (w <= maxPx && h <= maxPx) return bitmap
        val ratio = maxPx.toFloat() / maxOf(w, h)
        val newW = (w * ratio).toInt()
        val newH = (h * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    private fun hashUri(uri: String): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(uri.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }

    private fun stripUserInfo(uri: Uri): String {
        val authority = uri.host + if (uri.port != -1) ":${uri.port}" else ""
        return uri.buildUpon()
            .encodedAuthority(authority)
            .build()
            .toString()
    }

    companion object {
        private const val THUMBNAILS_DIR = "thumbnails"
        private const val THUMBNAIL_QUALITY = 85
        /** 缩略图最长边像素上限。 */
        private const val MAX_THUMB_PX = 640
    }
}
