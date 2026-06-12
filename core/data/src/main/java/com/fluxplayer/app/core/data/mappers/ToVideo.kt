package com.fluxplayer.app.core.data.mappers

import com.fluxplayer.app.core.common.Utils
import com.fluxplayer.app.core.database.entities.AudioStreamInfoEntity
import com.fluxplayer.app.core.database.entities.SubtitleStreamInfoEntity
import com.fluxplayer.app.core.database.relations.MediumWithInfo
import com.fluxplayer.app.core.model.Video
import java.util.Date

fun MediumWithInfo.toVideo() = Video(
    id = mediumEntity.mediaStoreId,
    path = mediumEntity.path,
    parentPath = mediumEntity.parentPath,
    duration = mediumEntity.duration,
    uriString = mediumEntity.uriString,
    nameWithExtension = mediumEntity.name,
    width = mediumEntity.width,
    height = mediumEntity.height,
    size = mediumEntity.size,
    dateModified = mediumEntity.modified,
    format = mediumEntity.format,
    playbackPosition = mediumStateEntity?.playbackPosition ?: 0L,
    lastPlayedAt = mediumStateEntity?.lastPlayedTime?.let { Date(it) },
    formattedDuration = Utils.formatDurationMillis(mediumEntity.duration),
    formattedFileSize = Utils.formatFileSize(mediumEntity.size),
    videoStream = videoStreamInfo?.toVideoStreamInfo(),
    audioStreams = audioStreamsInfo.map(AudioStreamInfoEntity::toAudioStreamInfo),
    subtitleStreams = subtitleStreamsInfo.map(SubtitleStreamInfoEntity::toSubtitleStreamInfo),
)
