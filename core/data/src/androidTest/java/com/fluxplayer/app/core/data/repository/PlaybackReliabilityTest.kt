package com.fluxplayer.app.core.data.repository

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxplayer.app.core.database.MediaDatabase
import com.fluxplayer.app.core.database.converter.UriListConverter
import com.fluxplayer.app.core.database.entities.DirectoryEntity
import com.fluxplayer.app.core.database.entities.DownloadStatus
import com.fluxplayer.app.core.database.entities.DownloadTaskEntity
import com.fluxplayer.app.core.database.entities.MediumEntity
import com.fluxplayer.app.core.database.entities.MediumStateEntity
import com.fluxplayer.app.core.datastore.serializer.ApplicationPreferencesSerializer
import com.fluxplayer.app.core.media.sync.MediaSnapshotWriter
import com.fluxplayer.app.core.model.ApplicationPreferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlaybackReliabilityTest {
    private lateinit var context: Context
    private lateinit var database: MediaDatabase
    private lateinit var settings: DataStore<ApplicationPreferences>
    private lateinit var settingsFile: File
    private lateinit var settingsScope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, MediaDatabase::class.java).build()
        settingsFile = File(context.cacheDir, "progress-${UUID.randomUUID()}.json")
        settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        settings = DataStoreFactory.create(ApplicationPreferencesSerializer, scope = settingsScope) { settingsFile }
    }

    @After
    fun tearDown() = runBlocking {
        settingsScope.coroutineContext[Job]?.cancelAndJoin()
        database.close()
        settingsFile.delete()
        Unit
    }

    @Test
    fun delayedProgressCannotOverwriteCompletedOrFailedDownload() = runBlocking {
        val dao = database.downloadTaskDao()
        val completed = dao.insert(download("complete"))
        dao.updateProgress(completed, 5, 10)
        dao.complete(completed, "/download/video", 10, 100)
        dao.updateProgress(completed, 7, 10)
        val row = requireNotNull(dao.getById(completed))
        assertEquals(DownloadStatus.COMPLETED, row.status)
        assertEquals(10L, row.downloadedBytes)
        assertEquals(10L, row.fileSize)
        assertEquals("/download/video", row.filePath)
        assertEquals(100L, row.completedAt)
        val failed = dao.insert(download("failed"))
        dao.fail(failed, 200)
        dao.updateProgress(failed, 9, 10)
        assertEquals(DownloadStatus.FAILED, dao.getById(failed)?.status)
        assertTrue(dao.getActiveDownloadsAsFlow().first().isEmpty())
    }

    @Test
    fun emptySuccessfulSnapshotRemovesLastVideoAndItsState() = runBlocking {
        val writer = MediaSnapshotWriter(database)
        writer.write(listOf(medium("one")), listOf(directory))
        database.mediumStateDao().upsert(MediumStateEntity("one", playbackPosition = 400))
        val cleanup = writer.write(emptyList(), emptyList())
        assertEquals(listOf("one"), cleanup.mediaUris)
        assertTrue(database.mediumDao().getAll().first().isEmpty())
        assertTrue(database.directoryDao().getAll().first().isEmpty())
        assertNull(database.mediumStateDao().get("one"))
    }

    @Test
    fun snapshotRetainsMetadataAndSharedSubtitlePermissions() = runBlocking {
        val writer = MediaSnapshotWriter(database)
        val shared = Uri.parse("content://test/shared")
        val unused = Uri.parse("content://test/unused")
        database.mediumDao().upsert(medium("keep").copy(format = "matroska", thumbnailPath = "/cached/keep"))
        database.mediumDao().upsert(medium("remove"))
        database.mediumStateDao().upsert(MediumStateEntity("keep", externalSubs = UriListConverter.fromListToString(listOf(shared))))
        database.mediumStateDao().upsert(MediumStateEntity("remove", externalSubs = UriListConverter.fromListToString(listOf(shared, unused))))
        val cleanup = writer.write(listOf(medium("keep").copy(size = 99)), listOf(directory))
        assertEquals(setOf(unused), cleanup.subtitleUris)
        assertEquals(listOf("remove"), cleanup.mediaUris)
        val kept = requireNotNull(database.mediumDao().get("keep"))
        assertEquals("matroska", kept.format)
        assertEquals("/cached/keep", kept.thumbnailPath)
        assertEquals(99L, kept.size)
    }

    @Test
    fun failedSnapshotRollsBackEarlierWrites() = runBlocking {
        val writer = MediaSnapshotWriter(database)
        writer.write(listOf(medium("old")), listOf(directory))
        database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_delete BEFORE DELETE ON media BEGIN SELECT RAISE(ABORT, 'test rollback'); END")
        val result = runCatching { writer.write(listOf(medium("new")), emptyList()) }
        assertTrue(result.isFailure)
        assertEquals(listOf("old"), database.mediumDao().getAll().first().map { it.uriString })
        assertEquals(listOf(directory), database.directoryDao().getAll().first())
    }

    @Test
    fun legacyProgressMigratesBeforeFirstSaveAndDoesNotRewritePreferencesOnHeartbeat() = runBlocking {
        settings.updateData {
            it.copy(
                audiobookRootUri = "/books",
                audiobookResumeState = mapOf("/books/A" to "2|400", "/books/B" to "1|200"),
                audiobookChapterProgress = mapOf("/books/A|2" to "400|1000", "/books/B|1" to "200|2000"),
                audiobookLastPlayedAt = mapOf("/books/A" to 50L, "/books/B" to 40L),
            )
        }
        val repository = AudiobookProgressRepository(database, settings)
        repository.save("/books/A", 2, 500, 1000, 60)
        val state = repository.progress.first()
        assertEquals("2|500", state.resumeStates["/books/A"])
        assertEquals("1|200", state.resumeStates["/books/B"])
        assertEquals(60L, state.lastPlayedAt["/books/A"])
        assertTrue(settings.data.first().audiobookResumeState.isEmpty())
        val preferencesBytes = settingsFile.readBytes()
        repository.save("/books/A", 2, 600, 1000, 60)
        assertTrue(preferencesBytes.contentEquals(settingsFile.readBytes()))
        val reopened = AudiobookProgressRepository(database, settings)
        assertEquals("2|600", reopened.progress.first().resumeStates["/books/A"])
    }

    @Test
    fun backupRoundTripAndClearUseTheNewProgressStorage() = runBlocking {
        val repository = AudiobookProgressRepository(database, settings)
        repository.save("/books/A", 3, 900, 2000, 100)
        val backup = repository.exportTo(ApplicationPreferences(audiobookRootUri = "/books"))
        repository.clear()
        assertTrue(repository.progress.first().resumeStates.isEmpty())
        repository.restore(backup)
        assertEquals("3|900", repository.progress.first().resumeStates["/books/A"])
        assertEquals(900L to 2000L, repository.chapterProgress("/books/A").first()[3])
        assertFalse(settings.data.first().audiobookChapterProgress.isNotEmpty())
    }

    @Test
    fun versionElevenDatabaseUpgradesWithoutLosingMedia() = runBlocking {
        val name = "migration-${UUID.randomUUID()}"
        try {
            // 使用提交的旧版导出结构创建数据库，验证真实升级路径。
            val assets = InstrumentationRegistry.getInstrumentation().context.assets
            val json = assets.open("com.fluxplayer.app.core.database.MediaDatabase/11.json").bufferedReader().use { it.readText() }
            val schema = JSONObject(json).getJSONObject("database").getJSONArray("entities")
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                for (i in 0 until schema.length()) {
                    val entity = schema.getJSONObject(i)
                    val table = entity.getString("tableName")
                    old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.getJSONArray("indices")
                    for (j in 0 until indices.length()) {
                        old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                }
                old.execSQL("INSERT INTO media (uri, path, filename, parent_path, last_modified, size, width, height, duration, media_store_id) VALUES ('legacy', '/video/one', 'one', '/video', 0, 1, 1, 1, 1, 1)")
                old.version = 11
            }
            val upgraded = Room.databaseBuilder(context, MediaDatabase::class.java, name)
                .addMigrations(MediaDatabase.MIGRATION_11_12).build()
            try {
                assertEquals("one", upgraded.mediumDao().get("legacy")?.name)
                upgraded.audiobookProgressDao().save("/books/A", 1, 10, 100, 20)
                assertEquals(10L, upgraded.audiobookProgressDao().getResume("/books/A")?.position)
            } finally {
                upgraded.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private val directory = DirectoryEntity("/video", "video", 0, "/")

    private fun medium(uri: String) = MediumEntity(uri, "/video/$uri", uri, "/video", 0, 10, 1, 1, 1000, 1)

    private fun download(name: String) = DownloadTaskEntity(fileName = name, url = "https://example.invalid/file", fileSize = 0, status = DownloadStatus.DOWNLOADING, createdAt = 0)
}
