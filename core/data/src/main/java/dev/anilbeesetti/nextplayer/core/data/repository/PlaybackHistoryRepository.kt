package dev.anilbeesetti.nextplayer.core.data.repository

import dev.anilbeesetti.nextplayer.core.model.PlaybackHistory
import dev.anilbeesetti.nextplayer.core.model.VideoSource
import kotlinx.coroutines.flow.Flow

interface PlaybackHistoryRepository {

    fun getHistoryFlow(): Flow<List<PlaybackHistory>>

    suspend fun recordPlayback(
        uriString: String,
        title: String,
        source: VideoSource,
        position: Long,
        duration: Long,
        originalUriString: String? = null,
        thumbnailPath: String? = null,
        parentPath: String? = null,
    )

    suspend fun isPlayed(uriString: String): Boolean

    suspend fun deleteItem(uriString: String)

    suspend fun clearAll()
}
