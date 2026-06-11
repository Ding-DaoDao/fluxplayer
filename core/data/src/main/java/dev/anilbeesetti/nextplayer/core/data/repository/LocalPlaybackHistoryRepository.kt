package dev.anilbeesetti.nextplayer.core.data.repository

import androidx.core.net.toUri
import dev.anilbeesetti.nextplayer.core.common.extensions.fromUri
import dev.anilbeesetti.nextplayer.core.data.extractor.ThumbnailExtractor
import dev.anilbeesetti.nextplayer.core.database.dao.MediumStateDao
import dev.anilbeesetti.nextplayer.core.database.dao.PlaybackHistoryDao
import dev.anilbeesetti.nextplayer.core.database.entities.PlaybackHistoryEntity
import dev.anilbeesetti.nextplayer.core.model.PlaybackHistory
import dev.anilbeesetti.nextplayer.core.model.VideoSource
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
    ) {
        // 新的缩略图优先使用；如果本次未截取，保留数据库中已有的缩略图
        val finalThumbnailPath = thumbnailPath
            ?: playbackHistoryDao.getByUri(uriString)?.thumbnailPath

        playbackHistoryDao.upsert(
            PlaybackHistoryEntity(
                uriString = uriString,
                title = title,
                source = source.name,
                lastPlayedTime = System.currentTimeMillis(),
                playbackPosition = position,
                duration = duration,
                originalUriString = originalUriString,
                thumbnailPath = finalThumbnailPath,
            ),
        )
        // 只有完全没有缩略图时才异步提取
        if (finalThumbnailPath == null) {
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
        )
    }
}
