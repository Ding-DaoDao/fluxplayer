package com.fluxplayer.app.core.media.sync

import android.net.Uri
import androidx.room.withTransaction
import com.fluxplayer.app.core.database.MediaDatabase
import com.fluxplayer.app.core.database.converter.UriListConverter
import com.fluxplayer.app.core.database.entities.DirectoryEntity
import com.fluxplayer.app.core.database.entities.MediumEntity
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/** 一轮扫描的数据库变更原子提交，失败或取消时保留原快照。 */
class MediaSnapshotWriter @Inject constructor(private val database: MediaDatabase) {
    data class Cleanup(val mediaUris: List<String>, val subtitleUris: Set<Uri>)

    suspend fun write(media: List<MediumEntity>, directories: List<DirectoryEntity>): Cleanup = database.withTransaction {
        val mediumDao = database.mediumDao()
        val stateDao = database.mediumStateDao()
        val directoryDao = database.directoryDao()
        val previous = mediumDao.getAll().first().associateBy { it.uriString }
        val currentUris = media.mapTo(HashSet()) { it.uriString }
        val changed = media.map { item ->
            val old = previous[item.uriString]
            item.copy(format = old?.format, thumbnailPath = old?.thumbnailPath)
        }.filter { it != previous[it.uriString] }
        mediumDao.upsertAll(changed)

        val previousDirectories = directoryDao.getAll().first().associateBy { it.path }
        directoryDao.upsertAll(directories.filter { it != previousDirectories[it.path] })
        val currentPaths = directories.mapTo(HashSet()) { it.path }
        (previousDirectories.keys - currentPaths).toList().chunked(500).forEach { directoryDao.delete(it) }

        val removed = previous.keys - currentUris
        val unusedSubtitles = if (removed.isEmpty()) {
            emptySet()
        } else {
            // 仅删除媒体时批量读取状态；仍被其他记录引用的字幕权限必须保留。
            val states = stateDao.getAll().first()
            val retained = states.filter { it.uriString !in removed }
                .flatMap { UriListConverter.fromStringToList(it.externalSubs) }.toSet()
            states.filter { it.uriString in removed }
                .flatMap { UriListConverter.fromStringToList(it.externalSubs) }.toSet() - retained
        }
        removed.toList().chunked(500).forEach {
            mediumDao.delete(it)
            stateDao.delete(it)
        }
        Cleanup(removed.toList(), unusedSubtitles)
    }
}
