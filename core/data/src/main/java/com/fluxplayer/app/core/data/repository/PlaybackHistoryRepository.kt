package com.fluxplayer.app.core.data.repository

import com.fluxplayer.app.core.model.PlaybackHistory
import com.fluxplayer.app.core.model.VideoSource
import kotlinx.coroutines.flow.Flow

interface PlaybackHistoryRepository {

    fun getHistoryFlow(): Flow<List<PlaybackHistory>>

    /** 足迹和已播放标记使用完整历史，不受显示上限和同名合并影响。 */
    fun getAllHistoryFlow(): Flow<List<PlaybackHistory>> = getHistoryFlow()

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
