package dev.anilbeesetti.nextplayer.core.datastore.datasource

import androidx.datastore.core.DataStore
import dev.anilbeesetti.nextplayer.core.common.Logger
import dev.anilbeesetti.nextplayer.core.model.WebDavServers
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

class WebDavServersDataSource @Inject constructor(
    private val webDavServersDataStore: DataStore<WebDavServers>,
) {

    companion object {
        private const val TAG = "WebDavServersDataSource"
    }

    val webDavServers: Flow<WebDavServers> = webDavServersDataStore.data

    suspend fun update(
        transform: suspend (WebDavServers) -> WebDavServers,
    ) {
        try {
            webDavServersDataStore.updateData(transform)
        } catch (ioException: Exception) {
            Logger.logError(TAG, "Failed to update WebDAV servers: $ioException")
        }
    }
}
