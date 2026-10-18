package com.fluxplayer.app.core.tingshu

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.github.eprendre.tingshu.utils.Episode
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClearListeningHistoryTest {
    @Test
    fun removalClearsAllChapterProgressAndOldCallbacksCannotRestoreIt() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val files = File(base.cacheDir, "clear-listening-$id").apply { mkdirs() }
        val library = base.getSharedPreferences("clear-library-$id", Context.MODE_PRIVATE)
        val history = base.getSharedPreferences("clear-home-$id", Context.MODE_PRIVATE)
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = files
            override fun getSharedPreferences(name: String?, mode: Int) = when (name) {
                "tingshu_library" -> library
                "listening_home" -> history
                else -> super.getSharedPreferences(name, mode)
            }
        }
        val constructor = TingshuRepository::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val repository = constructor.newInstance(context)
        val book = ListeningBook("a".repeat(64), "test", "book", "测试书", "", "", listOf(Episode("第一章", "one"), Episode("第二章", "two")))
        val other = book.copy(key = "b".repeat(64), url = "other")
        repository.markPlayed(book, 100)
        repository.saveProgress(book.key, ListeningProgress("two", 900, 2000), 100)
        repository.markPlayed(other, 110)
        repository.saveProgress(other.key, ListeningProgress("one", 500, 2000), 110)
        repository.clearHistory(book.key, 200)
        assertEquals(ListeningProgress(), repository.progress(book.key))
        assertTrue(repository.chapterProgress(book).isEmpty())
        assertFalse(repository.recentBooks.value.any { it.key == book.key })
        assertEquals(500L, repository.progress(other.key).position)
        repository.markPlayed(book, 100)
        repository.saveProgress(book.key, ListeningProgress("two", 950, 2000), 100)
        assertFalse(repository.recentBooks.value.any { it.key == book.key })
        assertTrue(repository.chapterProgress(book).isEmpty())
        repository.markPlayed(book, 201)
        repository.saveProgress(book.key, ListeningProgress("one", 0, 2000), 201)
        assertEquals(setOf(0), repository.chapterProgress(book).keys)
        repository.clearHistory(now = 300)
        repository.saveProgress(other.key, ListeningProgress("one", 600, 2000), 110)
        assertTrue(repository.recentBooks.value.isEmpty())
        val reopened = constructor.newInstance(context)
        assertEquals(ListeningProgress(), reopened.progress(book.key))
        assertEquals(ListeningProgress(), reopened.progress(other.key))
        assertTrue(reopened.chapterProgress(book).isEmpty())
    }
}
