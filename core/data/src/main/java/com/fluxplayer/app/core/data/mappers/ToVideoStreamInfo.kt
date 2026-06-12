package com.fluxplayer.app.core.data.mappers

import com.fluxplayer.app.core.database.entities.VideoStreamInfoEntity
import com.fluxplayer.app.core.model.VideoStreamInfo

fun VideoStreamInfoEntity.toVideoStreamInfo() = VideoStreamInfo(
    index = index,
    title = title,
    codecName = codecName,
    language = language,
    disposition = disposition,
    bitRate = bitRate,
    frameRate = frameRate,
    frameWidth = frameWidth,
    frameHeight = frameHeight,
)
