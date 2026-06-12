package com.fluxplayer.app.core.data.mappers

import com.fluxplayer.app.core.common.Utils
import com.fluxplayer.app.core.database.relations.DirectoryWithMedia
import com.fluxplayer.app.core.database.relations.MediumWithInfo
import com.fluxplayer.app.core.model.Folder

fun DirectoryWithMedia.toFolder() = Folder(
    name = directory.name,
    path = directory.path,
    dateModified = directory.modified,
    parentPath = directory.parentPath,
    formattedMediaSize = Utils.formatFileSize(media.sumOf { it.mediumEntity.size }),
    mediaList = media.map(MediumWithInfo::toVideo),
)
