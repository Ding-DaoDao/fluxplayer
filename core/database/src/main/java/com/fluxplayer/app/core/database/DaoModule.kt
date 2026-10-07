package com.fluxplayer.app.core.database

import com.fluxplayer.app.core.database.dao.DirectoryDao
import com.fluxplayer.app.core.database.dao.DownloadTaskDao
import com.fluxplayer.app.core.database.dao.MediumDao
import com.fluxplayer.app.core.database.dao.PlaybackHistoryDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object DaoModule {

    @Provides
    fun provideMediumDao(db: MediaDatabase): MediumDao = db.mediumDao()

    @Provides
    fun provideMediumStateDao(db: MediaDatabase) = db.mediumStateDao()

    @Provides
    fun provideDirectoryDao(db: MediaDatabase): DirectoryDao = db.directoryDao()

    @Provides
    fun providePlaybackHistoryDao(db: MediaDatabase): PlaybackHistoryDao = db.playbackHistoryDao()

    @Provides
    fun provideDownloadTaskDao(db: MediaDatabase): DownloadTaskDao = db.downloadTaskDao()

    @Provides
    fun provideAudiobookProgressDao(db: MediaDatabase) = db.audiobookProgressDao()
}
