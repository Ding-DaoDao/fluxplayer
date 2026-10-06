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

internal data class SourceCover(val reference: String)

/** 先交给图片加载器读取内存和磁盘，缺图时才访问书源解析封面。 */
internal class SourceCoverFetcher(private val resolve: suspend () -> ListeningResource?) : Fetcher.Factory<SourceCover> {
    override fun create(data: SourceCover, options: Options, imageLoader: ImageLoader): Fetcher = Fetcher {
        val disk = imageLoader.diskCache
        val key = options.diskCacheKey
        if (options.diskCachePolicy.readEnabled && disk != null && key != null) {
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
        val headers = NetworkHeaders.Builder().apply { resource.headers.forEach { (name, value) -> set(name, value) } }.build()
        val resolvedOptions = options.copy(extras = options.extras.newBuilder().apply { set(Extras.Key.httpHeaders, headers) }.build())
        val mapped = imageLoader.components.map(resource.url, resolvedOptions)
        val fetcher = imageLoader.components.newFetcher(mapped, resolvedOptions, imageLoader)?.first
            ?: throw IOException("无法加载书源封面")
        fetcher.fetch()
    }
}
