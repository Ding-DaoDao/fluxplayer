package com.fluxplayer.app.feature.tingshu

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil3.compose.AsyncImage
import com.fluxplayer.app.core.tingshu.ListeningBook
import com.fluxplayer.app.core.tingshu.ListeningHistoryVisibility
import com.fluxplayer.app.core.tingshu.ListeningProgress
import com.fluxplayer.app.core.tingshu.ListeningResumeAction
import com.fluxplayer.app.core.tingshu.ListeningSource
import com.fluxplayer.app.core.tingshu.TingshuRepository
import com.fluxplayer.app.core.tingshu.listeningResumeAction
import com.fluxplayer.app.core.ui.cache.rememberBookCoverImageLoader
import com.fluxplayer.app.core.ui.components.FluxLinearProgressIndicator
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.theme.FluxTheme
import com.fluxplayer.app.feature.player.LocalAudiobookPlayback
import com.fluxplayer.app.feature.player.PlayerActivity
import com.fluxplayer.app.feature.player.model.AudioBook
import com.fluxplayer.app.feature.player.service.PlayerService
import com.github.eprendre.tingshu.utils.Episode

private val HomeShapeLarge = RoundedCornerShape(24.dp)
private val HomeShapeMedium = RoundedCornerShape(18.dp)
private val HomeShapeSmall = RoundedCornerShape(12.dp)

/**
 * 听书书库首页：继续收听 → 书库与书源 → 最近听过。
 *
 * 书源导入/配置统一在「设置 → 听书配置」，本页不再提供配置入口。
 * 配色与排版全部取自 [FluxTheme]，MD3 / MIUIX 双引擎自动适配。
 */
@Composable
fun ListeningLibraryHome(
    sources: List<ListeningSource>,
    localBookCount: Int,
    hasLocalPath: Boolean,
    onLocalClick: () -> Unit,
    onSourceClick: (ListeningSource) -> Unit,
    modifier: Modifier = Modifier,
    localBooks: List<AudioBook> = emptyList(),
    localResume: Map<String, String> = emptyMap(),
    localChapterProgress: Map<String, String> = emptyMap(),
    localLastPlayedAt: Map<String, Long> = emptyMap(),
) {
    val context = LocalContext.current
    val activity = remember(context) {
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<androidx.activity.ComponentActivity>().first()
    }
    val backgroundPlayer: ListeningBackgroundPlayer = androidx.lifecycle.viewmodel.compose.viewModel(viewModelStoreOwner = activity)
    val preferences = remember { context.getSharedPreferences("listening_home", android.content.Context.MODE_PRIVATE) }
    var grid by rememberSaveable { mutableStateOf(preferences.getBoolean("grid", true)) }
    var historyVisibility by remember {
        mutableStateOf(
            ListeningHistoryVisibility(
                removedAt = preferences.all.mapNotNull { (key, value) ->
                    if (key.startsWith("removed.") && value is Long) key.removePrefix("removed.") to value else null
                }.toMap(),
                clearedAt = preferences.getLong("historyClearedAt", -1L),
            ),
        )
    }
    var removingBook by remember { mutableStateOf<ListeningBook?>(null) }
    var clearingHistory by remember { mutableStateOf(false) }
    if (removingBook != null || clearingHistory) {
        AlertDialog(
            onDismissRequest = {
                removingBook = null
                clearingHistory = false
            },
            title = { Text(if (clearingHistory) "清空最近听过？" else "删除收听记录？") },
            text = { Text(if (clearingHistory) "所有最近收听记录将被清空，书籍和收听进度会保留。" else "从最近听过中移除“${removingBook?.title}”，书籍和收听进度会保留。") },
            confirmButton = {
                TextButton(onClick = {
                    val now = System.currentTimeMillis()
                    if (clearingHistory) {
                        historyVisibility = historyVisibility.clear(now)
                        val editor = preferences.edit().putLong("historyClearedAt", now)
                        preferences.all.keys.filter { it.startsWith("removed.") }.forEach(editor::remove)
                        editor.apply()
                    } else {
                        removingBook?.key?.let { key ->
                            historyVisibility = historyVisibility.remove(key, now)
                            preferences.edit().putLong("removed.$key", now).apply()
                        }
                    }
                    removingBook = null
                    clearingHistory = false
                }) { Text(if (clearingHistory) "清空" else "删除") }
            },
            dismissButton = {
                TextButton(onClick = {
                    removingBook = null
                    clearingHistory = false
                }) { Text("取消") }
            },
        )
    }
    val repository = remember { TingshuRepository.get(context) }
    val resumeBook: (AudioBook?, ListeningBook) -> Unit = { local, source ->
        if (local != null) {
            val saved = localResume[local.folderPath].orEmpty()
            val index = (saved.substringBefore('|').toIntOrNull() ?: 0).coerceIn(0, local.chapters.lastIndex.coerceAtLeast(0))
            local.chapters.getOrNull(index)?.let { chapter ->
                backgroundPlayer.start(
                    context,
                    ComponentName(context, PlayerService::class.java),
                    com.fluxplayer.app.feature.player.service.CustomCommands.START_AUDIOBOOK.sessionCommand,
                    android.os.Bundle().apply {
                        putString("chapter_uri", chapter.uri.toString())
                        putString("cover_uri", local.coverUri?.toString())
                        putString("book_title", local.title)
                        putLong("position", (saved.substringAfter('|').toLongOrNull() ?: 0L).coerceAtLeast(0L))
                    },
                )
            }
        } else {
            val saved = repository.progress(source.key)
            val index = source.episodes.indexOfFirst { it.url == saved.episodeUrl }.coerceAtLeast(0)
            backgroundPlayer.start(
                context,
                ComponentName(context, TingshuPlaybackService::class.java),
                androidx.media3.session.SessionCommand(ListeningPlayback.SELECT, android.os.Bundle.EMPTY),
                android.os.Bundle().apply {
                    putString("book", source.key)
                    putInt("index", index)
                    putLong("position", saved.position.coerceAtLeast(0L))
                },
            )
        }
    }
    val recentBooks by repository.recentBooks.collectAsStateWithLifecycle()
    val progresses by repository.progresses.collectAsStateWithLifecycle()
    val playback by ListeningPlayback.state.collectAsStateWithLifecycle()
    val localPlayback by LocalAudiobookPlayback.state.collectAsStateWithLifecycle()
    val showLocal = localPlayback.bookPath.isNotBlank() && (
        playback.book == null ||
            (localPlayback.playing && !playback.playing) ||
            (localPlayback.playing == playback.playing && localPlayback.lastPlayedAt >= playback.lastPlayedAt)
        )
    val service = when {
        showLocal -> PlayerService::class.java
        playback.book != null -> TingshuPlaybackService::class.java
        else -> null
    }
    val controller = rememberHomeController(service?.let { ComponentName(context, it) })
    val localEntries = remember(localBooks, localResume, localChapterProgress, localLastPlayedAt, localPlayback.bookPath, localPlayback.lastPlayedAt) {
        localBooks.filter { it.folderPath in localResume || it.folderPath in localLastPlayedAt || it.folderPath == localPlayback.bookPath }.map { book ->
            val resume = localResume[book.folderPath].orEmpty().split('|')
            val index = resume.firstOrNull()?.toIntOrNull() ?: 0
            val position = resume.getOrNull(1)?.toLongOrNull() ?: 0L
            val duration = localChapterProgress["${book.folderPath}|$index"]?.substringAfter('|')?.toLongOrNull() ?: 0L
            HomeRecentBook(
                book = ListeningBook("local:${book.folderPath}", "local", book.folderPath, book.title, book.coverUri?.toString().orEmpty(), "", book.chapters.map { Episode(it.title, it.uri.toString()) }),
                progress = ListeningProgress(book.chapters.getOrNull(index)?.uri?.toString().orEmpty(), position, duration),
                playedAt = maxOf(localLastPlayedAt[book.folderPath] ?: 0L, if (book.folderPath == localPlayback.bookPath) localPlayback.lastPlayedAt else 0L),
                localBook = book,
            )
        }
    }
    val entries = (localEntries + recentBooks.map { HomeRecentBook(it, progresses[it.key], repository.lastPlayedAt(it.key)) })
        .filter { historyVisibility.contains(it.book.key, it.playedAt) }
        .sortedByDescending { it.playedAt }.take(20)
    val colors = FluxTheme.colorScheme

    // ── 正在播放横幅 ──
    // 内存播放状态（ListeningPlayback / LocalAudiobookPlayback）是纯进程内的，App 被杀后归零；
    // 此时回落到最近收听记录渲染横幅，保证「继续收听」入口常驻。
    val liveLocal = localPlayback.bookPath.isNotBlank()
    val liveSource = playback.book != null
    val lastEntry = entries.firstOrNull { it.playedAt > 0L }
    val bannerMode = when {
        liveLocal && (showLocal) -> HomeBannerMode.Local
        liveSource -> HomeBannerMode.Source
        lastEntry != null -> HomeBannerMode.Resume
        else -> null
    }

    ListeningHomeGrid(grid = grid, modifier = modifier) { layoutIsGrid ->
        (backgroundPlayer.error ?: playback.error?.takeUnless { showLocal })?.let { message ->
            item(key = "resume-error", span = { GridItemSpan(maxLineSpan) }) {
                SourceErrorNotice(message, onDismiss = if (backgroundPlayer.error != null) backgroundPlayer::dismissError else null)
            }
        }
        if (bannerMode != null) {
            item(key = "now-playing", span = { GridItemSpan(maxLineSpan) }) {
                val entry = lastEntry
                val isResume = bannerMode == HomeBannerMode.Resume
                // 恢复态取持久化的章节/进度，直播态取内存实时值
                val title = when (bannerMode) {
                    HomeBannerMode.Local -> localPlayback.title
                    HomeBannerMode.Source -> playback.book?.title.orEmpty()
                    HomeBannerMode.Resume -> entry?.book?.title.orEmpty()
                }
                val episodeTitle = when (bannerMode) {
                    HomeBannerMode.Local -> localPlayback.chapterTitle
                    HomeBannerMode.Source -> playback.book?.episodes?.getOrNull(playback.index)?.title.orEmpty()
                    HomeBannerMode.Resume -> entry?.let { resolveEpisodeTitle(it) }.orEmpty()
                }
                val position = when (bannerMode) {
                    HomeBannerMode.Local -> localPlayback.position
                    HomeBannerMode.Source -> playback.position
                    HomeBannerMode.Resume -> entry?.progress?.position ?: 0L
                }
                val duration = when (bannerMode) {
                    HomeBannerMode.Local -> localPlayback.duration
                    HomeBannerMode.Source -> playback.duration
                    HomeBannerMode.Resume -> entry?.progress?.duration ?: 0L
                }
                val coverModel = when (bannerMode) {
                    HomeBannerMode.Local -> localPlayback.coverUri
                    HomeBannerMode.Source -> playback.book?.let { rememberSourceCover(it.sourceId, it.coverUrl, repository) }
                    HomeBannerMode.Resume -> entry?.let { resumeCover(it, repository) }
                }
                val openPlayer = {
                    when (bannerMode) {
                        HomeBannerMode.Local -> context.startActivity(
                            Intent(context, PlayerActivity::class.java).apply {
                                data = Uri.parse(localPlayback.chapterUri)
                                putExtra("audio_only", true)
                                putExtra("cover_uri", localPlayback.coverUri)
                                putExtra("reopen_audiobook", true)
                            },
                        )
                        HomeBannerMode.Source -> context.startActivity(
                            Intent(context, TingshuPlayerActivity::class.java).putExtra("reopen", true),
                        )
                        HomeBannerMode.Resume -> entry?.let { saved ->
                            val progress = saved.progress
                            val index = saved.book.episodes.indexOfFirst { it.url == progress?.episodeUrl }.coerceAtLeast(0)
                            if (saved.localBook != null) {
                                saved.localBook.chapters.getOrNull(index)?.let { chapter ->
                                    context.startActivity(
                                        Intent(context, PlayerActivity::class.java).apply {
                                            action = Intent.ACTION_VIEW
                                            data = chapter.uri
                                            putExtra("audio_only", true)
                                            putExtra("cover_uri", saved.localBook.coverUri?.toString())
                                            putExtra("start_position_ms", progress?.position ?: 0L)
                                        },
                                    )
                                }
                            } else {
                                context.startActivity(
                                    Intent(context, TingshuPlayerActivity::class.java)
                                        .putExtra("book", saved.book.key).putExtra("index", index)
                                        .putExtra("position", progress?.position ?: 0L),
                                )
                            }
                        }
                    }
                }
                NowPlayingBanner(
                    title = title,
                    episodeTitle = episodeTitle,
                    playing = !isResume && (if (showLocal) localPlayback.playing else playback.playing),
                    playWhenReady = !isResume && (if (showLocal) localPlayback.playWhenReady else playback.playWhenReady),
                    loading = backgroundPlayer.starting || (!isResume && (if (showLocal) localPlayback.loading else playback.loading)),
                    position = position,
                    duration = duration,
                    coverModel = coverModel,
                    controlsEnabled = !backgroundPlayer.starting && (if (isResume) entry != null else controller != null),
                    onTogglePlayback = {
                        val connected = controller
                        val action = listeningResumeAction(
                            hasSession = !isResume && connected != null,
                            hasMedia = connected?.mediaItemCount?.let { it > 0 } == true,
                            ended = connected?.playbackState == Player.STATE_ENDED,
                            idle = connected?.playbackState == Player.STATE_IDLE,
                            playWhenReady = connected?.playWhenReady == true,
                        )
                        when (action) {
                            ListeningResumeAction.Restore -> {
                                val book = when (bannerMode) {
                                    HomeBannerMode.Local -> localEntries.firstOrNull { it.localBook?.folderPath == localPlayback.bookPath }
                                    HomeBannerMode.Source -> playback.book?.let { HomeRecentBook(it, progresses[it.key], repository.lastPlayedAt(it.key)) }
                                    HomeBannerMode.Resume -> entry
                                }
                                book?.let { resumeBook(it.localBook, it.book) }
                            }
                            ListeningResumeAction.Replay -> connected?.let {
                                it.seekToDefaultPosition()
                                it.play()
                            }
                            ListeningResumeAction.Pause -> connected?.pause()
                            ListeningResumeAction.PrepareAndPlay -> connected?.let {
                                it.prepare()
                                it.play()
                            }
                            ListeningResumeAction.Play -> connected?.play()
                        }
                    },
                    onClick = { openPlayer() },
                )
            }
        }
        item(key = "local", span = { GridItemSpan(maxLineSpan) }) {
            Surface(onClick = onLocalClick, shape = HomeShapeLarge, color = colors.primaryContainer) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("本地书库", style = FluxTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = colors.onPrimaryContainer)
                        Spacer(Modifier.height(8.dp))
                        Text(if (hasLocalPath) "$localBookCount 本藏书 · 随时开听" else "在听书配置中选择书库目录", style = FluxTheme.typography.bodySmall, color = colors.onPrimaryContainer.copy(alpha = 0.75f))
                        Spacer(Modifier.height(16.dp))
                        Text("打开书库  →", style = FluxTheme.typography.labelLarge, color = colors.onPrimaryContainer)
                    }
                    Box(Modifier.size(68.dp).background(colors.primary.copy(alpha = 0.12f), RoundedCornerShape(22.dp)), contentAlignment = Alignment.Center) {
                        Icon(NextIcons.Headset, null, tint = colors.onPrimaryContainer, modifier = Modifier.size(34.dp))
                    }
                }
            }
        }
        item(key = "source-heading", span = { GridItemSpan(maxLineSpan) }) {
            SectionTitle("在线书库") {
                Text("${sources.size} 个书源", style = FluxTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        }
        if (sources.isEmpty()) {
            item(key = "source-empty", span = { GridItemSpan(maxLineSpan) }) { EmptySourceEntryCard() }
        }
        items(sources, key = { "source-${it.id}" }) { source ->
            CloudLibraryCard(source, onClick = { onSourceClick(source) })
        }
        item(key = "recent-heading", span = { GridItemSpan(maxLineSpan) }) {
            SectionTitle("最近听过") {
                TextButton(onClick = { clearingHistory = true }, enabled = entries.isNotEmpty()) { Text("清空") }
                androidx.compose.material3.IconButton(onClick = {
                    grid = !grid
                    preferences.edit().putBoolean("grid", grid).apply()
                }) {
                    Icon(if (layoutIsGrid) NextIcons.ViewAgenda else NextIcons.DashBoard, if (layoutIsGrid) "切换为列表" else "切换为网格", tint = colors.onSurfaceVariant)
                }
            }
        }
        if (entries.isEmpty()) {
            item(key = "recent-empty", span = { GridItemSpan(maxLineSpan) }) {
                Text("从书库挑一本开始，收听记录会留在这里。", style = FluxTheme.typography.bodyMedium, color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
            }
        }
        items(entries, key = { "recent-${it.book.key}" }, contentType = { if (layoutIsGrid) "recent-grid" else "recent-row" }, span = { GridItemSpan(if (layoutIsGrid) 1 else maxLineSpan) }) { entry ->
            val book = entry.book
            val cover = if (entry.localBook != null) entry.localBook.coverUri else rememberSourceCover(book.sourceId, book.coverUrl, repository)
            // 最近收听记录已有完整书籍数据，点击直接从保存进度续播。
            val open = {
                resumeBook(entry.localBook, book)
            }
            if (layoutIsGrid) {
                RecentBookGridCard(book, entry.progress, cover, open, onLongClick = { removingBook = book })
            } else {
                RecentBookRow(book, entry.progress, cover, open, onLongClick = { removingBook = book })
            }
        }
    }
}

@Composable
private fun CloudLibraryCard(source: ListeningSource, onClick: () -> Unit) {
    val colors = FluxTheme.colorScheme
    Surface(onClick = onClick, shape = HomeShapeMedium, color = colors.surfaceContainerLow, border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.35f))) {
        Column(Modifier.fillMaxWidth().padding(20.dp).heightIn(min = 64.dp), verticalArrangement = Arrangement.Center) {
            Text(source.name, style = FluxTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(if (source.isCloudLibrary) "云端藏书" else "在线有声书", style = FluxTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

// region ── 区块标题 ──

@Composable
private fun SectionTitle(text: String, trailing: (@Composable () -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            style = FluxTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = FluxTheme.colorScheme.onSurface,
        )
        trailing?.invoke()
    }
}

// endregion

// region ── 正在播放横幅 ──

@Composable
private fun NowPlayingBanner(
    title: String,
    episodeTitle: String,
    playing: Boolean,
    playWhenReady: Boolean,
    loading: Boolean,
    position: Long,
    duration: Long,
    coverModel: Any?,
    controlsEnabled: Boolean,
    onTogglePlayback: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = FluxTheme.colorScheme
    val progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Surface(
        onClick = onClick,
        shape = HomeShapeLarge,
        color = colors.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.background(Brush.horizontalGradient(listOf(colors.primaryContainer, colors.primary.copy(alpha = 0.12f))))) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverBox(
                    coverModel = coverModel,
                    fallbackIcon = NextIcons.Podcast,
                    size = 64.dp,
                    shape = HomeShapeMedium,
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (playing) colors.primary else colors.onPrimaryContainer.copy(alpha = 0.45f),
                                ),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (loading) "缓冲中" else if (playing) "正在播放" else "继续收听",
                            style = FluxTheme.typography.labelMedium,
                            color = colors.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = title,
                        style = FluxTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onPrimaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (episodeTitle.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = episodeTitle,
                            style = FluxTheme.typography.bodySmall,
                            color = colors.onPrimaryContainer.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (duration > 0) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FluxLinearProgressIndicator(
                                progress = progress,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(3.dp)
                                    .clip(CircleShape),
                            )
                            Text(
                                text = "${formatHomeTime(position)} / ${formatHomeTime(duration)}",
                                style = FluxTheme.typography.labelSmall,
                                color = colors.onPrimaryContainer.copy(alpha = 0.75f),
                                maxLines = 1,
                            )
                        }
                    } else if (position > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "已听到 ${formatHomeTime(position)}",
                            style = FluxTheme.typography.labelSmall,
                            color = colors.onPrimaryContainer.copy(alpha = 0.7f),
                            maxLines = 1,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                androidx.compose.material3.IconButton(
                    onClick = onTogglePlayback,
                    enabled = controlsEnabled,
                    modifier = Modifier.size(48.dp).background(colors.primary, CircleShape),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (playWhenReady && (playing || loading)) NextIcons.Pause else NextIcons.Play,
                            contentDescription = if (playWhenReady && (playing || loading)) "暂停播放" else "继续播放",
                            tint = colors.onPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

// endregion

// region ── 书库 / 书源入口卡 ──

@Composable
private fun EmptySourceEntryCard() {
    val colors = FluxTheme.colorScheme
    Surface(
        shape = HomeShapeMedium,
        color = colors.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(HomeShapeSmall)
                    .background(colors.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = NextIcons.Link,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(21.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "暂无书源",
                    style = FluxTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "到「设置 → 听书配置」导入 JAR 书源包",
                    style = FluxTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// endregion

// region ── 最近听过：网格卡 ──

@Composable
private fun RecentBookGridCard(
    book: ListeningBook,
    progress: ListeningProgress?,
    coverModel: Any?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FluxTheme.colorScheme
    val percent = progressPercent(book, progress)

    Column(
        modifier = modifier
            .clip(HomeShapeMedium)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "删除收听记录"),
    ) {
        Box {
            CoverBox(
                coverModel = coverModel,
                fallbackIcon = NextIcons.Headset,
                size = 104.dp,
                shape = HomeShapeMedium,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            )
            if (percent > 0f) {
                Text(
                    text = "${(percent * 100).toInt()}%",
                    style = FluxTheme.typography.labelSmall,
                    color = colors.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.primary.copy(alpha = 0.88f))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = book.title,
            style = FluxTheme.typography.titleSmall,
            color = colors.onSurface,
            modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
            maxLines = 1,
            overflow = TextOverflow.Clip,
            lineHeight = 17.sp,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = progressSubtitle(book, progress),
            style = FluxTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
        if (percent > 0f) {
            Spacer(Modifier.height(6.dp))
            FluxLinearProgressIndicator(
                progress = percent,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(CircleShape),
            )
        }
    }
}

// endregion

// region ── 最近听过：列表行 ──

@Composable
private fun RecentBookRow(
    book: ListeningBook,
    progress: ListeningProgress?,
    coverModel: Any?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = FluxTheme.colorScheme
    val percent = progressPercent(book, progress)

    Surface(
        shape = HomeShapeMedium,
        color = colors.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(HomeShapeMedium).combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "删除收听记录"),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverBox(
                coverModel = coverModel,
                fallbackIcon = NextIcons.Headset,
                size = 80.dp,
                shape = HomeShapeSmall,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = FluxTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.onSurface,
                    modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = progressSubtitle(book, progress),
                    style = FluxTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE),
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                if (percent > 0f) {
                    Spacer(Modifier.height(7.dp))
                    FluxLinearProgressIndicator(
                        progress = percent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(CircleShape),
                    )
                }
            }
        }
    }
}

// endregion

// region ── 空状态 ──

@Composable
private fun CoverBox(
    coverModel: Any?,
    fallbackIcon: ImageVector,
    size: Dp,
    shape: Shape,
    modifier: Modifier = Modifier.size(size),
) {
    val colors = FluxTheme.colorScheme
    Box(
        modifier = modifier
            .clip(shape)
            .background(colors.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (coverModel != null) {
            AsyncImage(
                imageLoader = rememberBookCoverImageLoader(),
                model = coverModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = fallbackIcon,
                contentDescription = null,
                tint = colors.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(size * 0.42f),
            )
        }
    }
}

/** 整本书的收听百分比：已播章节占比 + 当前章节内部进度。 */
private fun progressPercent(book: ListeningBook, progress: ListeningProgress?): Float {
    if (progress == null || book.episodes.isEmpty()) return 0f
    val currentIndex = book.episodes.indexOfFirst { it.url == progress.episodeUrl }
    if (currentIndex < 0) return 0f
    val inner = if (progress.duration > 0) {
        (progress.position.toFloat() / progress.duration).coerceIn(0f, 1f)
    } else {
        0f
    }
    return ((currentIndex + inner) / book.episodes.size).coerceIn(0f, 1f)
}

private fun progressSubtitle(book: ListeningBook, progress: ListeningProgress?): String {
    val count = book.episodes.size
    if (progress == null) return "$count 集"
    val index = book.episodes.indexOfFirst { it.url == progress.episodeUrl }
    return if (index >= 0) "第 ${index + 1}/$count 集" else "$count 集"
}

// endregion

private data class HomeRecentBook(
    val book: ListeningBook,
    val progress: ListeningProgress?,
    val playedAt: Long,
    val localBook: AudioBook? = null,
)

/** 横幅数据来源：本地书库直播 / 书源直播 / 冷启动恢复。 */
private enum class HomeBannerMode { Local, Source, Resume }

/** 恢复态：按持久化的 episodeUrl 反查章节名，匹配不上时回落到第 N 集提示。 */
private fun resolveEpisodeTitle(entry: HomeRecentBook): String {
    val progress = entry.progress ?: return ""
    val episodes = entry.book.episodes
    val index = episodes.indexOfFirst { it.url == progress.episodeUrl }
    return when {
        index >= 0 -> episodes[index].title
        episodes.isNotEmpty() -> "第 ${progress.position / 1000}s 处"
        else -> ""
    }
}

/** 恢复态封面：本地书直接用书籍封面 Uri，书源走带鉴权头的图片请求。 */
@Composable
private fun resumeCover(entry: HomeRecentBook, repository: TingshuRepository): Any? =
    entry.localBook?.coverUri
        ?: entry.book.coverUrl.takeIf { it.isNotBlank() }
            ?.let { rememberSourceCover(entry.book.sourceId, it, repository) }

/** 横幅时间标签：mm:ss，超过一小时用 h:mm:ss。 */
private fun formatHomeTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** 只连接当前展示的会话，离开首页时释放控制器。 */
@Composable
private fun rememberHomeController(component: ComponentName?): MediaController? {
    val context = LocalContext.current
    var controller by remember(component) { mutableStateOf<MediaController?>(null) }
    DisposableEffect(component) {
        var disposed = false
        val future = component?.let { MediaController.Builder(context, SessionToken(context, it)).buildAsync() }
        future?.addListener({
            if (!disposed) controller = runCatching { future.get() }.getOrNull()
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            future?.let(MediaController::releaseFuture)
        }
    }
    return controller
}
