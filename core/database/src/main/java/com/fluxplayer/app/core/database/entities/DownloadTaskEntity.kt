package com.fluxplayer.app.core.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val fileName: String,
    val url: String,
    val fileSize: Long,
    val downloadedBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.PENDING,
    val provider: String = "",
    val filePath: String? = null,
    val createdAt: Long,
    val completedAt: Long? = null,
)
