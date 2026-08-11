package com.fluxplayer.app.core.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "download_tasks",
    indices = [
        // 下载任务列表按 status 过滤，补索引避免全表扫描
        Index(value = ["status"]),
    ],
)
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
