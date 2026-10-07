package com.fluxplayer.app.core.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audiobook_resume")
data class AudiobookResumeEntity(
    @PrimaryKey val bookPath: String,
    val chapterIndex: Int,
    val position: Long,
    val lastPlayedAt: Long,
)

@Entity(tableName = "audiobook_chapter_progress", primaryKeys = ["bookPath", "chapterIndex"])
data class AudiobookChapterProgressEntity(
    val bookPath: String,
    val chapterIndex: Int,
    val position: Long,
    val duration: Long,
)
