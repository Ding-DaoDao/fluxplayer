package com.fluxplayer.app.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "playback_history",
)
data class PlaybackHistoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "uri")
    val uriString: String,
    @ColumnInfo(name = "title")
    val title: String,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "last_played_time")
    val lastPlayedTime: Long,
    @ColumnInfo(name = "playback_position")
    val playbackPosition: Long,
    @ColumnInfo(name = "duration")
    val duration: Long,
    @ColumnInfo(name = "original_uri")
    val originalUriString: String? = null,
    @ColumnInfo(name = "thumbnail_path")
    val thumbnailPath: String? = null,
    @ColumnInfo(name = "parent_path")
    val parentPath: String? = null,
)
