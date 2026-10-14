package com.fluxplayer.app.feature.tingshu

import coil3.Extras
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.Options
import com.fluxplayer.app.core.tingshu.ListeningResource
import java.io.IOException

internal data class SourceCover(val reference: String, val forceRefresh: Boolean = false, val sourceId: String = "")

/** 先交给图片加载器读取内存和磁盘，缺图时才访问书源解析封面。 */
internal class SourceCoverFetcher(private val resolve: suspend () -> ListeningResource?) : Fetcher.Factory<SourceCover> {
    override fun create(data: SourceCover, options: Options, imageLoader: ImageLoader): Fetcher = Fetcher {
        if (!data.forceRefresh) {
            com.fluxplayer.app.core.tingshu.ListeningCoverStore.restoredFile(options.context, data.sourceId, data.reference)?.let { file ->
                val mapped = imageLoader.components.map(file, options)
                return@Fetcher imageLoader.components.newFetcher(mapped, options, imageLoader)!!.first.fetch()
            }
        }
        val disk = imageLoader.diskCache
        val key = options.diskCacheKey
        if (!data.forceRefresh && options.diskCachePolicy.readEnabled && disk != null && key != null) {
            disk.openSnapshot(key)?.let { snapshot ->
                return@Fetcher SourceFetchResult(
                    ImageSource(snapshot.data, disk.fileSystem, key, snapshot),
                    mimeType = null,
                    dataSource = DataSource.DISK,
                )
            }
        }
        if (!options.networkCachePolicy.readEnabled) throw IOException("封面未缓存")
        val resource = resolve() ?: throw IOException("书源没有返回封面")
        val headers = NetworkHeaders.Builder().apply {
            resource.headers.forEach { (name, value) -> set(name, value) }
            if (data.forceRefresh) set("Cache-Control", "no-cache")
        }.build()
        val resolvedOptions = options.copy(extras = options.extras.newBuilder().apply { set(Extras.Key.httpHeaders, headers) }.build())
        val mapped = imageLoader.components.map(resource.url, resolvedOptions)
        val fetcher = imageLoader.components.newFetcher(mapped, resolvedOptions, imageLoader)?.first
            ?: throw IOException("无法加载书源封面")
        val result = fetcher.fetch()
        if (data.forceRefresh && result != null) {
            // 主动刷新成功后，后续读取使用新缓存，不再被旧备份封面覆盖。
            com.fluxplayer.app.core.tingshu.ListeningCoverStore.restoredFile(options.context, data.sourceId, data.reference)?.delete()
        }
        result
    }
}
