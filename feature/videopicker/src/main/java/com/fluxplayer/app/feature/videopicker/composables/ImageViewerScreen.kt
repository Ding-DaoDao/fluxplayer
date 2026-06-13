package com.fluxplayer.app.feature.videopicker.composables

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxplayer.app.core.model.WebDavResource
import kotlinx.coroutines.launch

/**
 * 全屏图片查看器
 *
 * @param images       当前目录所有图片资源
 * @param initialIndex 打开时显示的图片索引（相对于 images 列表）
 * @param imageResolver 异步获取原图 URL + auth headers，返回 null 表示失败
 * @param onClose      关闭查看器回调
 */
@Composable
fun ImageViewerScreen(
    images: List<WebDavResource>,
    initialIndex: Int,
    imageResolver: suspend (WebDavResource) -> Pair<String, Map<String, String>>?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty()) return

    BackHandler(onBack = onClose)

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, images.lastIndex),
    ) { images.size }

    // URL 缓存：path → (url, headers)
    val urlCache = remember { mutableStateMapOf<String, Pair<String, Map<String, String>>>() }
    // 加载状态：path → loading
    val loadingSet = remember { mutableStateMapOf<String, Boolean>() }
    // 错误状态：path → true
    val errorSet = remember { mutableStateMapOf<String, Boolean>() }

    val scope = rememberCoroutineScope()

    // 加载当前页 URL
    LaunchedEffect(pagerState.currentPage) {
        val item = images.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
        if (urlCache.containsKey(item.path) || loadingSet[item.path] == true) return@LaunchedEffect
        loadingSet[item.path] = true
        errorSet.remove(item.path)
        val result = imageResolver(item)
        loadingSet.remove(item.path)
        if (result != null) {
            urlCache[item.path] = result
        } else {
            errorSet[item.path] = true
        }
    }

    // 预加载相邻页
    LaunchedEffect(pagerState.currentPage) {
        listOf(-1, 1).forEach { offset ->
            val adjIndex = pagerState.currentPage + offset
            val adj = images.getOrNull(adjIndex) ?: return@forEach
            if (urlCache.containsKey(adj.path) || loadingSet[adj.path] == true) return@forEach
            scope.launch {
                loadingSet[adj.path] = true
                val result = imageResolver(adj)
                loadingSet.remove(adj.path)
                if (result != null) {
                    urlCache[adj.path] = result
                }
            }
        }
    }

    // 顶部栏显隐
    var showTopBar by remember { mutableStateOf(true) }
    val currentItem = images.getOrNull(pagerState.currentPage)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { showTopBar = !showTopBar },
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { images[it].path },
        ) { page ->
            val item = images[page]
            val cached = urlCache[item.path]
            val isLoading = loadingSet[item.path] == true
            val isError = errorSet[item.path] == true

            when {
                cached != null -> {
                    ZoomableImage(
                        imageUrl = cached.first,
                        headers = cached.second,
                        onScaleChanged = { /* HorizontalPager userScrollEnabled 由手势冲突自行处理 */ },
                    )
                }
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }
                isError -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "图片加载失败",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.size(12.dp))
                        androidx.compose.material3.TextButton(
                            onClick = {
                                errorSet.remove(item.path)
                                scope.launch {
                                    loadingSet[item.path] = true
                                    val result = imageResolver(item)
                                    loadingSet.remove(item.path)
                                    if (result != null) urlCache[item.path] = result
                                    else errorSet[item.path] = true
                                }
                            },
                        ) {
                            Text("重试", color = Color.White)
                        }
                    }
                }
            }
        }

        // 顶部浮层
        AnimatedVisibility(
            visible = showTopBar,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "关闭",
                        tint = Color.White,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentItem?.name ?: "",
                        color = Color.White,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sizeText = currentItem?.let { item ->
                        if (item.size > 0) formatFileSize(item.size) else null
                    }
                    if (sizeText != null) {
                        Text(
                            text = "${pagerState.currentPage + 1} / ${images.size}    $sizeText",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                        )
                    } else {
                        Text(
                            text = "${pagerState.currentPage + 1} / ${images.size}",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}
