package dev.anilbeesetti.nextplayer.core.data.danmaku

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * 弹幕下载缓存管理器。
 *
 * 弹幕文件下载到 app 私有缓存目录 `danmaku_cache/` 中，
 * 以 episodeId 命名，格式为 Bilibili XML。
 */
class DanmakuDownloadManager(private val context: Context) {

    companion object {
        private const val TAG = "DanmakuDownloadManager"
        private const val CACHE_DIR = "danmaku_cache"
    }

    private val cacheDir: File
        get() = File(context.cacheDir, CACHE_DIR).also { it.mkdirs() }

    /**
     * 获取缓存文件路径。
     * @param episodeId 剧集 ID
     * @return 缓存文件，可能不存在
     */
    fun getCacheFile(episodeId: Int): File {
        return File(cacheDir, "$episodeId.xml")
    }

    /**
     * 检查指定 episodeId 的弹幕是否已缓存。
     */
    fun isCached(episodeId: Int): Boolean {
        return getCacheFile(episodeId).exists()
    }

    /**
     * 获取缓存的 content URI（用于 DanmakuParser）。
     * @return content URI，未缓存时返回 null
     */
    fun getCacheUri(episodeId: Int): Uri? {
        val file = getCacheFile(episodeId)
        return if (file.exists()) Uri.fromFile(file) else null
    }

    /**
     * 将弹幕 XML 写入缓存文件。
     * @param episodeId 剧集 ID
     * @param xmlBytes XML 内容的字节数组
     * @return 写入的文件
     */
    fun saveToCache(episodeId: Int, xmlBytes: ByteArray): File {
        val file = getCacheFile(episodeId)
        file.parentFile?.mkdirs()
        file.writeBytes(xmlBytes)
        Log.d(TAG, "Cached danmaku to ${file.absolutePath} (${xmlBytes.size} bytes)")
        return file
    }

    /**
     * 保存来自 API 的 XML 弹幕流。
     */
    fun saveFromStream(episodeId: Int, inputStream: java.io.InputStream): File {
        val bytes = inputStream.readBytes()
        return saveToCache(episodeId, bytes)
    }

    /**
     * 删除指定 episodeId 的缓存。
     */
    fun deleteCache(episodeId: Int) {
        getCacheFile(episodeId).delete()
    }

    /**
     * 清空所有弹幕缓存。
     */
    fun clearAll() {
        cacheDir.listFiles()?.forEach { it.delete() }
        Log.d(TAG, "Cleared all danmaku cache")
    }

    /**
     * 获取缓存占用大小（字节）。
     */
    fun getCacheSize(): Long {
        return cacheDir.walk().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * 获取已缓存的 episodeId 列表。
     */
    fun getCachedEpisodeIds(): List<Int> {
        return cacheDir.listFiles()
            ?.mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            ?: emptyList()
    }
}
