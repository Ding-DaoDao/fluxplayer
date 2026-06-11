package dev.anilbeesetti.nextplayer.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.anilbeesetti.nextplayer.core.database.entities.PlaybackHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackHistoryDao {

    @Upsert
    suspend fun upsert(history: PlaybackHistoryEntity)

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
