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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.fluxplayer.app.core.ui.base.DataState
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.feature.videopicker.model.AudioBook

@Composable
fun AudiobookTabContent(
    viewModel: AudiobookViewModel = hiltViewModel(),
    onBookClick: (AudioBook) -> Unit,
    onPlayChapter: (Uri, Uri?, Long, List<Uri>, Int) -> Unit = { _, _, _, _, _ -> },
    onShowingDetailChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
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
                Box(
                    modifier = modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
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
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
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
                    BookshelfList(
                        books = books,
                        onBookClick = { selectedBook = it },
                        modifier = modifier,
                    )
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
        ) {
            Icon(
                imageVector = NextIcons.Audio,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "选择听书文件夹",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "选择包含多本书籍的顶层文件夹",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onSelectDirectory) {
                Text("选择目录")
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
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "请确保每本书都在独立的子文件夹中",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        contentPadding = PaddingValues(12.dp),
    ) {
        items(books, key = { it.folderPath }) { book ->
            BookRow(
                book = book,
                onClick = { onBookClick(book) },
            )
        }
    }
}

@Composable
private fun BookRow(
    book: AudioBook,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 封面
        Box(
            modifier = Modifier
                .size(width = 80.dp, height = 105.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (book.coverUri != null) {
                AsyncImage(
                    model = book.coverUri,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = NextIcons.Audio,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // 右侧信息
        Column(modifier = Modifier.weight(1f)) {
            // 书名 - 跑马灯
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(),
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 共X集
            Text(
                text = "共 ${book.chapterCount} 集",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
