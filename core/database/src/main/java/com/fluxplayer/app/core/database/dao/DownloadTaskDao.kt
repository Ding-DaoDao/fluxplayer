package com.fluxplayer.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.fluxplayer.app.core.database.entities.DownloadTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: DownloadTaskEntity): Long

    @Update
    suspend fun update(task: DownloadTaskEntity)

    /** 只更新活跃任务的进度，迟到的回调不能覆盖终态。 */
    @Query("UPDATE download_tasks SET downloadedBytes = :bytes, fileSize = :total WHERE id = :id AND status = 'DOWNLOADING'")
    suspend fun updateProgress(id: Long, bytes: Long, total: Long)

    @Query("UPDATE download_tasks SET status = 'COMPLETED', downloadedBytes = :size, fileSize = :size, filePath = :path, completedAt = :time WHERE id = :id AND status = 'DOWNLOADING'")
    suspend fun complete(id: Long, path: String, size: Long, time: Long)

    @Query("UPDATE download_tasks SET status = 'FAILED', completedAt = :time WHERE id = :id AND status IN ('PENDING', 'DOWNLOADING')")
    suspend fun fail(id: Long, time: Long)

    @Query("SELECT * FROM download_tasks ORDER BY createdAt DESC")
    fun getAllAsFlow(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun getById(id: Long): DownloadTaskEntity?

    @Query("SELECT * FROM download_tasks WHERE status IN ('PENDING', 'DOWNLOADING') ORDER BY createdAt ASC")
    fun getActiveDownloadsAsFlow(): Flow<List<DownloadTaskEntity>>

    @Query("DELETE FROM download_tasks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM download_tasks")
    suspend fun clearAll()
}
