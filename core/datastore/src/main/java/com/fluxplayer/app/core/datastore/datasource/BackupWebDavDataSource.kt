package com.fluxplayer.app.core.datastore.datasource

import androidx.datastore.core.DataStore
import com.fluxplayer.app.core.common.Logger
import com.fluxplayer.app.core.model.BackupWebDavConfig
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

class BackupWebDavDataSource @Inject constructor(
    private val backupWebDavDataStore: DataStore<BackupWebDavConfig>,
) {

    companion object {
        private const val TAG = "BackupWebDavDataSource"
    }

    val config: Flow<BackupWebDavConfig> = backupWebDavDataStore.data

    suspend fun update(
        transform: suspend (BackupWebDavConfig) -> BackupWebDavConfig,
    ) {
        try {
            backupWebDavDataStore.updateData(transform)
        } catch (ioException: Exception) {
            Logger.logError(TAG, "Failed to update backup WebDAV config: $ioException")
        }
    }
}
