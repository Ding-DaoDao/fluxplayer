package com.fluxplayer.app.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.fluxplayer.app.core.database.entities.MediumStateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediumStateDao {

    @Upsert
    suspend fun upsert(mediumState: MediumStateEntity)

    @Upsert
    suspend fun upsertAll(mediaStates: List<MediumStateEntity>)

    @Query("SELECT * FROM media_state WHERE uri = :uri")
    suspend fun get(uri: String): MediumStateEntity?

    @Query("SELECT * FROM media_state WHERE uri = :uri")
    fun getAsFlow(uri: String): Flow<MediumStateEntity?>

    @Query("SELECT * FROM media_state")
    fun getAll(): Flow<List<MediumStateEntity>>

    @Query("DELETE FROM media_state WHERE uri in (:uris)")
    suspend fun delete(uris: List<String>)

    @Query("UPDATE media_state SET last_played_time = NULL, playback_position = 0, intro_ms = -1, outro_ms = -1, playback_speed = NULL")
    suspend fun clearPlayedTimestamps()

    @Query("DELETE FROM media_state WHERE last_played_time IS NOT NULL AND last_played_time < :cutoff")
    suspend fun deleteStale(cutoff: Long)

    /**
     * 原子写入播放位置：记录不存在则插入，存在则仅更新 position/last_played_time。
     * 播放进度高频调用，避免 SELECT + UPSERT 两次 DB 往返与并发丢更新。
     * 注意：INSERT 必须提供全部 NOT NULL 列（intro_ms/outro_ms/external_subs/video_scale/
     * subtitle_delay/subtitle_speed 在 Room 生成的新表中无列默认值），
     * 否则新记录插入会触发 NOT NULL 约束失败。
     */
    @Query(
        """INSERT INTO media_state (uri, intro_ms, outro_ms, playback_position, last_played_time, external_subs, video_scale, subtitle_delay, subtitle_speed)
        VALUES (:uri, -1, -1, :position, :lastPlayedTime, '', 1.0, 0, 1.0)
        ON CONFLICT(uri) DO UPDATE SET playback_position = :position, last_played_time = :lastPlayedTime""",
    )
    suspend fun upsertPosition(uri: String, position: Long, lastPlayedTime: Long)

    @Query(
        """INSERT INTO media_state (uri, intro_ms, outro_ms, last_played_time, playback_position, external_subs, video_scale, subtitle_delay, subtitle_speed)
        VALUES (:uri, :introMs, :outroMs, :lastPlayedTime, 0, '', 1.0, 0, 1.0)
        ON CONFLICT(uri) DO UPDATE SET intro_ms = :introMs, outro_ms = :outroMs, last_played_time = :lastPlayedTime""",
    )
    suspend fun upsertIntroOutro(uri: String, introMs: Long, outroMs: Long, lastPlayedTime: Long)
}
