package com.fluxplayer.app.core.data.repository

import androidx.datastore.core.DataStore
import androidx.room.withTransaction
import com.fluxplayer.app.core.database.MediaDatabase
import com.fluxplayer.app.core.database.entities.AudiobookChapterProgressEntity
import com.fluxplayer.app.core.database.entities.AudiobookResumeEntity
import com.fluxplayer.app.core.model.ApplicationPreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AudiobookProgressSnapshot(
    val resumeStates: Map<String, String>,
    val chapterProgress: Map<String, String>,
    val lastPlayedAt: Map<String, Long>,
)

@Singleton
class AudiobookProgressRepository @Inject constructor(
    private val database: MediaDatabase,
    private val preferences: DataStore<ApplicationPreferences>,
) {
    private val dao = database.audiobookProgressDao()
    private val mutex = Mutex()
    private var migrated = false

    val progress = flow {
        ensureMigrated()
        emitAll(combine(dao.observeResumes(), dao.observeChapters(), ::snapshot))
    }

    fun chapterProgress(bookPath: String) = flow {
        ensureMigrated()
        emitAll(dao.observeChapters(bookPath).map { rows -> rows.associate { it.chapterIndex to (it.position to it.duration) } })
    }

    suspend fun save(bookPath: String, index: Int, position: Long, duration: Long, lastPlayedAt: Long? = null) {
        if (bookPath.isBlank() || index < 0 || duration <= 0) return
        mutex.withLock {
            migrateLocked()
            dao.save(bookPath, index, position.coerceAtLeast(0), duration, lastPlayedAt)
        }
    }

    suspend fun clear() = mutex.withLock {
        migrateLocked()
        dao.clear()
    }

    suspend fun exportTo(settings: ApplicationPreferences): ApplicationPreferences = mutex.withLock {
        migrateLocked()
        val state = database.withTransaction { snapshot(dao.getResumes(), dao.getChapters()) }
        settings.copy(
            audiobookResumeState = state.resumeStates,
            audiobookChapterProgress = state.chapterProgress,
            audiobookLastPlayedAt = state.lastPlayedAt,
        )
    }

    suspend fun restore(settings: ApplicationPreferences) = mutex.withLock {
        migrateLocked()
        database.withTransaction {
            dao.clear()
            importLegacy(settings)
        }
    }

    private suspend fun ensureMigrated() = mutex.withLock { migrateLocked() }

    private suspend fun migrateLocked() {
        if (migrated) return
        // 在读取真实偏好后迁移，避免启动时 StateFlow 的默认空值掩盖旧记录。
        preferences.updateData { settings ->
            importLegacy(settings)
            withoutLegacyProgress(settings)
        }
        migrated = true
    }

    private suspend fun importLegacy(settings: ApplicationPreferences) {
        val resumes = settings.audiobookResumeState.mapNotNull { (path, value) ->
            val parts = value.split('|')
            val index = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            val position = parts.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(0) ?: return@mapNotNull null
            AudiobookResumeEntity(path, index, position, settings.audiobookLastPlayedAt[path] ?: 0L)
        }
        val chapters = settings.audiobookChapterProgress.mapNotNull { (key, value) ->
            val index = key.substringAfterLast('|').toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            val parts = value.split('|')
            val position = parts.getOrNull(0)?.toLongOrNull()?.coerceAtLeast(0) ?: return@mapNotNull null
            val duration = parts.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(0) ?: return@mapNotNull null
            AudiobookChapterProgressEntity(key.substringBeforeLast('|'), index, position, duration)
        }
        dao.importLegacy(resumes, chapters)
    }

    private fun snapshot(resumes: List<AudiobookResumeEntity>, chapters: List<AudiobookChapterProgressEntity>) = AudiobookProgressSnapshot(
        resumeStates = resumes.associate { it.bookPath to "${it.chapterIndex}|${it.position}" },
        chapterProgress = chapters.associate { "${it.bookPath}|${it.chapterIndex}" to "${it.position}|${it.duration}" },
        lastPlayedAt = resumes.associate { it.bookPath to it.lastPlayedAt },
    )

    companion object {
        fun withoutLegacyProgress(settings: ApplicationPreferences): ApplicationPreferences = settings.copy(
            audiobookResumeState = emptyMap(),
            audiobookChapterProgress = emptyMap(),
            audiobookLastPlayedAt = emptyMap(),
        )
    }
}
