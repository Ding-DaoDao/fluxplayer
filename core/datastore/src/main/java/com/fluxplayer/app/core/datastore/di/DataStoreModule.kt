package com.fluxplayer.app.core.datastore.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.fluxplayer.app.core.common.Dispatcher
import com.fluxplayer.app.core.common.NextDispatchers
import com.fluxplayer.app.core.common.di.ApplicationScope
import com.fluxplayer.app.core.datastore.serializer.ApplicationPreferencesSerializer
import com.fluxplayer.app.core.datastore.serializer.PlayerPreferencesSerializer
import com.fluxplayer.app.core.datastore.serializer.SearchHistorySerializer
import com.fluxplayer.app.core.datastore.serializer.WebDavServersSerializer
import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.model.PlayerPreferences
import com.fluxplayer.app.core.model.SearchHistory
import com.fluxplayer.app.core.model.WebDavServers
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

private const val APP_PREFERENCES_DATASTORE_FILE = "app_preferences.json"
private const val PLAYER_PREFERENCES_DATASTORE_FILE = "player_preferences.json"
private const val SEARCH_HISTORY_DATASTORE_FILE = "search_history.json"
private const val WEBDAV_SERVERS_DATASTORE_FILE = "webdav_servers.json"

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun provideAppPreferencesDataStore(
        @ApplicationContext context: Context,
        @Dispatcher(NextDispatchers.IO) ioDispatcher: CoroutineDispatcher,
        @ApplicationScope scope: CoroutineScope,
    ): DataStore<ApplicationPreferences> {
        return DataStoreFactory.create(
            serializer = ApplicationPreferencesSerializer,
            scope = CoroutineScope(scope.coroutineContext + ioDispatcher),
            produceFile = { context.dataStoreFile(APP_PREFERENCES_DATASTORE_FILE) },
        )
    }

    @Provides
    @Singleton
    fun providePlayerPreferencesDataStore(
        @ApplicationContext applicationContext: Context,
        @Dispatcher(NextDispatchers.IO) ioDispatcher: CoroutineDispatcher,
        @ApplicationScope scope: CoroutineScope,
    ): DataStore<PlayerPreferences> {
        return DataStoreFactory.create(
            serializer = PlayerPreferencesSerializer,
            scope = CoroutineScope(scope.coroutineContext + ioDispatcher),
            produceFile = { applicationContext.dataStoreFile(PLAYER_PREFERENCES_DATASTORE_FILE) },
        )
    }

    @Provides
    @Singleton
    fun provideSearchHistoryDataStore(
        @ApplicationContext applicationContext: Context,
        @Dispatcher(NextDispatchers.IO) ioDispatcher: CoroutineDispatcher,
        @ApplicationScope scope: CoroutineScope,
    ): DataStore<SearchHistory> {
        return DataStoreFactory.create(
            serializer = SearchHistorySerializer,
            scope = CoroutineScope(scope.coroutineContext + ioDispatcher),
            produceFile = { applicationContext.dataStoreFile(SEARCH_HISTORY_DATASTORE_FILE) },
        )
    }

    @Provides
    @Singleton
    fun provideWebDavServersDataStore(
        @ApplicationContext applicationContext: Context,
        @Dispatcher(NextDispatchers.IO) ioDispatcher: CoroutineDispatcher,
        @ApplicationScope scope: CoroutineScope,
    ): DataStore<WebDavServers> {
        return DataStoreFactory.create(
            serializer = WebDavServersSerializer,
            scope = CoroutineScope(scope.coroutineContext + ioDispatcher),
            produceFile = { applicationContext.dataStoreFile(WEBDAV_SERVERS_DATASTORE_FILE) },
        )
    }
}
