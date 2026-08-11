package com.fluxplayer.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.fluxplayer.app.core.database.entities.PlaybackHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackHistoryDao {

    @Upsert
    suspend fun upsert(history: PlaybackHistoryEntity)

    /**
     * 原子写入历史记录，且 thumbnail_path 只在传入非空时更新（null 保留现有值）。
     * 解决退出播放时多次 recordHistory 并发导致的缩略图被 null 覆盖问题。
     */
    @Query(
        """
        INSERT INTO playback_history (uri, title, source, last_played_time, playback_position, duration, original_uri, thumbnail_path, parent_path)
        VALUES (:uriString, :title, :source, :lastPlayedTime, :playbackPosition, :duration, :originalUriString, :thumbnailPath, :parentPath)
        ON CONFLICT(uri) DO UPDATE SET
            title = excluded.title,
            source = excluded.source,
            last_played_time = excluded.last_played_time,
            playback_position = excluded.playback_position,
            duration = excluded.duration,
            original_uri = excluded.original_uri,
            thumbnail_path = CASE
                WHEN excluded.thumbnail_path IS NOT NULL THEN excluded.thumbnail_path
                ELSE playback_history.thumbnail_path
            END,
            parent_path = excluded.parent_path
        """,
    )
    suspend fun upsertPreservingThumbnail(
        uriString: String,
        title: String,
        source: String,
        lastPlayedTime: Long,
        playbackPosition: Long,
        duration: Long,
        originalUriString: String?,
        thumbnailPath: String?,
        parentPath: String?,
    )

    @Query(
        """
        SELECT * FROM playback_history 
        ORDER BY last_played_time DESC
        """,
    )
    fun getAllAsFlow(): Flow<List<PlaybackHistoryEntity>>

    @Query(
        """
        SELECT * FROM playback_history 
        ORDER BY last_played_time DESC
        """,
    )
    suspend fun getAll(): List<PlaybackHistoryEntity>

    @Query("UPDATE playback_history SET thumbnail_path = :path WHERE uri = :uriString")
    suspend fun updateThumbnailPath(uriString: String, path: String?)

    @Query("SELECT * FROM playback_history WHERE uri = :uriString LIMIT 1")
    suspend fun getByUri(uriString: String): PlaybackHistoryEntity?

    @Query("SELECT COUNT(*) FROM playback_history WHERE uri = :uriString")
    suspend fun countByUri(uriString: String): Int

    @Query("DELETE FROM playback_history WHERE uri = :uriString")
    suspend fun delete(uriString: String)

    @Query("DELETE FROM playback_history")
    suspend fun clearAll()
}
