package com.fluxplayer.app.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.fluxplayer.app.core.database.entities.AudiobookChapterProgressEntity
import com.fluxplayer.app.core.database.entities.AudiobookResumeEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class AudiobookProgressDao {
    @Query("SELECT * FROM audiobook_resume")
    abstract fun observeResumes(): Flow<List<AudiobookResumeEntity>>

    @Query("SELECT * FROM audiobook_chapter_progress")
    abstract fun observeChapters(): Flow<List<AudiobookChapterProgressEntity>>

    @Query("SELECT * FROM audiobook_chapter_progress WHERE bookPath = :bookPath")
    abstract fun observeChapters(bookPath: String): Flow<List<AudiobookChapterProgressEntity>>

    @Query("SELECT * FROM audiobook_resume")
    abstract suspend fun getResumes(): List<AudiobookResumeEntity>

    @Query("SELECT * FROM audiobook_chapter_progress")
    abstract suspend fun getChapters(): List<AudiobookChapterProgressEntity>

    @Query("SELECT * FROM audiobook_resume WHERE bookPath = :bookPath")
    abstract suspend fun getResume(bookPath: String): AudiobookResumeEntity?

    @Upsert
    abstract suspend fun upsertResume(resume: AudiobookResumeEntity)

    @Upsert
    abstract suspend fun upsertChapter(chapter: AudiobookChapterProgressEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun importResumes(resumes: List<AudiobookResumeEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun importChapters(chapters: List<AudiobookChapterProgressEntity>)

    @Query("DELETE FROM audiobook_resume")
    abstract suspend fun clearResumes()

    @Query("DELETE FROM audiobook_chapter_progress")
    abstract suspend fun clearChapters()

    @Transaction
    open suspend fun save(bookPath: String, index: Int, position: Long, duration: Long, lastPlayedAt: Long?) {
        val previousTime = getResume(bookPath)?.lastPlayedAt ?: 0L
        upsertResume(AudiobookResumeEntity(bookPath, index, position, maxOf(previousTime, lastPlayedAt ?: previousTime)))
        upsertChapter(AudiobookChapterProgressEntity(bookPath, index, position, duration))
    }

    @Transaction
    open suspend fun importLegacy(resumes: List<AudiobookResumeEntity>, chapters: List<AudiobookChapterProgressEntity>) {
        // 迁移可安全重试，已经保存的新进度优先于旧偏好数据。
        importResumes(resumes)
        importChapters(chapters)
    }

    @Transaction
    open suspend fun clear() {
        clearResumes()
        clearChapters()
    }
}
