package com.fluxplayer.app.core.data.webdav

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WebDavClientModule {

    @Provides
    @Singleton
    fun provideWebDavClient(): WebDavClient {
        return WebDavClient()
    }
}
