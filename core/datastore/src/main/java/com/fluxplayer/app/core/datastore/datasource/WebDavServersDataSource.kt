package com.fluxplayer.app.core.datastore.datasource

import androidx.datastore.core.DataStore
import com.fluxplayer.app.core.common.Logger
import com.fluxplayer.app.core.model.WebDavServers
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

class WebDavServersDataSource @Inject constructor(
    private val webDavServersDataStore: DataStore<WebDavServers>,
) {

    companion object {
        private const val TAG = "WebDavServersDataSource"
    }

    val webDavServers: Flow<WebDavServers> = webDavServersDataStore.data

    /** 备份恢复必须向调用方报告写入失败，避免显示虚假的成功结果。 */
    suspend fun updateOrThrow(transform: suspend (WebDavServers) -> WebDavServers) {
        webDavServersDataStore.updateData(transform)
    }

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
