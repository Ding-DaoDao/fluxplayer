package com.fluxplayer.app.feature.videopicker.screens.audiobook

import android.app.Activity
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
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
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.toBitmap
import com.fluxplayer.app.core.ui.designsystem.NextIcons
import com.fluxplayer.app.core.ui.R as coreUiR
import com.fluxplayer.app.feature.videopicker.model.AudioBook
import com.fluxplayer.app.feature.videopicker.model.AudioChapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val BgTop = Color(0xFFF0F4F8)
private val BgMid = Color(0xFFE8EDF3)
private val BgBottom = Color(0xFFDDE4EC)
private val GoldBorder = Color(0xFF3B82F6)
private val ActiveChapter = Color(0xFF2563EB)

// ── 动画常量 ──
/** 封面初始尺寸 */
private val CoverInitW = 112.dp
private val CoverInitH = 144.dp
/** 封面收起后尺寸 */
private val CoverCollapsedW = 48.dp
private val CoverCollapsedH = 62.dp
/** 信息区内边距（初始 → 收起） */
private val InfoPaddingTop = 0.dp
private val InfoPaddingCollapsed = 0.dp
/** 收起动画作用的滚动距离（越大越平滑） */
private val CollapseRangeDp = 260.dp

@Composable
fun AudiobookDetailContent(
    book: AudioBook,
    onBackClick: () -> Unit,
    onChapterClick: (AudioChapter, Long) -> Unit,
    resumeChapterIndex: Int? = null,
    resumePositionMs: Long = 0L,
    chapterProgress: Map<Int, Pair<Long, Long>> = emptyMap(),
    modifier: Modifier = Modifier,
) {
    var reversed by remember { mutableStateOf(false) }
    val chapters = if (reversed) book.chapters.reversed() else book.chapters

    // ── 从封面提取主色调 ──
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    var paletteColor by remember { mutableStateOf(Color(0xFF1C1C1E)) }
    LaunchedEffect(book.coverUri) {
        val coverUri = book.coverUri ?: return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            try {
                val result = imageLoader.execute(
                    ImageRequest.Builder(context).data(coverUri).size(128, 128).build(),
                )
                result.image?.toBitmap()
            } catch (_: Exception) { null }
        }
        bitmap?.let { bmp ->
            // Coil 可能返回硬件加速位图 (Config#HARDWARE)，Palette 无法读取像素，需转为 ARGB_8888
            val safeBmp = if (bmp.config == Bitmap.Config.HARDWARE) {
                bmp.copy(Bitmap.Config.ARGB_8888, false)
            } else bmp
            val palette = Palette.from(safeBmp).generate()
            val dominant = palette.getDominantColor(paletteColor.toArgb())
            paletteColor = Color(dominant)
        }
    }

    // 拦截系统返回键，统一走 onBackClick 正确清掉状态
    BackHandler(onBack = onBackClick)

    // 强制深色状态栏（白色图标）
    val view = LocalView.current
    SideEffect {
        if (!view.isInEditMode) {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
        }
    }

    val density = LocalDensity.current
    val statusBarTop = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
    val topBarHeight = 48.dp

    // ── 滚动状态 ──
    val listState = rememberLazyListState()
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

    // ── 目录卡片可见比例（0=不可见, 1=完全进入白底区域） ──
    val catalogVisibleFraction by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex >= 1) {
                1f
            } else {
                val itemHeight = with(density) { 120.dp.toPx() }
                val offset = listState.firstVisibleItemScrollOffset
                (offset / itemHeight).coerceIn(0f, 1f)
            }
        }
    }

    // ── 封面动画：用 spring 保留轻微物理感 ──
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

    // ── 其他动画：用 tween 保持克制线性 ──
    val infoAlpha by animateFloatAsState(
        targetValue = 1f - collapseFraction,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "infoAlpha",
    )
    val darkBgAlpha by animateFloatAsState(
        targetValue = collapseFraction * 0.6f + 0.05f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "barBg",
    )
    val topBarTitleAlpha by animateFloatAsState(
        targetValue = collapseFraction,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "barTitle",
    )
    val borderAlpha by animateFloatAsState(
        targetValue = 1f - collapseFraction,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "borderAlpha",
    )

    // ── 目录卡片颜色：从封面主色提取，混合暗色底色 ──
    val catalogBg = paletteColor.copy(alpha = 0.15f).compositeOver(BgMid)
    // ── 顶栏颜色：始终白色半透明 ──
    val topBarBgColor by animateColorAsState(
        targetValue = if (catalogVisibleFraction > 0.5f) {
            Color.White.copy(alpha = 0.85f)
        } else {
            Color.White.copy(alpha = darkBgAlpha)
        },
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "topBarBg",
    )
    val topBarIconTint by animateColorAsState(
        targetValue = Color(0xFF2563EB),
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "topBarIcon",
    )
    val topBarTitleColor by animateColorAsState(
        targetValue = Color(0xFF1E293B),
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "topBarTitleColor",
    )

    // ── 收藏按钮点击微交互 ──
    val favInteractionSource = remember { MutableInteractionSource() }
    val isFavPressed by favInteractionSource.collectIsPressedAsState()
    val favScale by animateFloatAsState(
        targetValue = if (isFavPressed) 0.75f else 1f,
        animationSpec = spring(stiffness = 400f, dampingRatio = 0.5f),
        label = "favScale",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(BgTop, BgMid, BgBottom),
                ),
            ),
    ) {
        // ── 模糊封面背景层（蓝调滤镜） ──
        if (book.coverUri != null) {
            AsyncImage(
                model = book.coverUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(40.dp)
                    .graphicsLayer { alpha = 0.25f },
            )
        }
        // ── 渐变遮罩层（蓝白） ──
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFE8EDF3).copy(alpha = 0.1f),
                            Color.Transparent,
                            Color(0xFFDDE4EC).copy(alpha = 0.3f),
                        ),
                        startY = 0f,
                        endY = Float.POSITIVE_INFINITY,
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
                        .padding(top = statusBarTop + topBarHeight + 12.dp),
                ) {
                    // 封面 + 文字信息
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 封面（金色边框 + 缩放动画）
                        Box(
                            modifier = Modifier
                                .size(width = animatedCoverW, height = animatedCoverH)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    width = if (collapseFraction < 0.5f) 2.dp else 0.dp,
                                    color = GoldBorder.copy(alpha = borderAlpha),
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .background(Color(0xFFE2E8F0)),
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
                                    modifier = Modifier.size(28.dp),
                                    tint = Color(0xFF94A3B8),
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        // 文字信息（随滚动淡出）
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .alpha(infoAlpha),
                            verticalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = book.title,
                                color = Color(0xFF1E293B),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "共 ${book.chapterCount} 集",
                                color = Color(0xFF64748B),
                                fontSize = 14.sp,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // ── 操作区 ──
                    val hasResume = resumeChapterIndex != null && resumeChapterIndex < chapters.size
                    val resumeChapter = if (hasResume) book.chapters.getOrNull(resumeChapterIndex!!) else null
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (hasResume) "继续播放 第${resumeChapterIndex!! + 1}集" else "还未有播放记录",
                            color = Color(0xFF64748B),
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (chapters.isNotEmpty()) {
                            Button(
                                onClick = {
                                    if (hasResume && resumeChapter != null) {
                                        onChapterClick(resumeChapter, resumePositionMs)
                                    } else {
                                        onChapterClick(chapters.first(), 0L)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ActiveChapter,
                                ),
                                shape = RoundedCornerShape(50),
                            ) {
                                Icon(
                                    painter = painterResource(coreUiR.drawable.ic_play),
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (hasResume && resumeChapter != null) resumeChapter.title else chapters.first().title,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 160.dp),
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
                    // 目录切换栏（轻量横条）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "目录 · ${book.chapterCount}章",
                            color = Color(0xFF475569),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = if (reversed) "倒序" else "正序",
                            color = Color(0xFF64748B),
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = ripple(),
                                    onClick = { reversed = !reversed },
                                )
                                .padding(4.dp),
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // ── Items 2+: 章节列表（卡片式） ──
            val cardBg = Color(0xFFF5F7FA)
            val progressBrush = Brush.horizontalGradient(
                colors = listOf(Color(0xFF2563EB), Color(0xFF60A5FA)),
            )
            itemsIndexed(
                items = chapters,
                key = { _, ch -> ch.uri.toString() },
            ) { _, chapter ->
                val realIndex = book.chapters.indexOf(chapter) + 1
                val chapIdx = realIndex - 1
                val savedProgress = chapterProgress[chapIdx]
                val savedPosMs = savedProgress?.first?.coerceAtLeast(0L) ?: 0L
                val savedDurMs = savedProgress?.second?.coerceAtLeast(0L) ?: 0L
                val hasProgress = savedDurMs > 0 && savedPosMs > 0
                val isPlayed = hasProgress
                val rawProgress = if (hasProgress) (savedPosMs.toFloat() / savedDurMs).coerceIn(0f, 1f) else 0f
                val animatedProgress by animateFloatAsState(
                    targetValue = rawProgress,
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = 80f),
                    label = "progress",
                )

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = cardBg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 3.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(),
                            onClick = { onChapterClick(chapter, savedPosMs) },
                        ),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 序号
                        Text(
                            text = realIndex.toString().padStart(3, '0'),
                            color = if (isPlayed) ActiveChapter else Color(0xFF94A3B8),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(40.dp),
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            // 标题 + 播放按钮
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = chapter.title,
                                    color = if (isPlayed) ActiveChapter else Color(0xFF334155),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Icon(
                                    painter = painterResource(coreUiR.drawable.ic_play),
                                    contentDescription = "播放",
                                    tint = if (isPlayed) ActiveChapter else Color(0xFF94A3B8),
                                    modifier = Modifier.size(22.dp),
                                )
                            }

                            Spacer(modifier = Modifier.height(if (hasProgress) 8.dp else 0.dp))

                            // 进度条（仅已播放）
                            if (hasProgress) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(Color(0xFFE2E8F0)),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .fillMaxWidth(animatedProgress)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(progressBrush),
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "已听 ${formatChapterProgress(savedPosMs, savedDurMs)}",
                                        color = ActiveChapter.copy(alpha = 0.8f),
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── 悬浮顶栏：白色毛玻璃效果 ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(topBarHeight + statusBarTop)
                .background(topBarBgColor),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = statusBarTop, start = 12.dp, end = 12.dp),
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

                // 中间书名（滚动后渐显）
                Text(
                    text = book.title,
                    color = topBarTitleColor.copy(alpha = topBarTitleAlpha),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .padding(horizontal = 8.dp),
                )

                // 右侧收藏（带点击微交互）
                IconButton(
                    onClick = { /* 收藏 - 开发中 */ },
                    modifier = Modifier.graphicsLayer {
                        scaleX = favScale
                        scaleY = favScale
                    },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FavoriteBorder,
                        contentDescription = "收藏",
                        tint = topBarIconTint,
                    )
                }
            }
        }
    }
}

// ── 工具：Dp 线性插值 ──
private fun lerp(start: Dp, stop: Dp, fraction: Float): Dp {
    return start + (stop - start) * fraction.coerceIn(0f, 1f)
}

// ── 工具：章节进度格式化 ──
private fun formatChapterProgress(posMs: Long, durMs: Long): String {
    if (durMs <= 0) return "0%"
    val pct = (posMs.toFloat() / durMs * 100).toInt().coerceIn(0, 100)
    return "$pct%"
}
