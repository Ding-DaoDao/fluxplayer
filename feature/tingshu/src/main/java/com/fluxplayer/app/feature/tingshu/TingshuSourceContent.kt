package com.fluxplayer.app.feature.tingshu

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.request.ImageRequest
import com.fluxplayer.app.core.tingshu.ListeningBook
import com.fluxplayer.app.core.tingshu.TingshuRepository
import com.fluxplayer.app.core.ui.components.MediaPageLoading as AudiobookLoadingIndicator
import com.fluxplayer.app.core.ui.components.MediaPullToRefreshBox as PullToRefreshBox
import com.fluxplayer.app.core.ui.components.MediaTextField as OutlinedTextField
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.theme.FluxTheme
import com.fluxplayer.app.feature.player.AudiobookBookCard
import com.fluxplayer.app.feature.player.PlayConfirmDialog
import com.fluxplayer.app.feature.player.model.AudioBook
import com.github.eprendre.tingshu.utils.Book

@Composable
fun TingshuSourceContent(
    modifier: Modifier = Modifier,
    onShowingDetailChanged: (Boolean) -> Unit = {},
    onExit: () -> Unit = {},
    viewModel: TingshuViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 待确认播放的书源书籍：点击书籍先弹确认窗；弹窗打开时后台预解析详情以显示集数
    var pendingBook by remember(state.source?.id) { mutableStateOf<Book?>(null) }
    val pendingDetail = state.pendingDetail
    var searchText by remember(state.source?.id) { mutableStateOf("") }

    pendingBook?.let { target ->
        val resolved = pendingDetail?.takeIf { it.url == target.bookUrl }
        val progress = resolved?.let { viewModel.repository.progress(it.key) }
        PlayConfirmDialog(
            title = resolved?.title?.takeIf { it.isNotBlank() } ?: target.title,
            chapterCount = resolved?.episodes?.size ?: 0,
            coverModel = rememberSourceCover(state.source?.id.orEmpty(), resolved?.coverUrl?.takeIf { it.isNotBlank() } ?: target.coverUrl, viewModel.repository, state.pendingCoverRevision),
            resumeLabel = when {
                state.error != null -> null
                resolved == null -> null
                progress != null && progress.episodeUrl.isNotBlank() -> resolved.episodes.indexOfFirst { it.url == progress.episodeUrl }
                    .takeIf { it >= 0 }?.let { "继续收听第 ${it + 1} 集" }
                else -> null
            },
            loading = state.resolvingBook,
            onRefresh = { viewModel.resolvePending(target, refresh = true) },
            playEnabled = resolved != null && !state.resolvingBook && state.error == null,
            errorContent = state.error?.let { message ->
                { SourceErrorNotice(message, onRetry = { viewModel.resolvePending(target) }) }
            },
            onDismiss = {
                pendingBook = null
                viewModel.consumePendingDetail()
            },
            onPlay = {
                pendingBook = null
                viewModel.consumePendingDetail()
                if (resolved != null) {
                    playBook(context, viewModel.repository, resolved)
                } else {
                    // 预解析未就绪，等解析完再起播
                    viewModel.resolveForPlayback(target) { book -> playBook(context, viewModel.repository, book) }
                }
            },
        )
    }
    // 书籍详情页已下线：点书统一走播放确认弹窗，这里只保留分类层级返回
    DisposableEffect(Unit) { onDispose { onShowingDetailChanged(false) } }
    BackHandler(state.canGoBack) {
        viewModel.back()
        if (state.source == null) onExit()
    }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.source?.let { !it.id.startsWith("jdr:") || "search" in it.capabilities } == true) {
            OutlinedTextField(
                value = searchText,
                onValueChange = { searchText = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                label = { Text("搜索书名、关键词或视频链接") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search(searchText) }),
                trailingIcon = {
                    TextButton(onClick = { viewModel.search(searchText) }, enabled = searchText.isNotBlank() && !state.loading) {
                        Text("搜索")
                    }
                },
            )
        }
        if (state.shortcuts.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.shortcuts, key = { it.id }) { shortcut ->
                    FilterChip(
                        selected = state.searchKeyword == null && state.directoryId == shortcut.id,
                        onClick = { viewModel.browseDirectory(shortcut.id, shortcut.name, shortcut = true) },
                        enabled = !state.loading,
                        label = { Text(shortcut.name) },
                    )
                }
            }
        }
        if (pendingBook == null) {
            state.error?.let { message ->
                SourceErrorNotice(message, Modifier.padding(horizontal = 16.dp), onRetry = viewModel::retry, onDismiss = viewModel::dismissError)
            }
        }
        val listState = rememberLazyListState()
        LaunchedEffect(state.searchKeyword, state.directoryId) { listState.scrollToItem(0) }
        // 触底自动加载下一页：提前 3 项预加载，滚到底后不再触发
        LaunchedEffect(listState, state.books.size + state.folders.size, state.nextUrl, state.totalPages, state.browseNextPage, state.page, state.loading) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .collect { lastVisible ->
                    val total = listState.layoutInfo.totalItemsCount
                    if (total > 0 && lastVisible >= total - 3) viewModel.loadMoreIfNeeded()
                }
        }
        PullToRefreshBox(
            isRefreshing = state.refreshing && pendingBook == null,
            onRefresh = { if (pendingBook == null && !state.loading) viewModel.refresh() },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            ) {
                item(key = "header") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (state.canGoBack) {
                            TextButton(onClick = viewModel::back) { Text("上一级") }
                        }
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(if (state.canGoBack) state.title else state.shortcuts.firstOrNull { it.id == state.directoryId }?.name ?: "全部书籍", style = FluxTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = FluxTheme.colorScheme.onSurface)
                            if (state.books.isNotEmpty()) {
                                Text(
                                    "已加载 ${state.books.size} 本",
                                    style = FluxTheme.typography.bodySmall,
                                    color = FluxTheme.colorScheme.onSurfaceVariant,
                                )
                            }
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
                items(state.folders, key = { "folder-${it.id}" }) { folder ->
                    TextButton(onClick = { viewModel.browseDirectory(folder.id, folder.name) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                        Text("目录 · ${folder.name}", modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE), maxLines = 1)
                    }
                }
                if (state.shortcuts.isNotEmpty()) {
                    items(state.books.chunked(3), key = { row -> "books-${row.first().bookUrl}" }) { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { book ->
                                SourceBookTile(
                                    book = book,
                                    cover = rememberSourceCover(state.source!!.id, book.coverUrl, viewModel.repository),
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        if (!state.loading) {
                                            viewModel.resolvePending(book)
                                            pendingBook = book
                                        }
                                    },
                                )
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                } else {
                    items(state.books) { book ->
                        AudiobookBookCard(
                            book = AudioBook(book.title, book.bookUrl, null, 0, emptyList()),
                            coverModel = rememberSourceCover(state.source!!.id, book.coverUrl, viewModel.repository),
                            subtitle = listOf(book.author, book.artist).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "查看章节" },
                            onClick = {
                                if (!state.loading) {
                                    viewModel.resolvePending(book)
                                    pendingBook = book
                                }
                            },
                        )
                    }
                }
                if (state.books.isEmpty() && state.folders.isEmpty() && state.menus.isEmpty() && !state.loading) {
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
                                if (state.searchKeyword != null) "试试其他关键词，或在书源配置中关闭有声筛选" else if (state.source?.isCloudLibrary == true) "请在设置 → 听书配置中检查登录状态和书库目录" else "请到听书配置中检查书源，或导入更多书源",
                                style = FluxTheme.typography.bodySmall,
                                color = FluxTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                if (state.loadingMore) {
                    item(key = "loading-more") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            AudiobookLoadingIndicator("正在加载更多…")
                        }
                    }
                }
            }
            if (state.loading && !state.refreshing && !state.loadingMore && pendingBook == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AudiobookLoadingIndicator("正在加载书库…")
                }
            }
        }
    }
}

/**
 * 直接启动播放：从续播记录定位章节，无记录则从第 1 章开始。
 * 书籍详情页已被绕过，这里负责"从哪一章开始听"的决策。
 */
private fun playBook(context: android.content.Context, repository: TingshuRepository, book: ListeningBook) {
    if (book.episodes.isEmpty()) return
    val progress = repository.progress(book.key)
    val index = book.episodes.indexOfFirst { it.url == progress.episodeUrl }.coerceAtLeast(0)
    context.startActivity(
        Intent(context, TingshuPlayerActivity::class.java)
            .putExtra("book", book.key)
            .putExtra("index", index)
            .putExtra("position", progress.position),
    )
}

@Composable
internal fun rememberSourceCover(sourceId: String, url: String, repository: TingshuRepository, refreshRevision: Long = 0): ImageRequest? {
    val context = LocalContext.current
    val cacheEnabled by com.fluxplayer.app.core.ui.cache.BookCoverCache.enabled(context).collectAsStateWithLifecycle()
    val coverRevision by com.fluxplayer.app.core.tingshu.ListeningCoverStore.revision.collectAsStateWithLifecycle()
    return remember(context, sourceId, url, repository, refreshRevision, cacheEnabled, coverRevision) {
        if (url.isBlank()) return@remember null
        ImageRequest.Builder(context).data(SourceCover(url, refreshRevision > 0, sourceId))
            .placeholderMemoryCacheKey("$sourceId:$url")
            .apply {
                if (refreshRevision > 0) {
                    memoryCachePolicy(coil3.request.CachePolicy.WRITE_ONLY)
                    diskCachePolicy(if (cacheEnabled) coil3.request.CachePolicy.WRITE_ONLY else coil3.request.CachePolicy.DISABLED)
                }
            }
            .diskCacheKey("$sourceId:$url").memoryCacheKey("$sourceId:$url:$coverRevision")
            .fetcherFactory(SourceCoverFetcher { repository.resolveCover(sourceId, url, forceRefresh = refreshRevision > 0) }).build()
    }
}
