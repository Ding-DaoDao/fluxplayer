package com.fluxplayer.app.feature.tingshu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.exoplayer.ExoPlayer
import com.fluxplayer.app.feature.player.AudioPlaybackScreen
import com.fluxplayer.app.feature.player.AudioPlayerTheme
import com.fluxplayer.app.feature.player.service.AudioSleepTimer

/** 使用虚构书籍验证真实控件，不读取用户书库或改变实际播放会话。 */
class ListeningControlsPreviewActivity : ComponentActivity() {
    var bookPath by mutableStateOf("controls-book-1")
    var intro by mutableStateOf(30)
    var outro by mutableStateOf(15)
    var timer by mutableStateOf(AudioSleepTimer.State())
    var savedSpeed = 1f
    var refreshCount = 0
    private lateinit var player: ExoPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        setContent {
            AudioPlayerTheme {
                AudioPlaybackScreen(
                    player = player,
                    onBackClick = { finish() },
                    bookPath = bookPath,
                    bookName = "控件测试书籍",
                    chapterNames = List(100) { "章节 ${it + 1}" },
                    currentChapterIndex = 0,
                    introSkipSeconds = intro,
                    outroSkipSeconds = outro,
                    onSkipSettingsChanged = { head, tail ->
                        intro = head
                        outro = tail
                    },
                    onSpeedChanged = { savedSpeed = it },
                    sleepState = timer,
                    onSleepChange = { seconds, episodes -> timer = AudioSleepTimer.State(remainingSeconds = seconds, remainingEpisodes = episodes) },
                    onRefreshDirectory = { refreshCount++ },
                    onSelectChapter = {},
                )
            }
        }
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}
