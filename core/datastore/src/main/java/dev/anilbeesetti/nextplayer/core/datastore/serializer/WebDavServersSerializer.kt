package dev.anilbeesetti.nextplayer.core.datastore.serializer

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import dev.anilbeesetti.nextplayer.core.model.WebDavServers
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

object WebDavServersSerializer : Serializer<WebDavServers> {

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    override val defaultValue: WebDavServers
        get() = WebDavServers()

    override suspend fun readFrom(input: InputStream): WebDavServers {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return defaultValue
        val string = bytes.decodeToString().trim()
        if (string.isEmpty() || !string.startsWith("{")) return defaultValue
        try {
            return jsonFormat.decodeFromString(
                deserializer = WebDavServers.serializer(),
                string = string,
            )
        } catch (exception: SerializationException) {
            throw CorruptionException("Cannot read datastore", exception)
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    override suspend fun writeTo(t: WebDavServers, output: OutputStream) {
        output.write(
            jsonFormat.encodeToString(
                serializer = WebDavServers.serializer(),
                value = t,
            ).encodeToByteArray(),
        )
    }
}
