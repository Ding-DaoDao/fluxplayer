package com.fluxplayer.app.feature.tingshu

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken

class TingshuPlayerActivity : ComponentActivity() {
    private var selectionSent = false
    private var requestedBook: String? = null
    private var requestedIndex = 0
    private var requestedPosition = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        requestedBook = savedInstanceState?.getString("book") ?: intent.getStringExtra("book")
        requestedIndex = savedInstanceState?.getInt("index") ?: intent.getIntExtra("index", 0)
        requestedPosition = savedInstanceState?.getLong("position") ?: intent.getLongExtra("position", 0)
        selectionSent = savedInstanceState != null && ListeningPlayback.state.value.book != null
        setContent {
            val state by ListeningPlayback.state.collectAsStateWithLifecycle()
            var controller by remember { mutableStateOf<MediaController?>(null) }
            var connectionError by remember { mutableStateOf<String?>(null) }
            DisposableEffect(Unit) {
                val future = MediaController.Builder(
                    this@TingshuPlayerActivity,
                    SessionToken(this@TingshuPlayerActivity, ComponentName(this@TingshuPlayerActivity, TingshuPlaybackService::class.java)),
                ).buildAsync()
                var disposed = false
                future.addListener({
                    if (!disposed) {
                        try {
                            controller = future.get()
                        } catch (error: Exception) {
                            connectionError = "无法连接听书播放器，请返回后重试"
                        }
                    }
                }, ContextCompat.getMainExecutor(this@TingshuPlayerActivity))
                onDispose {
                    disposed = true
                    MediaController.releaseFuture(future)
                }
            }
            LaunchedEffect(controller) {
                val connected = controller ?: return@LaunchedEffect
                if (!selectionSent && requestedBook != null && !intent.getBooleanExtra("reopen", false)) {
                    select(connected, requestedBook!!, requestedIndex, requestedPosition)
                    selectionSent = true
                }
            }
            com.fluxplayer.app.feature.player.AudioPlayerTheme {
                val connected = controller
                val book = state.book
                if (connected == null || book == null) {
                    com.fluxplayer.app.feature.player.AudioLoadingScreen(
                        coverArtworkUri = book?.coverUrl?.takeIf { it.isNotBlank() }?.let(android.net.Uri::parse),
                        title = book?.title ?: "正在打开听书",
                    )
                    (connectionError ?: state.error)?.let { error ->
                        Column(Modifier.padding(24.dp)) {
                            SourceErrorNotice(error)
                            TextButton(onClick = { finish() }) { Text("返回书库") }
                        }
                    }
                } else {
                    val repository = com.fluxplayer.app.core.tingshu.TingshuRepository.get(this@TingshuPlayerActivity)
                    val progresses by repository.progresses.collectAsStateWithLifecycle()
                    com.fluxplayer.app.feature.player.AudioPlaybackScreen(
                        player = connected,
                        onBackClick = { finish() },
                        bookPath = book.key,
                        bookName = book.title,
                        chapterNames = book.episodes.map { it.title },
                        chapterProgress = remember(book, progresses) { repository.chapterProgress(book) },
                        currentChapterIndex = state.index,
                        introSkipSeconds = state.introSkipSeconds,
                        outroSkipSeconds = state.outroSkipSeconds,
                        onSkipSettingsChanged = { intro, outro ->
                            connected.sendCustomCommand(
                                SessionCommand(ListeningPlayback.SKIP, Bundle.EMPTY),
                                Bundle().apply {
                                    putInt("intro", intro)
                                    putInt("outro", outro)
                                },
                            )
                        },
                        onSelectChapter = { index -> select(connected, book.key, index, 0) },
                        sleepState = com.fluxplayer.app.feature.player.service.AudioSleepTimer.State(
                            remainingSeconds = state.sleepSeconds.toInt(),
                            remainingEpisodes = state.sleepEpisodes,
                        ),
                        onSleepChange = { seconds, episodes ->
                            connected.sendCustomCommand(
                                SessionCommand(ListeningPlayback.SLEEP, Bundle.EMPTY),
                                Bundle().apply {
                                    putInt("seconds", seconds)
                                    putInt("episodes", episodes)
                                },
                            )
                        },
                        coverModel = rememberSourceCover(book.sourceId, book.coverUrl, repository),
                        loading = state.loading,
                        playbackError = state.error,
                        playbackErrorContent = state.error?.let { error ->
                            { SourceErrorNotice(error, onRetry = { select(connected, book.key, state.index, state.position) }) }
                        },
                        onRetry = { select(connected, book.key, state.index, state.position) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val state = ListeningPlayback.state.value
        outState.putString("book", state.book?.key ?: requestedBook)
        outState.putInt("index", if (state.book != null) state.index else requestedIndex)
        outState.putLong("position", if (state.book != null) state.position else requestedPosition)
        super.onSaveInstanceState(outState)
    }

    private fun select(controller: MediaController, book: String, index: Int, position: Long) {
        controller.sendCustomCommand(
            SessionCommand(ListeningPlayback.SELECT, Bundle.EMPTY),
            Bundle().apply {
                putString("book", book)
                putInt("index", index)
                putLong("position", position)
            },
        )
    }
}
