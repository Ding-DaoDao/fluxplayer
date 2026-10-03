package com.fluxplayer.app.feature.videopicker.screens.audiobook

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fluxplayer.app.core.tingshu.ListeningBook
import com.fluxplayer.app.core.ui.base.DataState
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.theme.FluxTheme
import com.fluxplayer.app.feature.player.AudiobookBookCard
import com.fluxplayer.app.feature.player.PlayConfirmDialog
import com.fluxplayer.app.feature.tingshu.TingshuPlayerActivity
import com.fluxplayer.app.feature.tingshu.TingshuSourceContent
import com.fluxplayer.app.feature.tingshu.TingshuViewModel
import com.fluxplayer.app.feature.videopicker.model.AudioBook

@Composable
fun AudiobookTabContent(
    viewModel: AudiobookViewModel = hiltViewModel(),
    onPlayChapter: (Uri, Uri?, Long, List<Uri>, Int) -> Unit = { _, _, _, _, _ -> },
    onShowingDetailChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val sourceViewModel: TingshuViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val sources by sourceViewModel.repository.sources.collectAsStateWithLifecycle()
    val localState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var destination by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var showingDetail by remember { mutableStateOf(false) }
    // 首页点书后的播放确认窗：本地书直接可播，书源书籍需等预解析
    var pendingLocal by remember { mutableStateOf<AudioBook?>(null) }
    var pendingSource by remember { mutableStateOf<ListeningBook?>(null) }
    val sourceState by sourceViewModel.state.collectAsStateWithLifecycle()

    // 播放确认窗：本地书与书源书籍共用一套交互
    pendingLocal?.let { book ->
        val entry = localState.resumeStates[book.folderPath].orEmpty()
        val resumeIndex = entry.substringBefore('|').toIntOrNull()?.takeIf { it >= 0 }
        val uris = remember(book) { book.chapters.map { it.uri } }
        PlayConfirmDialog(
            title = book.title,
            chapterCount = book.chapters.size,
            coverModel = book.coverUri,
            resumeLabel = resumeIndex?.let { "继续收听第 ${it + 1} 集" },
            onDismiss = { pendingLocal = null },
            onPlay = {
                pendingLocal = null
                val startIndex = resumeIndex?.coerceIn(0, (uris.lastIndex).coerceAtLeast(0)) ?: 0
                val startMs = entry.substringAfter('|').toLongOrNull() ?: 0L
                uris.getOrNull(startIndex)?.let { uri ->
                    onPlayChapter(uri, book.coverUri, startMs, uris, startIndex)
                }
            },
        )
    }
    pendingSource?.let { book ->
        val progress = sourceViewModel.repository.progress(book.key)
        val index = book.episodes.indexOfFirst { it.url == progress.episodeUrl }
        PlayConfirmDialog(
            title = book.title,
            chapterCount = book.episodes.size,
            coverModel = book.coverUrl.takeIf { it.isNotBlank() }?.let(Uri::parse),
            resumeLabel = if (index >= 0) "继续收听第 ${index + 1} 集" else null,
            onDismiss = {
                pendingSource = null
                sourceViewModel.consumePendingDetail()
            },
            onPlay = {
                pendingSource = null
                sourceViewModel.consumePendingDetail()
                val startIndex = index.coerceAtLeast(0)
                context.startActivity(
                    android.content.Intent(context, TingshuPlayerActivity::class.java)
                        .putExtra("book", book.key)
                        .putExtra("index", startIndex)
                        .putExtra("position", progress.position),
                )
            },
        )
    }
    val detailChanged: (Boolean) -> Unit = {
        showingDetail = it
        onShowingDetailChanged(it)
    }
    androidx.activity.compose.BackHandler(destination != null && !showingDetail) { destination = null }
    LaunchedEffect(destination) { if (destination == null) detailChanged(false) }
    if (destination == null) {
        com.fluxplayer.app.feature.tingshu.ListeningLibraryHome(
            sources = sources,
            localBookCount = (localState.scanState as? DataState.Success)?.value?.size ?: localState.partialBooks.size,
            hasLocalPath = localState.rootUri != null,
            onLocalClick = {
                destination = "local"
            },
            localBooks = (localState.scanState as? DataState.Success)?.value ?: localState.partialBooks,
            localResume = localState.resumeStates,
            localChapterProgress = localState.chapterProgress,
            localLastPlayedAt = localState.lastPlayedAt,
            onSourceClick = { source ->
                sourceViewModel.open(source)
                destination = source.id
            },
            onRecentClick = { key ->
                sourceViewModel.openSavedBook(key)
                destination = "recent"
            },
            // 首页点书：统一弹播放确认窗（本地书与书源书籍都已有完整数据，无需再解析）
            onBookPick = { localBook, sourceBook ->
                if (localBook != null) pendingLocal = localBook else pendingSource = sourceBook
            },
            modifier = modifier,
        )
    } else {
        Column(modifier.fillMaxSize()) {
            if (!showingDetail) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.IconButton(onClick = { destination = null }) {
                        Icon(NextIcons.ArrowBack, "返回书库", tint = FluxTheme.colorScheme.onSurface)
                    }
                    Text(if (destination == "local") "本地书库" else sources.firstOrNull { it.id == destination }?.name.orEmpty(), style = FluxTheme.typography.titleMedium)
                }
            }
            if (destination == "local") {
                LocalAudiobookTabContent(
                    viewModel,
                    onPlayChapter,
                    detailChanged,
                    Modifier.weight(1f),
                )
            } else {
                TingshuSourceContent(onExit = { destination = null }, viewModel = sourceViewModel, modifier = Modifier.weight(1f), onShowingDetailChanged = detailChanged)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalAudiobookTabContent(
    viewModel: AudiobookViewModel = hiltViewModel(),
    onPlayChapter: (Uri, Uri?, Long, List<Uri>, Int) -> Unit = { _, _, _, _, _ -> },
    onShowingDetailChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 待确认播放的书籍：点击书籍只弹确认窗，确认后才起播
    var pendingBook by remember { mutableStateOf<AudioBook?>(null) }

    pendingBook?.let { book ->
        val resumeEntry = uiState.resumeStates[book.folderPath].orEmpty()
        val resumeIndex = resumeEntry.substringBefore('|').toIntOrNull()?.takeIf { it >= 0 }
        val uris = remember(book) { book.chapters.map { it.uri } }
        val startIndex = resumeIndex?.coerceIn(0, (uris.lastIndex).coerceAtLeast(0)) ?: 0
        val startMs = resumeEntry.substringAfter('|').toLongOrNull() ?: 0L
        PlayConfirmDialog(
            title = book.title,
            chapterCount = book.chapters.size,
            coverModel = book.coverUri,
            resumeLabel = resumeIndex?.let { "继续收听第 ${it + 1} 集" },
            onDismiss = { pendingBook = null },
            onPlay = {
                pendingBook = null
                uris.getOrNull(startIndex)?.let { uri ->
                    onPlayChapter(uri, book.coverUri, startMs, uris, startIndex)
                }
            },
        )
    }

    if (uiState.rootUri == null) {
        // 空状态：未选择听书目录
        EmptySelectionView(
            onSelectDirectory = {
            },
            modifier = modifier,
        )
    } else {
        when (val state = uiState.scanState) {
            is DataState.Loading -> {
                val partialBooks = uiState.partialBooks
                if (partialBooks.isEmpty()) {
                    // 尚未扫到任何书：全屏转圈
                    Box(
                        modifier = modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    // 已扫到部分书：立即展示书架，顶部提示扫描进度
                    Column(modifier = modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "扫描中… 已发现 ${partialBooks.size} 本",
                                style = FluxTheme.typography.bodySmall,
                                color = FluxTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        BookshelfList(
                            books = partialBooks,
                            onBookClick = { pendingBook = it },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            is DataState.Error -> {
                Box(
                    modifier = modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "扫描失败",
                            style = FluxTheme.typography.bodyLarge,
                            color = FluxTheme.colorScheme.error,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = viewModel::refresh) {
                            Text("重试")
                        }
                    }
                }
            }

            is DataState.Success -> {
                val books = state.value
                if (books.isEmpty()) {
                    EmptyBooksView(
                        onChangeDirectory = { },
                        modifier = modifier,
                    )
                } else {
                    // 按最近播放排序（未播放过的保持扫描顺序）
                    val sortedBooks = remember(books, uiState.lastPlayedAt) {
                        books.sortedWith(
                            compareByDescending<AudioBook> {
                                uiState.lastPlayedAt[it.folderPath] ?: 0L
                            },
                        )
                    }
                    PullToRefreshBox(
                        isRefreshing = uiState.isRefreshing,
                        onRefresh = { viewModel.refreshBooks() },
                        modifier = modifier,
                    ) {
                        BookshelfList(
                            books = sortedBooks,
                            resumeStates = uiState.resumeStates,
                            onBookClick = { pendingBook = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptySelectionView(
    onSelectDirectory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp),
        ) {
            // 图标
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = FluxTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(120.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Icon(
                        imageVector = NextIcons.Audio,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = FluxTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "开始你的听书之旅",
                style = FluxTheme.typography.headlineSmall,
                color = FluxTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "请在设置 → 听书配置中选择本地书库路径，系统将自动整理书籍",
                style = FluxTheme.typography.bodyMedium,
                color = FluxTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun EmptyBooksView(
    onChangeDirectory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "该目录下未找到书籍",
                style = FluxTheme.typography.bodyLarge,
                color = FluxTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "请确保每本书都在独立的子文件夹中",
                style = FluxTheme.typography.bodySmall,
                color = FluxTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("请到设置 → 听书配置更换路径", color = FluxTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun BookshelfList(
    books: List<AudioBook>,
    onBookClick: (AudioBook) -> Unit,
    resumeStates: Map<String, String> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(books, key = { it.folderPath }) { book ->
            AudiobookBookCard(
                book = book,
                resumeChapterIndex = resumeStates[book.folderPath]
                    ?.substringBefore('|')?.toIntOrNull(),
                onClick = { onBookClick(book) },
            )
        }
    }
}
