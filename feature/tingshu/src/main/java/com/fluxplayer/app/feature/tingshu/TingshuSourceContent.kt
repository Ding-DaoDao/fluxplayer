package com.fluxplayer.app.feature.tingshu

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.fluxplayer.app.core.tingshu.ListeningBook
import com.fluxplayer.app.core.tingshu.TingshuRepository
import com.fluxplayer.app.core.ui.components.FluxLinearProgressIndicator
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.theme.FluxTheme
import com.fluxplayer.app.feature.player.AudiobookBookCard
import com.fluxplayer.app.feature.player.AudiobookDetailContent
import com.fluxplayer.app.feature.player.model.AudioBook
import com.fluxplayer.app.feature.player.model.AudioChapter

@Composable
fun TingshuSourceContent(
    modifier: Modifier = Modifier,
    onShowingDetailChanged: (Boolean) -> Unit = {},
    onExit: () -> Unit = {},
    viewModel: TingshuViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var keyword by remember(state.source?.id) { mutableStateOf("") }
    LaunchedEffect(state.detail) { onShowingDetailChanged(state.detail != null) }
    DisposableEffect(Unit) { onDispose { onShowingDetailChanged(false) } }
    BackHandler(state.detail != null || state.canGoBack) {
        viewModel.back()
        if (state.detail != null && state.source == null) onExit()
    }
    val detail = state.detail
    if (detail != null) {
        SourceBookDetail(detail, viewModel.repository, {
            viewModel.back()
            if (state.source == null) onExit()
        }, modifier)
        return
    }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.source?.isCloudLibrary != true) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    placeholder = { Text("搜索书籍", style = FluxTheme.typography.bodyMedium) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    leadingIcon = {
                        Icon(
                            imageVector = NextIcons.Search,
                            contentDescription = null,
                            tint = FluxTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = FluxTheme.colorScheme.primary,
                        unfocusedBorderColor = FluxTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { viewModel.search(keyword) },
                    enabled = !state.loading && keyword.isNotBlank(),
                ) { Text("搜索") }
            }
        }
        if (state.loading) {
            FluxLinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
        }
        state.error?.let { message ->
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = FluxTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Row(
                    Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        message,
                        style = FluxTheme.typography.bodySmall,
                        color = FluxTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = viewModel::dismissError) { Text("关闭") }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (state.canGoBack) {
                        TextButton(onClick = viewModel::back) { Text("上一级") }
                    }
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(if (state.canGoBack) state.title else "全部书籍", style = FluxTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = FluxTheme.colorScheme.onSurface)
                        if (state.books.isNotEmpty()) {
                            Text("已加载 ${state.books.size} 本", style = FluxTheme.typography.bodySmall, color = FluxTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (!state.canGoBack) {
                        TextButton(onClick = { state.source?.let(viewModel::open) }, enabled = !state.loading) { Text("刷新") }
                    }
                }
            }
            state.menus.forEach { menu ->
                item(key = "menu-${menu.title}") {
                    Text(
                        text = menu.title,
                        style = FluxTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = FluxTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                    )
                }
                items(menu.tabs) { tab ->
                    AudiobookBookCard(
                        book = AudioBook(tab.title.ifBlank { "书源目录" }, tab.url, null, 0, emptyList()),
                        subtitle = "浏览书籍",
                        onClick = { if (!state.loading) viewModel.category(tab.url, tab.title.ifBlank { "书源目录" }) },
                    )
                }
            }
            items(state.books) { book ->
                AudiobookBookCard(
                    book = AudioBook(book.title, book.bookUrl, null, 0, emptyList()),
                    coverModel = rememberSourceCover(state.source!!.id, book.coverUrl, viewModel.repository),
                    subtitle = listOf(book.author, book.artist).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "查看章节" },
                    onClick = { if (!state.loading) viewModel.detail(book) },
                )
            }
            if (state.books.isEmpty() && state.menus.isEmpty() && !state.loading) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(FluxTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = NextIcons.Headset,
                                contentDescription = null,
                                tint = FluxTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "没有找到书籍",
                            style = FluxTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = FluxTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (state.source?.isCloudLibrary == true) "请在设置 → 听书配置中检查登录状态和书库目录" else "换个关键词试试，或到听书配置中导入更多书源",
                            style = FluxTheme.typography.bodySmall,
                            color = FluxTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            if (state.page < state.totalPages && (state.query.isNotBlank() || state.nextUrl.isNotBlank())) {
                item {
                    TextButton(onClick = viewModel::nextPage, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("加载更多书籍") }
                }
            }
        }
    }
}

@Composable
private fun SourceBookDetail(book: ListeningBook, repository: TingshuRepository, onBack: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val progresses by repository.progresses.collectAsStateWithLifecycle()
    val progress = progresses[book.key] ?: repository.progress(book.key)
    val storedIndex = book.episodes.indexOfFirst { it.url == progress.episodeUrl }
    val uiBook = remember(book) {
        AudioBook(
            book.title,
            book.key,
            book.coverUrl.takeIf { it.isNotBlank() }?.let(Uri::parse),
            book.episodes.size,
            book.episodes.map { AudioChapter(it.title, Uri.parse(it.url), 0) },
        )
    }
    AudiobookDetailContent(
        book = uiBook,
        onBackClick = onBack,
        onChapterClick = { chapter, position ->
            val index = uiBook.chapters.indexOf(chapter)
            context.startActivity(Intent(context, TingshuPlayerActivity::class.java).putExtra("book", book.key).putExtra("index", index).putExtra("position", position))
        },
        resumeChapterIndex = storedIndex.takeIf { it >= 0 },
        resumePositionMs = progress.position,
        chapterProgress = remember(book, progresses) { repository.chapterProgress(book) },
        coverModel = rememberSourceCover(book.sourceId, book.coverUrl, repository),
        intro = book.intro,
        modifier = modifier,
    )
}

@Composable
internal fun rememberSourceCover(sourceId: String, url: String, repository: TingshuRepository): ImageRequest? {
    val context = LocalContext.current
    var request by remember(sourceId, url) { mutableStateOf<ImageRequest?>(null) }
    LaunchedEffect(sourceId, url) {
        if (url.isBlank()) return@LaunchedEffect
        val headers = try {
            repository.coverHeaders(sourceId, url)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
        request = ImageRequest.Builder(context).data(url).httpHeaders(
            NetworkHeaders.Builder().apply { headers.forEach { (key, value) -> set(key, value) } }.build(),
        ).build()
    }
    return request
}
