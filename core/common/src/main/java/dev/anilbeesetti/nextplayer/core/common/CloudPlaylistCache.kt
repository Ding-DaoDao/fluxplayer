package dev.anilbeesetti.nextplayer.core.common

object CloudPlaylistCache {
    data class FileMetadata(
        val fileName: String,
        val etag: String? = null,
        val size: Long? = null,
        val s3keyFlag: String? = null,
        val downloadUrl: String? = null,
        val parentPath: String? = null,
    )

    private data class CachedUrl(val url: String, val timestamp: Long)

    private val resolvedUrls = mutableMapOf<String, MutableMap<String, CachedUrl>>()
    private val fileMetadata = mutableMapOf<String, MutableMap<String, FileMetadata>>()
    private const val URL_TTL_MS = 15 * 60 * 1000L  // 15 分钟

    fun getResolvedUrl(provider: String, fileId: String): String? {
        val entry = resolvedUrls[provider]?.get(fileId) ?: return null
        if (System.currentTimeMillis() - entry.timestamp > URL_TTL_MS) {
            resolvedUrls[provider]?.remove(fileId)
            return null
        }
        return entry.url
    }

    fun putResolvedUrl(provider: String, fileId: String, url: String) {
        resolvedUrls.getOrPut(provider) { mutableMapOf() }[fileId] =
            CachedUrl(url, System.currentTimeMillis())
    }

    fun getFileMetadata(provider: String, fileId: String): FileMetadata? {
        return fileMetadata[provider]?.get(fileId)
    }

    fun putFileMetadata(provider: String, fileId: String, metadata: FileMetadata) {
        fileMetadata.getOrPut(provider) { mutableMapOf() }[fileId] = metadata
    }

    fun clear() {
        resolvedUrls.clear()
        fileMetadata.clear()
    }
}
