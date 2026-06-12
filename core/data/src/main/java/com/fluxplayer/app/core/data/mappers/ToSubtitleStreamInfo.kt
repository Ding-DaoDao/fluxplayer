package com.fluxplayer.app.core.data.mappers

import com.fluxplayer.app.core.database.entities.SubtitleStreamInfoEntity
import com.fluxplayer.app.core.model.SubtitleStreamInfo

fun SubtitleStreamInfoEntity.toSubtitleStreamInfo() = SubtitleStreamInfo(
    index = index,
    title = title,
    codecName = codecName,
    language = language,
    disposition = disposition,
)
