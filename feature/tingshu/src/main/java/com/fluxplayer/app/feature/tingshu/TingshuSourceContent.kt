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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.fluxplayer.app.core.common.NaturalOrder
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
        // 排序只是展示层重排：切规则不重新请求书源，翻页追加的数据也会一起参与排序
        val visibleBooks = remember(state.books, state.sortMode) {
            state.books.sortedForDisplay(state.sortMode)
        }
        // 换目录、换关键词、切排序或切视图都回到顶部：重排后停在中间会像「没生效」
        LaunchedEffect(state.searchKeyword, state.directoryId, state.sortMode, state.viewMode) { listState.scrollToItem(0) }
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
                        // 排序 / 视图切换只对书籍列表有意义，没有书时不占位置
                        if (state.books.isNotEmpty()) {
                            SourceSortButton(current = state.sortMode, onSelect = viewModel::setSortMode)
                            IconButton(onClick = viewModel::toggleViewMode) {
                                Icon(
                                    imageVector = if (state.viewMode == BookViewMode.GRID) NextIcons.ViewAgenda else NextIcons.GridView,
                                    contentDescription = if (state.viewMode == BookViewMode.GRID) "切换为列表" else "切换为网格",
                                    tint = FluxTheme.colorScheme.onSurfaceVariant,
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
                if (state.viewMode == BookViewMode.GRID) {
                    items(visibleBooks.chunked(3), key = { row -> "books-${row.first().bookUrl}" }) { row ->
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
                    items(visibleBooks) { book ->
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
            .fetcherFactory(SourceCoverFetcher { retry -> repository.resolveCover(sourceId, url, forceRefresh = refreshRevision > 0 || retry) }).build()
    }
}

/**
 * 「全部书籍」那一行右侧的排序入口：点开是下拉菜单，选中即生效。
 * 高亮当前规则；DEFAULT 时图标用次要色，避免让人以为列表被强制排过。
 */
@Composable
private fun SourceSortButton(current: BookSortMode, onSelect: (BookSortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = NextIcons.Sort,
                contentDescription = "排序：${current.label}",
                tint = if (current == BookSortMode.DEFAULT) FluxTheme.colorScheme.onSurfaceVariant else FluxTheme.colorScheme.primary,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            BookSortMode.values().forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = mode.label,
                            color = if (mode == current) FluxTheme.colorScheme.primary else FluxTheme.colorScheme.onSurface,
                        )
                    },
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    },
                )
            }
        }
    }
}

/**
 * 展示层排序，纯本地重排、不重新请求书源。
 *
 * DEFAULT 保持源返回顺序——jar 源可以在自己的配置里排好序，这里不去覆盖它。
 * 其余规则只依赖 App 能拿到的字段：jdr 源把 sortSize/sortTime 塞在 jdrExtras 里，
 * jar 源的 Book 没有这两个字段，取不到时按 0 参与排序（等于保持原序）。
 *
 * 注意：网盘里的"书"通常是文件夹，接口给的 size 多为 0，
 * 所以「大小」这两项实际只对散落的单音频书有效——网盘网页版对文件夹也只显示 "-"。
 */
private fun List<Book>.sortedForDisplay(mode: BookSortMode): List<Book> = when (mode) {
    BookSortMode.DEFAULT -> this
    BookSortMode.NAME_ASC -> sortedWith(compareBy(NaturalOrder.fileNameComparator) { it.title })
    BookSortMode.NAME_DESC -> sortedWith(compareByDescending(NaturalOrder.fileNameComparator) { it.title })
    BookSortMode.NEWEST -> sortedByDescending { it.jdrSortValue("sortTime") }
    BookSortMode.OLDEST -> sortedBy { it.jdrSortValue("sortTime") }
    BookSortMode.LARGEST -> sortedByDescending { it.jdrSortValue("sortSize") }
    BookSortMode.SMALLEST -> sortedBy { it.jdrSortValue("sortSize") }
}

/**
 * 从 jdrExtras 里取排序元数据；jar 源没有这个字段，返回 0。
 *
 * JS 侧 `Number(...)` 序列化出来可能是 `1.7e12` 这种科学计数法或带小数点，
 * 所以先按整数读，失败再按浮点读，两个都不行才算 0。
 */
private fun Book.jdrSortValue(key: String): Long {
    if (jdrExtras.isBlank() || jdrExtras == "{}") return 0L
    return runCatching {
        val obj = Json.parseToJsonElement(jdrExtras) as? JsonObject ?: return@runCatching 0L
        val raw = (obj[key] as? JsonPrimitive)?.content ?: return@runCatching 0L
        raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong() ?: 0L
    }.getOrDefault(0L)
}
