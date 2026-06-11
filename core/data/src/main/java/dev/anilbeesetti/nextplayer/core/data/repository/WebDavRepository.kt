package dev.anilbeesetti.nextplayer.core.data.repository

import dev.anilbeesetti.nextplayer.core.model.WebDavResource
import dev.anilbeesetti.nextplayer.core.model.WebDavServer
import kotlinx.coroutines.flow.Flow

interface WebDavRepository {

    val servers: Flow<List<WebDavServer>>

    val activeServers: Flow<List<WebDavServer>>

    suspend fun addServer(server: WebDavServer)

    suspend fun updateServer(server: WebDavServer)

    suspend fun deleteServer(id: String)

    suspend fun toggleActive(id: String)

    /**
     * List files/directories at the given path on the specified WebDAV server.
     */
    suspend fun listDirectory(
        baseUrl: String,
        path: String,
        authHeader: String,
    ): Result<List<WebDavResource>>

    /**
     * Test connection to a WebDAV server.
     */
    suspend fun testConnection(
        baseUrl: String,
        username: String,
        password: String,
    ): Result<Boolean>

    suspend fun createFolder(baseUrl: String, path: String, authHeader: String): Result<Unit>

    suspend fun delete(baseUrl: String, path: String, authHeader: String): Result<Unit>

    suspend fun move(baseUrl: String, sourcePath: String, destinationPath: String, authHeader: String): Result<Unit>

    suspend fun copy(baseUrl: String, sourcePath: String, destinationPath: String, authHeader: String): Result<Unit>
}
