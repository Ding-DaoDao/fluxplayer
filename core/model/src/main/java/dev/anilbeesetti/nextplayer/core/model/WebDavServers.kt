package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

@Serializable
data class WebDavServers(
    val servers: List<WebDavServer> = emptyList(),
) {
    fun addServer(server: WebDavServer): WebDavServers {
        return copy(servers = servers + server)
    }

    fun updateServer(server: WebDavServer): WebDavServers {
        return copy(
            servers = servers.map { if (it.id == server.id) server else it }
        )
    }

    fun removeServer(id: String): WebDavServers {
        return copy(servers = servers.filter { it.id != id })
    }

    fun toggleActive(id: String): WebDavServers {
        return copy(
            servers = servers.map {
                if (it.id == id) it.copy(isActive = !it.isActive) else it
            }
        )
    }

    val activeServers: List<WebDavServer>
        get() = servers.filter { it.isActive }
}
