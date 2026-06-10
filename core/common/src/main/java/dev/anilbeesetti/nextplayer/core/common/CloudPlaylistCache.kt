package dev.anilbeesetti.nextplayer.core.common

object CloudPlaylistCache {
    data class FileMetadata(
        val fileName: String,
        val etag: String? = null,
        val size: Long? = null,
        val s3keyFlag: String? = null,
        val downloadUrl: String? = null
    )

    private val resolvedUrls = mutableMapOf<String, MutableMap<String, String>>()
    private val fileMetadata = mutableMapOf<String, MutableMap<String, FileMetadata>>()

    fun getResolvedUrl(provider: String, fileId: String): String? {
        return resolvedUrls[provider]?.get(fileId)
    }

    fun putResolvedUrl(provider: String, fileId: String, url: String) {
        resolvedUrls.getOrPut(provider) { mutableMapOf() }[fileId] = url
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
