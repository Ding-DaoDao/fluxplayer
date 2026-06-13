package com.fluxplayer.app.core.data

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.fluxplayer.app.core.common.CustomDownloadManager
import com.fluxplayer.app.core.database.dao.DownloadTaskDao
import com.fluxplayer.app.core.data.repository.CloudDownloadRepository
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DownloadModule {

    @Singleton
    @Provides
    fun provideCloudDownloadRepository(
        downloadTaskDao: DownloadTaskDao,
        customDownloadManager: CustomDownloadManager,
        preferencesRepository: PreferencesRepository,
    ): CloudDownloadRepository = CloudDownloadRepository(
        downloadTaskDao = downloadTaskDao,
        customDownloadManager = customDownloadManager,
        preferencesRepository = preferencesRepository,
    )
}
