package com.fluxplayer.app.feature.tingshu

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.fluxplayer.app.feature.player.AudioPlayerTheme
import com.fluxplayer.app.feature.player.model.AudioBook
import com.fluxplayer.app.feature.player.model.AudioChapter

/** 在独立测试包中使用真实书架和本地收听记录，复现显示模式切换。 */
class ListeningHomePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val books = List(20) { index ->
            AudioBook(
                title = "书 $index",
                folderPath = "/layout-test/$index",
                coverUri = null,
                chapterCount = 1,
                chapters = listOf(AudioChapter("第一集", Uri.parse("file:///layout-test/$index/1.mp3"), 100)),
            )
        }
        setContent {
            AudioPlayerTheme {
                Surface(Modifier.fillMaxSize()) {
                    ListeningLibraryHome(
                        sources = emptyList(),
                        localBookCount = books.size,
                        hasLocalPath = true,
                        onLocalClick = {},
                        onSourceClick = {},
                        localBooks = books,
                        localResume = books.associate { it.folderPath to "0|30000" },
                        localChapterProgress = books.associate { "${it.folderPath}|0" to "30000|120000" },
                        localLastPlayedAt = books.mapIndexed { index, book -> book.folderPath to (System.currentTimeMillis() - index) }.toMap(),
                    )
                }
            }
        }
    }
}
