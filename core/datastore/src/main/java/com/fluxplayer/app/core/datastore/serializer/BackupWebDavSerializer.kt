package com.fluxplayer.app.core.datastore.serializer

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.fluxplayer.app.core.model.BackupWebDavConfig
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

object BackupWebDavSerializer : Serializer<BackupWebDavConfig> {

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    override val defaultValue: BackupWebDavConfig
        get() = BackupWebDavConfig()

    override suspend fun readFrom(input: InputStream): BackupWebDavConfig {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return defaultValue
        val string = bytes.decodeToString().trim()
        if (string.isEmpty() || !string.startsWith("{")) return defaultValue
        try {
            return jsonFormat.decodeFromString(
                deserializer = BackupWebDavConfig.serializer(),
                string = string,
            )
        } catch (exception: SerializationException) {
            throw CorruptionException("Cannot read backup webdav config", exception)
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    override suspend fun writeTo(t: BackupWebDavConfig, output: OutputStream) {
        output.write(
            jsonFormat.encodeToString(
                serializer = BackupWebDavConfig.serializer(),
                value = t,
            ).encodeToByteArray(),
        )
    }
}
