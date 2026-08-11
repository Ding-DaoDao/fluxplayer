package com.fluxplayer.app.core.data.repository

import androidx.core.net.toUri
import com.fluxplayer.app.core.common.extensions.fromUri
import com.fluxplayer.app.core.data.extractor.ThumbnailExtractor
import com.fluxplayer.app.core.database.dao.MediumStateDao
import com.fluxplayer.app.core.database.dao.PlaybackHistoryDao
import com.fluxplayer.app.core.database.entities.PlaybackHistoryEntity
import com.fluxplayer.app.core.model.PlaybackHistory
import com.fluxplayer.app.core.model.VideoSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Singleton
class LocalPlaybackHistoryRepository @Inject constructor(
    private val playbackHistoryDao: PlaybackHistoryDao,
    private val mediumStateDao: MediumStateDao,
    private val thumbnailExtractor: ThumbnailExtractor,
) : PlaybackHistoryRepository {

    private val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var migrationAttempted = false

    private val thumbnailScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        migrationScope.launch {
            migrateFromMediaState()
        }
    }

    private suspend fun migrateFromMediaState() {
        if (migrationAttempted) return
        migrationAttempted = true

        // Only migrate if history table is empty
        val existingCount = playbackHistoryDao.getAll().size
        if (existingCount > 0) return

        val mediaStates = mediumStateDao.getAll().first()
            .filter { it.lastPlayedTime != null }

        for (state in mediaStates) {
            val uriString = state.uriString
            val title = uriString.toUri().lastPathSegment ?: uriString
            val source = VideoSource.fromUri(uriString)

            playbackHistoryDao.upsert(
                PlaybackHistoryEntity(
                    uriString = uriString,
                    title = title,
                    source = source.name,
                    lastPlayedTime = state.lastPlayedTime ?: System.currentTimeMillis(),
                    playbackPosition = state.playbackPosition,
                    duration = 0L,
                    originalUriString = if (source == VideoSource.WEBDAV) uriString else null,
                ),
            )
        }
    }

    override suspend fun isPlayed(uriString: String): Boolean {
        return playbackHistoryDao.countByUri(uriString) > 0
    }

    override fun getHistoryFlow(): Flow<List<PlaybackHistory>> {
        return playbackHistoryDao.getAllAsFlow().map { entities ->
            entities.map { it.toPlaybackHistory() }
        }
    }

    override suspend fun recordPlayback(
        uriString: String,
        title: String,
        source: VideoSource,
        position: Long,
        duration: Long,
        originalUriString: String?,
        thumbnailPath: String?,
        parentPath: String?,
    ) {
        // 原子写入：thumbnailPath 为 null 时由 SQL 层保留现有值，
        // 避免退出时多次 recordHistory 并发读-改-写把缩略图覆盖成 null
        playbackHistoryDao.upsertPreservingThumbnail(
            uriString = uriString,
            title = title,
            source = source.name,
            lastPlayedTime = System.currentTimeMillis(),
            playbackPosition = position,
            duration = duration,
            originalUriString = originalUriString,
            thumbnailPath = thumbnailPath,
            parentPath = parentPath,
        )
        // 只有完全没有缩略图时才异步提取
        if (thumbnailPath == null &&
            playbackHistoryDao.getByUri(uriString)?.thumbnailPath == null
        ) {
            thumbnailScope.launch {
                val path = thumbnailExtractor.extract(
                    uriString = uriString,
                    source = source,
                    positionMs = position.coerceAtLeast(0),
                )
                if (path != null) {
                    playbackHistoryDao.updateThumbnailPath(
                        uriString = uriString,
                        path = path,
                    )
                }
            }
        }
    }

    override suspend fun deleteItem(uriString: String) {
        playbackHistoryDao.delete(uriString)
        thumbnailExtractor.deleteThumbnail(uriString)
    }

    override suspend fun clearAll() {
        playbackHistoryDao.clearAll()
        mediumStateDao.clearPlayedTimestamps()
        thumbnailExtractor.clearAll()
    }

    private fun PlaybackHistoryEntity.toPlaybackHistory(): PlaybackHistory {
        return PlaybackHistory(
            uriString = uriString,
            title = title,
            source = try {
                VideoSource.valueOf(source)
            } catch (_: IllegalArgumentException) {
                VideoSource.OTHER
            },
            lastPlayedTime = lastPlayedTime,
            playbackPosition = playbackPosition,
            duration = duration,
            originalUriString = originalUriString,
            thumbnailPath = thumbnailPath,
            parentPath = parentPath,
        )
    }
}
