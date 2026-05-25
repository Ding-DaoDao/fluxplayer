package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

@Serializable
data class WebDavServer(
    val id: String = "",
    val name: String,
    val url: String,
    val username: String,
    val password: String,
    val isActive: Boolean = false,
) {
    /**
     * Base64 encoded Basic auth header value.
     */
    val basicAuthHeader: String
        get() = "Basic " + java.util.Base64.getEncoder().encodeToString(
            "$username:$password".toByteArray(),
        )

    /**
     * Normalized base URL with trailing slash removed.
     */
    val normalizedUrl: String
        get() = url.trimEnd('/')
}
