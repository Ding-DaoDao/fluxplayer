package com.fluxplayer.app.feature.player

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.palette.graphics.Palette
import com.fluxplayer.app.core.ui.cache.rememberBookCoverImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.toBitmap
import com.fluxplayer.app.core.ui.R as coreUiR
import com.fluxplayer.app.core.ui.components.ChapterDragScrollbar
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.theme.FluxTheme
import com.fluxplayer.app.feature.player.model.AudioBook
import com.fluxplayer.app.feature.player.model.AudioChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// 配色方案通过 FluxTheme.colorScheme 接入，自动适配 MD3 / MIUI X 两套引擎

// ── 动画常量 ──
/** 封面初始尺寸 */
private val CoverInitW = 120.dp
private val CoverInitH = 160.dp

/** 封面收起后尺寸 */
private val CoverCollapsedW = 48.dp
private val CoverCollapsedH = 64.dp

/** 收起动画作用的滚动距离 */
private val CollapseRangeDp = 240.dp

@Composable
fun AudiobookDetailContent(
    book: AudioBook,
    onBackClick: () -> Unit,
    onChapterClick: (AudioChapter, Long) -> Unit,
    resumeChapterIndex: Int? = null,
    resumePositionMs: Long = 0L,
    chapterProgress: Map<Int, Pair<Long, Long>> = emptyMap(),
    coverModel: Any? = book.coverUri,
    intro: String = "",
    modifier: Modifier = Modifier,
) {
    var reversed by remember { mutableStateOf(false) }
    val chapters = if (reversed) book.chapters.reversed() else book.chapters

    // 当前主题颜色（从 CompositionLocal 读取，自动适配 MD3 / MIUI X）
    val fluxColors = FluxTheme.colorScheme

    // ── 从封面提取主色调（作为背景渐变的基准色） ──
    val context = LocalContext.current
    val imageLoader = rememberBookCoverImageLoader()
    var paletteColor by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(coverModel) {
        val coverUri = coverModel ?: return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            try {
                val result = imageLoader.execute(
                    if (coverUri is ImageRequest) coverUri else ImageRequest.Builder(context).data(coverUri).size(128, 128).build(),
                )
                result.image?.toBitmap()
            } catch (_: Exception) {
                null
            }
        }
        bitmap?.let { bmp ->
            val safeBmp = if (bmp.config == Bitmap.Config.HARDWARE) {
                bmp.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bmp
            }
            val palette = Palette.from(safeBmp ?: return@let).generate()
            val dominant = palette.getDominantColor(0)
            if (dominant != 0) {
                paletteColor = Color(dominant)
            }
        }
    }

    // 拦截系统返回键，统一走 onBackClick 正确清掉状态
    BackHandler(onBack = onBackClick)

    val density = LocalDensity.current
    val statusBarTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val topBarHeight = 48.dp

    // ── 滚动状态 ──
    val listState = rememberLazyListState()
    // 已播放章节（显示顺序索引），用于滚动条轨道打点
    val playedChapterIndexes = remember(book, chapters, chapterProgress) {
        chapters.mapIndexedNotNull { index, ch ->
            val progress = chapterProgress[book.chapters.indexOf(ch)]
            if (progress != null && progress.second > 0 && progress.first > 0) index else null
        }.toSet()
    }

    /** 滚动收起进度 0=完全展开 / 1=完全收起 */
    val collapseFraction by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex == 0) {
                val offset = listState.firstVisibleItemScrollOffset
                val rangePx = with(density) { CollapseRangeDp.toPx() }
                (offset / rangePx).coerceIn(0f, 1f)
            } else {
                1f
            }
        }
    }

    // 状态栏图标颜色：头部区域透明背景用白色图标；滚动到目录区后顶栏背景与页面一致（浅色），切深色图标
    val view = LocalView.current
    SideEffect {
        if (!view.isInEditMode) {
            val activity = generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>().firstOrNull() ?: return@SideEffect
            val window = activity.window
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = fluxColors.background.luminance() > 0.5f
        }
    }

    // ── 封面动画 ──
    val animatedCoverW by animateDpAsState(
        targetValue = lerp(CoverInitW, CoverCollapsedW, collapseFraction),
        animationSpec = spring(stiffness = 200f, dampingRatio = 0.85f),
        label = "coverW",
    )
    val animatedCoverH by animateDpAsState(
        targetValue = lerp(CoverInitH, CoverCollapsedH, collapseFraction),
        animationSpec = spring(stiffness = 200f, dampingRatio = 0.85f),
        label = "coverH",
    )

    // ── 其他动画 ──
    val infoAlpha by animateFloatAsState(
        targetValue = 1f - collapseFraction,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "infoAlpha",
    )
    val topBarTitleAlpha by animateFloatAsState(
        targetValue = collapseFraction,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "barTitle",
    )

    // 背景渐变使用的主色：优先使用封面提取色，否则使用主题 primary
    val gradientPrimary = paletteColor ?: fluxColors.primary

    // ── 顶栏元素颜色 ──
    // 顶栏纯透明悬浮（无背景色块/分隔线），内容自然从下方滚过；
    // 图标/书名：封面区域（透明背景）白色 → 目录区（浅色内容上）深色，与背景同步过渡
    val topBarIconTint by animateColorAsState(
        targetValue = fluxColors.onSurface,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "topBarIcon",
    )
    val topBarTitleColor by animateColorAsState(
        targetValue = fluxColors.onSurface,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "topBarTitleColor",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(fluxColors.background),
    ) {
        // ── 模糊封面背景层 ──
        if (coverModel != null) {
            AsyncImage(
                    imageLoader = rememberBookCoverImageLoader(),
                model = coverModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(60.dp)
                    .graphicsLayer { alpha = 0.12f },
            )
        }
        // ── 渐变遮罩层：从页面顶部到底部自然过渡 ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            gradientPrimary.copy(alpha = 0.18f),
                            gradientPrimary.copy(alpha = 0.06f),
                            Color.Transparent,
                            fluxColors.background,
                        ),
                        startY = 0f,
                        endY = 3000f,
                    ),
                ),
        )

        // ── 可滚动内容 ──
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars),
        ) {
            // ── Item 0: 头部区域（封面 + 信息 + 操作） ──
            item(key = "header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = statusBarTop + topBarHeight + 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 封面
                    Box(
                        modifier = Modifier
                            .size(width = animatedCoverW, height = animatedCoverH)
                            .clip(RoundedCornerShape(16.dp))
                            .background(fluxColors.surfaceVariant)
                            .graphicsLayer { shadowElevation = 16f },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (coverModel != null) {
                            AsyncImage(
                    imageLoader = rememberBookCoverImageLoader(),
                                model = coverModel,
                                contentDescription = book.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(
                                imageVector = NextIcons.Audio,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = fluxColors.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // 书名（随滚动淡出）
                    Text(
                        text = book.title,
                        color = fluxColors.onSurface,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .alpha(infoAlpha)
                            .padding(horizontal = 32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // 章节数
                    Text(
                        text = "共 ${book.chapterCount} 章节",
                        color = fluxColors.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.alpha(infoAlpha),
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // ── 操作区：继续播放按钮（显示续播集数） ──
                    val hasResume = resumeChapterIndex != null && resumeChapterIndex < chapters.size
                    val resumeChapter = if (hasResume) book.chapters.getOrNull(resumeChapterIndex!!) else null
                    val resumeChapterNum = if (hasResume) resumeChapterIndex!! + 1 else 1

                    // 主操作按钮
                    Card(
                        onClick = {
                            if (hasResume && resumeChapter != null) {
                                onChapterClick(resumeChapter, resumePositionMs)
                            } else if (chapters.isNotEmpty()) {
                                onChapterClick(chapters.first(), 0L)
                            }
                        },
                        shape = RoundedCornerShape(28.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = fluxColors.primary,
                        ),
                        elevation = CardDefaults.cardElevation(
                            defaultElevation = 4.dp,
                            pressedElevation = 8.dp,
                        ),
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(60.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(coreUiR.drawable.ic_play),
                                contentDescription = null,
                                tint = fluxColors.onPrimary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = if (hasResume && resumeChapter != null) "继续播放" else "开始播放",
                                    color = fluxColors.onPrimary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    lineHeight = 14.sp,
                                )
                                Text(
                                    text = if (hasResume && resumeChapter != null) {
                                        "第 $resumeChapterNum 集 · ${resumeChapter.title}"
                                    } else {
                                        "${book.chapterCount} 集 · 从头开始"
                                    },
                                    color = fluxColors.onPrimary.copy(alpha = 0.85f),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // ── Item 1: 目录区 ──
            item(key = "catalog") {
                Column {
                    // 目录切换栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "目录",
                            style = MaterialTheme.typography.titleMedium,
                            color = fluxColors.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "${book.chapterCount} 章节",
                            color = fluxColors.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (reversed) fluxColors.primary.copy(alpha = 0.1f) else fluxColors.surfaceVariant,
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(),
                                onClick = { reversed = !reversed },
                            ),
                        ) {
                            Text(
                                text = if (reversed) "倒序" else "正序",
                                color = if (reversed) fluxColors.primary else fluxColors.onSurfaceVariant,
                                fontSize = 13.sp,
                                fontWeight = if (reversed) FontWeight.Medium else FontWeight.Normal,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // ── Items 2+: 章节列表 ──
            if (intro.isNotBlank()) {
                item(key = "intro") {
                    Text(intro, color = fluxColors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp), maxLines = 6)
                }
            }
            itemsIndexed(
                items = chapters,
                key = { index, ch -> "$index:${ch.uri}" },
            ) { _, chapter ->
                val realIndex = book.chapters.indexOf(chapter) + 1
                val chapIdx = realIndex - 1
                val savedProgress = chapterProgress[chapIdx]
                val savedPosMs = savedProgress?.first?.coerceAtLeast(0L) ?: 0L
                val savedDurMs = savedProgress?.second?.coerceAtLeast(0L) ?: 0L
                val hasProgress = savedDurMs > 0 && savedPosMs > 0
                val isPlayed = hasProgress
                val progress = if (hasProgress) (savedPosMs.toFloat() / savedDurMs).coerceIn(0f, 1f) else 0f

                Card(
                    onClick = { onChapterClick(chapter, savedPosMs) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isPlayed) fluxColors.surfaceVariant else fluxColors.surfaceContainer,
                    ),
                    elevation = CardDefaults.cardElevation(
                        defaultElevation = 0.dp,
                        pressedElevation = 2.dp,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 序号圆圈
                        Surface(
                            shape = CircleShape,
                            color = if (isPlayed) fluxColors.primary.copy(alpha = 0.1f) else fluxColors.surfaceVariant,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Text(
                                    text = realIndex.toString(),
                                    color = if (isPlayed) fluxColors.primary else fluxColors.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        // 章节信息
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = chapter.title,
                                color = if (isPlayed) fluxColors.primary else fluxColors.onSurface,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )

                            // 章节时长（首次播放后从进度记录回填）
                            if (savedDurMs > 0) {
                                Text(
                                    text = formatChapterDuration(savedDurMs),
                                    color = fluxColors.onSurfaceVariant,
                                    fontSize = 12.sp,
                                )
                            }

                            // 进度条（已播放的章节显示）
                            if (hasProgress) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(fluxColors.primary.copy(alpha = 0.1f)),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(progress)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(
                                                    Brush.horizontalGradient(
                                                        colors = listOf(
                                                            fluxColors.primary.copy(alpha = 0.5f),
                                                            fluxColors.primary,
                                                        ),
                                                    ),
                                                ),
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "${(progress * 100).toInt()}%",
                                        color = fluxColors.primary.copy(alpha = 0.7f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // 播放图标
                        Surface(
                            shape = CircleShape,
                            color = if (isPlayed) fluxColors.primary.copy(alpha = 0.1f) else fluxColors.surfaceVariant,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                Icon(
                                    painter = painterResource(coreUiR.drawable.ic_play),
                                    contentDescription = "播放",
                                    tint = if (isPlayed) fluxColors.primary else fluxColors.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 右侧可拖拽滚动条：拖动 thumb 快速定位章节 ──
        // 简介占用独立条目，滚动条定位时需要计入偏移。
        val listItemOffset = if (intro.isBlank()) 2 else 3
        ChapterDragScrollbar(
            listState = listState,
            totalCount = chapters.size + listItemOffset,
            itemOffset = listItemOffset,
            playedChapters = playedChapterIndexes,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 6.dp),
        )

        // ── 悬浮顶栏 — 纯透明悬浮：无背景色块、无分隔线，内容自然从下方滚过 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(topBarHeight + statusBarTop)
                .padding(top = statusBarTop, start = 4.dp, end = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧返回
            IconButton(onClick = onBackClick) {
                Icon(
                    painter = painterResource(coreUiR.drawable.ic_arrow_left),
                    contentDescription = "返回",
                    tint = topBarIconTint,
                )
            }

            // 中间书名（滚动后渐显，悬浮胶囊衬底保证内容穿过时可读）
            Text(
                text = book.title,
                color = topBarTitleColor.copy(alpha = topBarTitleAlpha),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .padding(horizontal = 8.dp)
                    .background(
                        fluxColors.surfaceVariant.copy(alpha = topBarTitleAlpha * 0.5f),
                        RoundedCornerShape(16.dp),
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// ── 工具：Dp 线性插值 ──
private fun lerp(start: Dp, stop: Dp, fraction: Float): Dp {
    return start + (stop - start) * fraction.coerceIn(0f, 1f)
}

// ── 工具：章节时长格式化（mm:ss / h:mm:ss） ──
private fun formatChapterDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
