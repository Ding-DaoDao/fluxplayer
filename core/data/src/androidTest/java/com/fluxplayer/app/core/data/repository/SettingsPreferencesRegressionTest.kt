package com.fluxplayer.app.core.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fluxplayer.app.core.database.MediaDatabase
import com.fluxplayer.app.core.datastore.datasource.AppPreferencesDataSource
import com.fluxplayer.app.core.datastore.datasource.PlayerPreferencesDataSource
import com.fluxplayer.app.core.datastore.serializer.ApplicationPreferencesSerializer
import com.fluxplayer.app.core.datastore.serializer.PlayerPreferencesSerializer
import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.model.PlayerPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPreferencesRegressionTest {
    @Test
    fun removedLegacyKeysDoNotResetUnrelatedPreferences() = runBlocking {
        val player = PlayerPreferencesSerializer.readFrom(
            """{"useLongPressControls":false,"danmakuCacheMap":{"1":"old.xml"},"pauseOnHeadsetDisconnect":false,"preferredAudioLanguage":"zh"}""".byteInputStream(),
        )
        assertFalse(player.pauseOnHeadsetDisconnect)
        assertEquals("zh", player.preferredAudioLanguage)
        val app = ApplicationPreferencesSerializer.readFrom(
            """{"latestFootprintPerDir":{"old":"unused"},"historyLimit":350,"audiobookRootUri":"/books"}""".byteInputStream(),
        )
        assertEquals(350, app.historyLimit)
        assertEquals("/books", app.audiobookRootUri)
    }

    @Test
    fun resettingPreferencesMigratesAndPreservesListeningProgress() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, MediaDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val app = MemoryStore(
                ApplicationPreferences(
                    audiobookRootUri = "/books",
                    historyLimit = 500,
                    audiobookResumeState = mapOf("/books/A" to "2|1234"),
                    audiobookChapterProgress = mapOf("/books/A|2" to "1234|9000"),
                    audiobookLastPlayedAt = mapOf("/books/A" to 100L),
                ),
            )
            val player = MemoryStore(PlayerPreferences(defaultPlaybackSpeed = 2f))
            val progress = AudiobookProgressRepository(database, app, context)
            val repository = LocalPreferencesRepository(AppPreferencesDataSource(app), PlayerPreferencesDataSource(player), progress, scope)
            repository.resetPreferences()
            assertEquals(ApplicationPreferences(), app.data.first())
            assertEquals(PlayerPreferences(), player.data.first())
            val restored = progress.progress.first()
            assertEquals("2|1234", restored.resumeStates["/books/A"])
            assertEquals("1234|9000", restored.chapterProgress["/books/A|2"])
            assertEquals(100L, restored.lastPlayedAt["/books/A"])
        } finally {
            scope.cancel()
            database.close()
        }
    }

    @Test
    fun explicitProgressClearKeepsLibraryAndOtherPreferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, MediaDatabase::class.java).build()
        try {
            val app = MemoryStore(ApplicationPreferences(audiobookRootUri = "/books", historyLimit = 500))
            val progress = AudiobookProgressRepository(database, app, context)
            progress.save("/books/A", 1, 1234, 9000)
            progress.clear()
            assertTrue(progress.progress.first().resumeStates.isEmpty())
            assertTrue(progress.progress.first().chapterProgress.isEmpty())
            assertEquals("/books", app.data.first().audiobookRootUri)
            assertEquals(500, app.data.first().historyLimit)
        } finally {
            database.close()
        }
    }

    private class MemoryStore<T>(initial: T) : DataStore<T> {
        override val data = MutableStateFlow(initial)
        private val mutex = Mutex()

        override suspend fun updateData(transform: suspend (T) -> T): T = mutex.withLock {
            transform(data.value).also { data.value = it }
        }
    }
}
