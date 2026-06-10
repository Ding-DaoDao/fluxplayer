package dev.anilbeesetti.nextplayer.core.data

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.anilbeesetti.nextplayer.core.data.repository.LocalMediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.LocalPreferencesRepository
import dev.anilbeesetti.nextplayer.core.data.repository.LocalSearchHistoryRepository
import dev.anilbeesetti.nextplayer.core.data.repository.MediaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.data.repository.SearchHistoryRepository
import dev.anilbeesetti.nextplayer.core.data.repository.DanmakuRepository
import dev.anilbeesetti.nextplayer.core.data.repository.LocalDanmakuRepository
import dev.anilbeesetti.nextplayer.core.data.repository.LocalPlaybackHistoryRepository
import dev.anilbeesetti.nextplayer.core.data.repository.LocalWebDavRepository
import dev.anilbeesetti.nextplayer.core.data.repository.PlaybackHistoryRepository
import dev.anilbeesetti.nextplayer.core.data.repository.WebDavRepository
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
