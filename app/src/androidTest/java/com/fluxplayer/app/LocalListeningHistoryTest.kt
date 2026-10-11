package com.fluxplayer.app

import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxplayer.app.core.common.ListeningFile
import com.fluxplayer.app.core.common.LocalListeningBook
import com.fluxplayer.app.core.common.LocalListeningLibrary
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.model.PlayerPreferences
import com.fluxplayer.app.feature.player.AudioThemePreferences
import com.fluxplayer.app.feature.videopicker.screens.audiobook.AudiobookViewModel
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalListeningHistoryTest {
    @Test
    fun historyRestoresWithoutDirectoryScanAndUpdatesWhenNewBookStarts() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val progress = EntryPointAccessors.fromApplication(context, AudioThemePreferences::class.java).audiobookProgressRepository()
        val books = List(2) { index ->
            LocalListeningBook(
                path = "${context.cacheDir}/history-unavailable/$index",
                title = "历史书籍 $index",
                chapters = listOf(ListeningFile(Uri.parse("file://${context.cacheDir}/history-unavailable/$index/01.mp3"), "01.mp3", 100, 0, false)),
                cover = null,
            )
        }
        books.forEach { LocalListeningLibrary.remember(context, it) }
        progress.save(books[0].path, 0, 12_000, 60_000, 100, books[0].chapters.single().uri.toString())
        val preferences = HistoryPreferences("${context.cacheDir}/history-unavailable")
        var store = ViewModelStore()
        fun create(): AudiobookViewModel {
            lateinit var model: AudiobookViewModel
            instrumentation.runOnMainSync {
                model = AudiobookViewModel(preferences, progress, context)
                store.put("history", model)
            }
            return model
        }
        try {
            var model = create()
            val initial = withTimeout(5_000) { model.uiState.first { it.recentLocalBooks.any { book -> book.folderPath == books[0].path } } }
            assertEquals("历史书籍 0", initial.recentLocalBooks.single { it.folderPath == books[0].path }.title)
            assertEquals("0|12000", initial.resumeStates[books[0].path])
            // 外部目录不可用也须显示历史，证明展示不依赖扫描完成。
            withTimeout(5_000) { model.uiState.first { it.scanError != null } }
            progress.save(books[1].path, 0, 0, 60_000, 200, books[1].chapters.single().uri.toString())
            val updated = withTimeout(2_000) { model.uiState.first { it.recentLocalBooks.any { book -> book.folderPath == books[1].path } } }
            assertEquals(books[1].path, updated.recentLocalBooks.first().folderPath)
            instrumentation.runOnMainSync { store.clear() }
            store = ViewModelStore()
            model = create()
            val restored = withTimeout(5_000) { model.uiState.first { it.recentLocalBooks.size >= 2 } }
            assertTrue(restored.recentLocalBooks.any { it.folderPath == books[0].path })
            assertEquals(books[1].path, restored.recentLocalBooks.first().folderPath)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }
}

/** 独立测试使用不可访问的书库路径，不修改用户偏好。 */
private class HistoryPreferences(root: String) : PreferencesRepository {
    override val applicationPreferences = MutableStateFlow(ApplicationPreferences(audiobookRootUri = root))
    override val playerPreferences = MutableStateFlow(PlayerPreferences())
    override suspend fun updateApplicationPreferences(transform: suspend (ApplicationPreferences) -> ApplicationPreferences) {
        applicationPreferences.value = transform(applicationPreferences.value)
    }
    override suspend fun updatePlayerPreferences(transform: suspend (PlayerPreferences) -> PlayerPreferences) {
        playerPreferences.value = transform(playerPreferences.value)
    }
    override suspend fun resetPreferences() = Unit
}
