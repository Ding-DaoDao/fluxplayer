package com.fluxplayer.app.core.datastore.serializer

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.fluxplayer.app.core.model.SearchHistory
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

object SearchHistorySerializer : Serializer<SearchHistory> {

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    override val defaultValue: SearchHistory
        get() = SearchHistory()

    override suspend fun readFrom(input: InputStream): SearchHistory {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return defaultValue
        val string = bytes.decodeToString().trim()
        if (string.isEmpty() || !string.startsWith("{")) return defaultValue
        try {
            return jsonFormat.decodeFromString(
                deserializer = SearchHistory.serializer(),
                string = string,
            )
        } catch (exception: SerializationException) {
            throw CorruptionException("Cannot read datastore", exception)
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    override suspend fun writeTo(t: SearchHistory, output: OutputStream) {
        output.write(
            jsonFormat.encodeToString(
                serializer = SearchHistory.serializer(),
                value = t,
            ).encodeToByteArray(),
        )
    }
}
