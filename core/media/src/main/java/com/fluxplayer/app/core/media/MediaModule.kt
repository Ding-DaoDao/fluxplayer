package com.fluxplayer.app.core.media

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.request.CachePolicy
import coil3.request.crossfade
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.fluxplayer.app.core.media.services.LocalMediaService
import com.fluxplayer.app.core.media.services.MediaService
import com.fluxplayer.app.core.media.sync.LocalMediaInfoSynchronizer
import com.fluxplayer.app.core.media.sync.LocalMediaSynchronizer
import com.fluxplayer.app.core.media.sync.MediaInfoSynchronizer
import com.fluxplayer.app.core.media.sync.MediaSynchronizer
import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.model.ThumbnailGenerationStrategy
import javax.inject.Singleton
import okio.FileSystem

@Module
@InstallIn(SingletonComponent::class)
interface MediaModule {

    @Binds
    @Singleton
    fun bindsMediaSynchronizer(
        mediaSynchronizer: LocalMediaSynchronizer,
    ): MediaSynchronizer

    @Binds
    @Singleton
    fun bindsMediaInfoSynchronizer(
        mediaInfoSynchronizer: LocalMediaInfoSynchronizer,
    ): MediaInfoSynchronizer

    @Binds
    @Singleton
    fun bindMediaService(
        mediaService: LocalMediaService,
    ): MediaService
}
