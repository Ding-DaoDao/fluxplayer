package com.fluxplayer.app.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.room.withTransaction
import com.fluxplayer.app.core.common.LocalListeningLibrary
import com.fluxplayer.app.core.database.MediaDatabase
import com.fluxplayer.app.core.database.entities.AudiobookChapterProgressEntity
import com.fluxplayer.app.core.database.entities.AudiobookResumeEntity
import com.fluxplayer.app.core.model.ApplicationPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AudiobookProgressSnapshot(
    val resumeStates: Map<String, String>,
    val chapterProgress: Map<String, String>,
    val lastPlayedAt: Map<String, Long>,
)

@Singleton
class AudiobookProgressRepository @Inject constructor(
    private val database: MediaDatabase,
    private val preferences: DataStore<ApplicationPreferences>,
    @ApplicationContext private val context: Context,
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

    suspend fun save(bookPath: String, index: Int, position: Long, duration: Long, lastPlayedAt: Long? = null, chapterUri: String? = null, updateResume: Boolean = true) {
        if (bookPath.isBlank() || index < 0 || duration <= 0) return
        mutex.withLock {
            migrateLocked()
            val book = withContext(Dispatchers.IO) { LocalListeningLibrary.find(context, bookPath) }
            val identity = LocalListeningLibrary.chapterIdentity(chapterUri ?: book?.chapters?.getOrNull(index)?.uri?.toString().orEmpty())
            val stableIndex = book?.chapters?.indexOfFirst { LocalListeningLibrary.chapterIdentity(it.uri.toString()) == identity } ?: index
            if (stableIndex < 0) return@withLock
            dao.save(bookPath, stableIndex, position.coerceAtLeast(0), duration, lastPlayedAt, identity, updateResume)
        }
    }

    /** 文件增删后按章节 URI 重排进度，旧记录首次扫描时补齐标识。 */
    suspend fun reconcile(bookPath: String, uris: List<String>) = mutex.withLock {
        migrateLocked()
        val identities = uris.map(LocalListeningLibrary::chapterIdentity)
        database.withTransaction {
            val chapters = dao.getChapters().filter { it.bookPath == bookPath }
            val resume = dao.getResume(bookPath)
            dao.clearChapters(bookPath)
            chapters.forEach { row ->
                val uri = row.chapterUri.ifBlank { identities.getOrNull(row.chapterIndex).orEmpty() }
                val index = identities.indexOf(uri)
                if (index >= 0) dao.upsertChapter(row.copy(chapterIndex = index, chapterUri = uri))
            }
            if (resume != null) {
                val uri = resume.chapterUri.ifBlank { identities.getOrNull(resume.chapterIndex).orEmpty() }
                val index = identities.indexOf(uri)
                dao.upsertResume(resume.copy(chapterIndex = index, chapterUri = uri, position = if (index < 0) 0 else resume.position))
            }
        }
    }

    suspend fun clear() = mutex.withLock {
        migrateLocked()
        dao.clear()
    }

    suspend fun exportTo(settings: ApplicationPreferences): ApplicationPreferences = mutex.withLock {
        migrateLocked()
        val rows = database.withTransaction { dao.getResumes() to dao.getChapters() }
        val state = snapshot(rows.first, rows.second)
        settings.copy(
            audiobookResumeState = rows.first.associate { it.bookPath to "${it.chapterIndex}|${it.position}|${it.chapterUri}" },
            audiobookChapterProgress = rows.second.associate { "${it.bookPath}|${it.chapterIndex}" to "${it.position}|${it.duration}|${it.chapterUri}" },
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
            AudiobookResumeEntity(path, index, position, settings.audiobookLastPlayedAt[path] ?: 0L, parts.getOrNull(2).orEmpty())
        }
        val chapters = settings.audiobookChapterProgress.mapNotNull { (key, value) ->
            val index = key.substringAfterLast('|').toIntOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            val parts = value.split('|')
            val position = parts.getOrNull(0)?.toLongOrNull()?.coerceAtLeast(0) ?: return@mapNotNull null
            val duration = parts.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(0) ?: return@mapNotNull null
            AudiobookChapterProgressEntity(key.substringBeforeLast('|'), index, position, duration, parts.getOrNull(2).orEmpty())
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
