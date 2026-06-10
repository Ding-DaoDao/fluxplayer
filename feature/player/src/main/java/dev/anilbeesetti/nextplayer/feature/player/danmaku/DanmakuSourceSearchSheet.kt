package dev.anilbeesetti.nextplayer.feature.player.danmaku

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.anilbeesetti.nextplayer.core.model.AnimeMatch
import dev.anilbeesetti.nextplayer.core.model.DanmakuDownloadState
import dev.anilbeesetti.nextplayer.core.model.DanmakuSource
import dev.anilbeesetti.nextplayer.core.model.EpisodeInfo

/**
 * 弹幕源搜索底部弹出层。
 *
 * 多步骤流程：
 * 1. 源选择 → 2. 输入关键词 → 3. 搜索结果列表 → 4. 剧集选择 → 5. 下载
 */
@Composable
fun DanmakuSourceSearchSheet(
    show: Boolean,
    sources: List<DanmakuSource>,
    downloadState: DanmakuDownloadState,
    onSearch: (DanmakuSource, String) -> Unit,
    onSelectAnime: (AnimeMatch) -> Unit,
    onSelectEpisode: (EpisodeInfo) -> Unit,
    onDismiss: () -> Unit,
    onResetSearch: () -> Unit = onDismiss,
    modifier: Modifier = Modifier,
) {
    if (!show) return

    // 搜索关键词 — 提到外层，切换状态时保持
    var keyword by remember { mutableStateOf("") }

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        // 半透明背景 — 点击外部关闭
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
                .clickable(onClick = onDismiss),
        )

        // 右上角紧凑浮窗
        androidx.compose.material3.Card(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 98.dp, end = 16.dp)
                .widthIn(max = 300.dp)
                .heightIn(max = 400.dp),
            shape = RoundedCornerShape(14.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = Color(0xFF1E1E2E),
            ),
            elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
            ) {
            when (val state = downloadState) {
                is DanmakuDownloadState.Idle -> {
                    SearchInputStep(
                        sources = sources,
                        keyword = keyword,
                        onKeywordChange = { keyword = it },
                        onSearch = onSearch,
                        onDismiss = onDismiss,
                    )
                }
                is DanmakuDownloadState.Searching -> {
                    SearchingStep(keyword = keyword)
                }
                is DanmakuDownloadState.SearchResult -> {
                    SearchResultStep(
                        animeList = state.animeList,
                        onSelectAnime = onSelectAnime,
                        onBack = onDismiss,
                    )
                }
                is DanmakuDownloadState.AnimeSelected -> {
                    EpisodeSelectionStep(
                        episodes = state.episodes,
                        onSelectEpisode = onSelectEpisode,
                        anime = state.anime,
                    )
                }
                is DanmakuDownloadState.Downloading -> {
                    DownloadingStep()
                }
                is DanmakuDownloadState.Ready -> {
                    // Ready 不显示UI — 由上层 LaunchedEffect 关闭 Sheet + 显示 Toast
                }
                is DanmakuDownloadState.Error -> {
                    ErrorStep(
                        message = state.message,
                        onRetry = onResetSearch,
                    )
                }
            }
        }
    }
    }
}

@Composable
private fun SearchInputStep(
    sources: List<DanmakuSource>,
    keyword: String = "",
    onKeywordChange: (String) -> Unit = {},
    onSearch: (DanmakuSource, String) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.width(240.dp)) {
        // 关键词输入（紧凑）
        OutlinedTextField(
            value = keyword,
            onValueChange = onKeywordChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索动漫", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp) },
            textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF00A0FF),
                unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                cursorColor = Color(0xFF00A0FF),
                focusedContainerColor = Color(0xFF2A2A3E),
                unfocusedContainerColor = Color(0xFF2A2A3E),
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(
                onSearch = {
                    if (keyword.isNotBlank() && sources.isNotEmpty()) {
                        onSearch(sources.first(), keyword)
                    }
                },
            ),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 源按钮行 — 点击即搜索
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            sources.forEach { source ->
                Button(
                    onClick = {
                        if (keyword.isNotBlank()) {
                            onSearch(source, keyword)
                        }
                    },
                    enabled = keyword.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00A0FF),
                        disabledContainerColor = Color.White.copy(alpha = 0.12f),
                    ),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) {
                    Text(source.name, color = Color.White, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // 搜索按钮（紧凑）
        Button(
            onClick = {
                if (keyword.isNotBlank() && sources.isNotEmpty()) {
                    onSearch(sources.first(), keyword)
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = keyword.isNotBlank() && sources.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A0FF)),
            shape = RoundedCornerShape(6.dp),
            contentPadding = ButtonDefaults.TextButtonContentPadding,
        ) {
            Text("搜索", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SearchingStep(keyword: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Color(0xFF00A0FF))
        Spacer(modifier = Modifier.height(16.dp))
        Text("正在搜索…", color = Color.White)
    }
}

@Composable
private fun SearchResultStep(
    animeList: List<AnimeMatch>,
    onSelectAnime: (AnimeMatch) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "搜索结果",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "返回",
                color = Color(0xFF00A0FF),
                modifier = Modifier.clickable(onClick = onBack),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        if (animeList.isEmpty()) {
            Text("未找到匹配结果", color = Color.White.copy(alpha = 0.5f))
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                items(animeList) { anime ->
                    AnimeItem(anime = anime, onClick = { onSelectAnime(anime) })
                }
            }
        }
    }
}

@Composable
private fun AnimeItem(anime: AnimeMatch, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = anime.title,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (anime.type.isNotBlank()) {
                    Text(
                        text = anime.type,
                        color = Color(0xFF00A0FF),
                        fontSize = 12.sp,
                    )
                }
                if (anime.episodeCount > 0) {
                    Text(
                        text = "共${anime.episodeCount}集",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeSelectionStep(
    episodes: List<EpisodeInfo>,
    onSelectEpisode: (EpisodeInfo) -> Unit,
    anime: AnimeMatch,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = anime.title,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        )
        Text(
            text = "选择剧集",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (episodes.isEmpty()) {
            Text("暂无剧集信息", color = Color.White.copy(alpha = 0.5f))
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                items(episodes) { episode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectEpisode(episode) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = episode.title.ifBlank { "第${episode.episodeNumber}集" },
                            color = Color.White,
                            fontSize = 14.sp,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "下载",
                            color = Color(0xFF00A0FF),
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadingStep() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth(0.7f),
            color = Color(0xFF00A0FF),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text("正在下载弹幕…", color = Color.White)
    }
}

@Composable
private fun ErrorStep(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("加载失败", color = Color(0xFFFF4444), fontSize = 16.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(message, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00A0FF)),
        ) {
            Text("重试", color = Color.White)
        }
    }
}
