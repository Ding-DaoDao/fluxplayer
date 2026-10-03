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
import com.fluxplayer.app.core.ui.base.DataState
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.theme.FluxTheme
import com.fluxplayer.app.feature.player.AudiobookBookCard
import com.fluxplayer.app.feature.player.AudiobookDetailContent
import com.fluxplayer.app.feature.tingshu.TingshuSourceContent
import com.fluxplayer.app.feature.tingshu.TingshuViewModel
import com.fluxplayer.app.feature.videopicker.model.AudioBook

@Composable
fun AudiobookTabContent(
    viewModel: AudiobookViewModel = hiltViewModel(),
    onBookClick: (AudioBook) -> Unit,
    onPlayChapter: (Uri, Uri?, Long, List<Uri>, Int) -> Unit = { _, _, _, _, _ -> },
    onShowingDetailChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val sourceViewModel: TingshuViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val sources by sourceViewModel.repository.sources.collectAsStateWithLifecycle()
    val localState by viewModel.uiState.collectAsStateWithLifecycle()
    var destination by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var showingDetail by remember { mutableStateOf(false) }
    var recentLocalBook by remember { mutableStateOf<AudioBook?>(null) }
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
                recentLocalBook = null
                destination = "local"
            },
            localBooks = (localState.scanState as? DataState.Success)?.value ?: localState.partialBooks,
            localResume = localState.resumeStates,
            localChapterProgress = localState.chapterProgress,
            localLastPlayedAt = localState.lastPlayedAt,
            onLocalRecentClick = { book ->
                recentLocalBook = book
                destination = "local"
            },
            onSourceClick = { source ->
                sourceViewModel.open(source)
                destination = source.id
            },
            onRecentClick = { key ->
                sourceViewModel.openSavedBook(key)
                destination = "recent"
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
                LocalAudiobookTabContent(viewModel, onBookClick, onPlayChapter, detailChanged, Modifier.weight(1f), recentLocalBook)
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
    onBookClick: (AudioBook) -> Unit,
    onPlayChapter: (Uri, Uri?, Long, List<Uri>, Int) -> Unit = { _, _, _, _, _ -> },
    onShowingDetailChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
    initialBook: AudioBook? = null,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 选中的书籍（非 null 时显示详情页）
    var selectedBook by remember(initialBook) { mutableStateOf(initialBook) }

    // 通知外层顶栏是否隐藏
    LaunchedEffect(selectedBook) {
        onShowingDetailChanged(selectedBook != null)
    }

    // 详情页
    if (selectedBook != null) {
        val book = selectedBook!!
        // 解析续播状态
        val resumeEntry = uiState.resumeStates[book.folderPath]
        val resumeChapterIndex = resumeEntry?.substringBefore('|')?.toIntOrNull()
        val resumePositionMs = resumeEntry?.substringAfter('|')?.toLongOrNull() ?: 0L
        // 解析该书各章节进度: key="bookPath|chapterIndex" → "positionMs|durationMs"
        val bookPrefix = "${book.folderPath}|"
        val chapterProgressPairs = uiState.chapterProgress
            .filterKeys { it.startsWith(bookPrefix) }
            .mapKeys { (k, _) -> k.removePrefix(bookPrefix).toIntOrNull() ?: -1 }
            .filterKeys { it >= 0 }
            .mapValues { (_, v) ->
                val parts = v.split("|")
                (parts.getOrNull(0)?.toLongOrNull() ?: 0L) to (parts.getOrNull(1)?.toLongOrNull() ?: 0L)
            }

        AudiobookDetailContent(
            book = book,
            onBackClick = { selectedBook = null },
            onChapterClick = { chapter, startMs ->
                val chapterUris = book.chapters.map { it.uri }
                val startIndex = book.chapters.indexOfFirst { it.uri == chapter.uri }.coerceAtLeast(0)
                onPlayChapter(chapter.uri, book.coverUri, startMs, chapterUris, startIndex)
            },
            resumeChapterIndex = resumeChapterIndex,
            resumePositionMs = resumePositionMs,
            chapterProgress = chapterProgressPairs,
            modifier = Modifier.fillMaxSize(),
        )
        return
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
                            onBookClick = { selectedBook = it },
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
                            onBookClick = { selectedBook = it },
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
