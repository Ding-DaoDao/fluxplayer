package com.fluxplayer.app.core.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.fluxplayer.app.core.data.repository.LocalMediaRepository
import com.fluxplayer.app.core.data.repository.LocalPreferencesRepository
import com.fluxplayer.app.core.data.repository.LocalSearchHistoryRepository
import com.fluxplayer.app.core.data.repository.MediaRepository
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.data.repository.SearchHistoryRepository
import com.fluxplayer.app.core.data.repository.DanmakuRepository
import com.fluxplayer.app.core.data.repository.LocalDanmakuRepository
import com.fluxplayer.app.core.data.repository.LocalPlaybackHistoryRepository
import com.fluxplayer.app.core.data.repository.LocalWebDavRepository
import com.fluxplayer.app.core.data.repository.PlaybackHistoryRepository
import com.fluxplayer.app.core.data.repository.WebDavRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface DataModule {

    @Binds
    fun bindsMediaRepository(
        videoRepository: LocalMediaRepository,
    ): MediaRepository

    @Binds
    @Singleton
    fun bindsPreferencesRepository(
        preferencesRepository: LocalPreferencesRepository,
    ): PreferencesRepository

    @Binds
    @Singleton
    fun bindsSearchHistoryRepository(
        searchHistoryRepository: LocalSearchHistoryRepository,
    ): SearchHistoryRepository

    @Binds
    @Singleton
    fun bindsDanmakuRepository(
        danmakuRepository: LocalDanmakuRepository,
    ): DanmakuRepository

    @Binds
    @Singleton
    fun bindsWebDavRepository(
        webDavRepository: LocalWebDavRepository,
    ): WebDavRepository

    @Binds
    @Singleton
    fun bindsPlaybackHistoryRepository(
        playbackHistoryRepository: LocalPlaybackHistoryRepository,
    ): PlaybackHistoryRepository
}
