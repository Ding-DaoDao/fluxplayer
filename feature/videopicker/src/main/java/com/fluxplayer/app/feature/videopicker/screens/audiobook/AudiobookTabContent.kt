package com.fluxplayer.app.feature.videopicker.screens.audiobook

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.annotation.OptIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.fluxplayer.app.core.ui.base.DataState
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.R as coreUiR
import com.fluxplayer.app.core.ui.theme.FluxTheme
import com.fluxplayer.app.feature.videopicker.model.AudioBook

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookTabContent(
    viewModel: AudiobookViewModel = hiltViewModel(),
    onBookClick: (AudioBook) -> Unit,
    onPlayChapter: (Uri, Uri?, Long, List<Uri>, Int) -> Unit = { _, _, _, _, _ -> },
    onShowingDetailChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 选中的书籍（非 null 时显示详情页）
    var selectedBook by remember { mutableStateOf<AudioBook?>(null) }

    // 通知外层顶栏是否隐藏
    LaunchedEffect(selectedBook) {
        onShowingDetailChanged(selectedBook != null)
    }

    // SAF 目录选择器
    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { uri: Uri? ->
            if (uri != null) {
                // 将 SAF URI 持久化
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)

                // 保存到 DataStore
                viewModel.setRootUri(uri.toString())
            }
        },
    )

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
        Log.d("AudiobookTabContent", "bookPath=${book.folderPath}, resumeEntry=$resumeEntry, chapterProgressPairs keys=${chapterProgressPairs.keys}, total=${uiState.chapterProgress.size}")

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
                directoryPickerLauncher.launch(null)
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
                        onChangeDirectory = { directoryPickerLauncher.launch(null) },
                        modifier = modifier,
                    )
                } else {
                    PullToRefreshBox(
                        isRefreshing = uiState.isRefreshing,
                        onRefresh = { viewModel.refreshBooks() },
                        modifier = modifier,
                    ) {
                        BookshelfList(
                            books = books,
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
                text = "选择包含有声书的文件夹，系统将自动扫描并整理你的书籍",
                style = FluxTheme.typography.bodyMedium,
                color = FluxTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp,
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onSelectDirectory,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = FluxTheme.colorScheme.primary,
                ),
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(52.dp),
            ) {
                Text(
                    text = "选择文件夹",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
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
            Button(onClick = onChangeDirectory) {
                Text("重新选择")
            }
        }
    }
}

@Composable
private fun BookshelfList(
    books: List<AudioBook>,
    onBookClick: (AudioBook) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(books, key = { it.folderPath }) { book ->
            BookListItem(
                book = book,
                onClick = { onBookClick(book) },
            )
        }
    }
}

@Composable
private fun BookListItem(
    book: AudioBook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FluxTheme.colorScheme

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp, pressedElevation = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ── 封面缩略图 ──
            Box(
                modifier = Modifier
                    .size(width = 72.dp, height = 96.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (book.coverUri != null) {
                    // 显式限定解码尺寸（2x 显示尺寸），避免大封面全尺寸解码拖慢列表
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(book.coverUri)
                            .size(144, 192)
                            .build(),
                        contentDescription = book.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = NextIcons.Audio,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = colors.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            // ── 书籍信息 ──
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = book.title,
                    style = FluxTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 20.sp,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 章节数标签
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = colors.primary.copy(alpha = 0.1f),
                    ) {
                        Text(
                            text = "${book.chapterCount} 章节",
                            color = colors.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            // ── 右箭头 ──
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
