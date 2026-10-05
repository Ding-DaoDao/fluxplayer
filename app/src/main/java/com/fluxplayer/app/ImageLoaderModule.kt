package com.fluxplayer.app

import android.content.Context
import android.net.Uri
import android.util.Log
import coil3.EventListener
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.crossfade
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.model.ThumbnailGenerationStrategy
import okio.FileSystem
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ImageLoaderModule {

    private const val TAG = "CoverImageLoader"

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        preferencesRepository: PreferencesRepository,
    ): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(
                    VideoThumbnailDecoder.Factory(
                        thumbnailStrategy = {
                            val preferences = preferencesRepository.applicationPreferences.value
                            when (preferences.thumbnailGenerationStrategy) {
                                ThumbnailGenerationStrategy.FIRST_FRAME -> ThumbnailStrategy.FirstFrame
                                ThumbnailGenerationStrategy.FRAME_AT_PERCENTAGE -> ThumbnailStrategy.FrameAtPercentage(preferences.thumbnailFramePosition)
                                ThumbnailGenerationStrategy.HYBRID -> ThumbnailStrategy.Hybrid(preferences.thumbnailFramePosition)
                            }
                        },
                    ),
                )
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache(
                DiskCache.Builder()
                    .fileSystem(FileSystem.SYSTEM)
                    // 云盘封面独立目录：原先与本地视频帧共用 thumbnails 目录，
                    // 视频封面一多就会 LRU 淘汰云盘封面，导致回看时重新走网络（表现为加载慢）。
                    .directory(context.filesDir.resolve("cloud_covers"))
                    .maxSizePercent(0.05)
                    .build(),
            )
            .memoryCache {
                // 与 Coil 默认值一致（STANDARD_MEMORY_MULTIPLIER = 0.2，低内存设备 0.15）。
                // 显式写出是为了让意图明确：封面滚动是内存缓存收益最高的场景。
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.2)
                    .strongReferencesEnabled(true)
                    .build()
            }
            // 封面加载埋点：此前 placeholder/error/fallback 三态同图且零日志，
            // 导致「加载中/加载失败/本来没封面」在界面上无法区分，线上也无法定位失败原因。
            .eventListener(
                object : EventListener() {
                    // EventListener 实例每次请求都新建（ImageLoader 会缓存它），
                    // 用起始时间戳测总耗时，区分是网络慢还是磁盘/解码慢。
                    private var startAt = 0L

                    override fun onStart(request: ImageRequest) {
                        startAt = System.nanoTime()
                    }

                    override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                        val ms = (System.nanoTime() - startAt) / 1_000_000
                        val host = hostOf(request)
                        val line = "封面OK ${ms}ms source=${result.dataSource} host=$host"
                        if (ms >= SLOW_REQUEST_MS) Log.w(TAG, line) else Log.d(TAG, line)
                    }

                    override fun onError(request: ImageRequest, result: ErrorResult) {
                        val t = result.throwable
                        val reason = if (t is java.io.IOException) "网络错误 ${t.message}" else "${t::class.java.simpleName} ${t.message}"
                        Log.w(TAG, "封面失败 host=${hostOf(request)} reason=$reason")
                    }
                },
            )
            .crossfade(true)
            .build()
    }

    /** 慢加载阈值（毫秒），超过则打 warn 便于 adb logcat 过滤 */
    private const val SLOW_REQUEST_MS = 800L

    private fun hostOf(request: ImageRequest): String =
        (request.data as? String)?.let { Uri.parse(it).host } ?: "?"
}