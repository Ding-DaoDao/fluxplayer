package com.fluxplayer.app.core.data.repository

import com.fluxplayer.app.core.common.di.ApplicationScope
import com.fluxplayer.app.core.data.webdav.WebDavClient
import com.fluxplayer.app.core.datastore.datasource.WebDavServersDataSource
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.core.model.WebDavServer
import com.fluxplayer.app.core.model.WebDavServers
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Singleton
class LocalWebDavRepository @Inject constructor(
    private val dataSource: WebDavServersDataSource,
    private val webDavClient: WebDavClient,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : WebDavRepository {

    private val webDavServers: StateFlow<WebDavServers> =
        dataSource.webDavServers.stateIn(
            scope = applicationScope,
            started = SharingStarted.Eagerly,
            initialValue = WebDavServers(),
        )

    override val servers: Flow<List<WebDavServer>>
        get() = webDavServers.map { it.servers }

    override val activeServers: Flow<List<WebDavServer>>
        get() = webDavServers.map { it.activeServers }

    override suspend fun addServer(server: WebDavServer) {
        dataSource.update { it.addServer(server) }
    }

    override suspend fun updateServer(server: WebDavServer) {
        dataSource.update { it.updateServer(server) }
    }

    override suspend fun deleteServer(id: String) {
        dataSource.update { it.removeServer(id) }
    }

    override suspend fun toggleActive(id: String) {
        dataSource.update { it.toggleActive(id) }
    }

    override suspend fun listDirectory(
        baseUrl: String,
        path: String,
        authHeader: String,
    ): Result<List<WebDavResource>> {
        return webDavClient.listDirectory(baseUrl, path, authHeader)
    }

    override suspend fun testConnection(
        baseUrl: String,
        username: String,
        password: String,
    ): Result<Boolean> {
        return webDavClient.testConnection(baseUrl, username, password)
    }

    override suspend fun createFolder(baseUrl: String, path: String, authHeader: String): Result<Unit> {
        return webDavClient.createFolder(baseUrl, path, authHeader)
    }

    override suspend fun delete(baseUrl: String, path: String, authHeader: String): Result<Unit> {
        return webDavClient.delete(baseUrl, path, authHeader)
    }

    override suspend fun move(baseUrl: String, sourcePath: String, destinationPath: String, authHeader: String): Result<Unit> {
        return webDavClient.move(baseUrl, sourcePath, destinationPath, authHeader)
    }

    override suspend fun copy(baseUrl: String, sourcePath: String, destinationPath: String, authHeader: String): Result<Unit> {
        return webDavClient.copy(baseUrl, sourcePath, destinationPath, authHeader)
    }
}
