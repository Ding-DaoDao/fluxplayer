package com.fluxplayer.app.feature.player.danmaku

import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.fluxplayer.app.core.ui.components.FluxCircularProgressIndicator
import com.fluxplayer.app.core.ui.components.FluxIconButton
import com.fluxplayer.app.core.ui.components.FluxText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fluxplayer.app.core.model.AnimeMatch
import com.fluxplayer.app.core.model.DanmakuDownloadState
import com.fluxplayer.app.core.model.DanmakuSource
import com.fluxplayer.app.core.model.EpisodeInfo
import com.fluxplayer.app.core.ui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.regex.Pattern

// ── 深色主题色 ──
private val SurfaceColor = Color(0xFF1E1E2E)
private val SurfaceVariantColor = Color(0xFF2A2A3E)
private val TextPrimaryColor = Color(0xFFE0E0E0)
private val TextSecondaryColor = Color(0xFF9CA3AF)
private val AccentColor = Color(0xFF60A5FA)
private val AccentDimColor = Color(0xFF1E3A5F)
private val DividerColor = Color(0xFF374151)
private val ErrorColor = Color(0xFFF87171)

/** 弹幕搜索弹窗的视图模式（跨 show/hide 持久化） */
enum class DanmakuSearchViewMode { SEARCH, LOCAL_FILE }

/**
 * 弹幕搜索弹窗 —— 可拖动、深色主题、整合搜索与本地文件浏览。
 * viewMode/keyword/localDir 由外部（ViewModel）管理，关闭再打开时画面保持不变。
 */
@Composable
fun DanmakuSearchSheet(
    show: Boolean,
    sources: List<DanmakuSource>,
    downloadState: DanmakuDownloadState,
    currentViewMode: DanmakuSearchViewMode,
    onViewModeChange: (DanmakuSearchViewMode) -> Unit,
    currentKeyword: String,
    onKeywordChange: (String) -> Unit,
    currentLocalDir: File,
    onLocalDirChange: (File) -> Unit,
    browserRoot: String,
    onSearch: (DanmakuSource, String) -> Unit,
    onSelectAnime: (AnimeMatch) -> Unit,
    onSelectEpisode: (EpisodeInfo) -> Unit,
    onDismiss: () -> Unit,
    onResetSearch: () -> Unit = onDismiss,
    onNavigateBack: () -> Unit = onResetSearch,
    onFileSelected: (File) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!show) return

    val context = LocalContext.current

    // 文件列表状态（由 localDir 驱动刷新，跨 show/hide 不需要持久化）
    var fileList by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isLoadingFiles by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(checkStoragePermission()) }

    LaunchedEffect(currentViewMode, currentLocalDir, hasPermission) {
        if (currentViewMode != DanmakuSearchViewMode.LOCAL_FILE) return@LaunchedEffect
        if (!hasPermission) {
            fileList = emptyList()
            isLoadingFiles = false
            return@LaunchedEffect
        }
        isLoadingFiles = true
        fileList = withContext(Dispatchers.IO) {
            try {
                val dir = if (currentLocalDir.isDirectory) currentLocalDir else File("/storage/emulated/0/Video")
                val files = listFilesInDirectory(dir, context)
                files.sortedWith(naturalFileComparator()).mapNotNull { file ->
                    if (file.isDirectory) {
                        FileItem(file.name, file, true)
                    } else if (file.name.endsWith(".xml", true) ||
                        file.name.endsWith(".json", true) ||
                        file.name.endsWith(".bilibili", true)
                    ) {
                        FileItem(file.name, file, false)
                    } else {
                        null
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
        isLoadingFiles = false
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 半透明背景 — 点击关闭
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
                .clickable(interactionSource = null, indication = null, onClick = onDismiss),
        )

        Card(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 80.dp, end = 12.dp)
                .widthIn(max = 320.dp)
                .heightIn(max = 480.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceColor),
            elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                when (currentViewMode) {
                    DanmakuSearchViewMode.SEARCH -> {
                        // ── 搜索框（关闭按钮内嵌右侧） ──
                        OutlinedTextField(
                            value = currentKeyword,
                            onValueChange = onKeywordChange,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            placeholder = {
                                Text(
                                    "搜索动漫...",
                                    color = TextSecondaryColor.copy(alpha = 0.5f),
                                    fontSize = 13.sp,
                                )
                            },
                            textStyle = TextStyle(color = TextPrimaryColor, fontSize = 13.sp),
                            singleLine = true,
                            trailingIcon = {
                                FluxIconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_close),
                                        contentDescription = "关闭",
                                        tint = TextSecondaryColor,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentColor,
                                unfocusedBorderColor = DividerColor,
                                cursorColor = AccentColor,
                                focusedContainerColor = SurfaceVariantColor,
                                unfocusedContainerColor = SurfaceVariantColor,
                            ),
                            shape = RoundedCornerShape(8.dp),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Search,
                            ),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    if (currentKeyword.isNotBlank() && sources.isNotEmpty()) {
                                        onSearch(sources.first(), currentKeyword)
                                    }
                                },
                            ),
                        )

                        // ── 本地文件入口 ──
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onViewModeChange(DanmakuSearchViewMode.LOCAL_FILE)
                                    hasPermission = checkStoragePermission()
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_danmaku),
                                contentDescription = null,
                                tint = AccentColor,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "本地文件",
                                color = TextPrimaryColor,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.weight(1f))
                            Text("›", color = TextSecondaryColor, fontSize = 14.sp)
                        }

                        // ── 搜索源（单行横向滑动） ──
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(sources) { source ->
                                FilterChip(
                                    selected = false,
                                    onClick = {
                                        if (currentKeyword.isNotBlank()) {
                                            onSearch(source, currentKeyword)
                                        }
                                    },
                                    label = {
                                        Text(
                                            source.name,
                                            fontSize = 10.sp,
                                            color = if (currentKeyword.isNotBlank()) AccentColor else TextSecondaryColor,
                                        )
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = AccentDimColor,
                                        selectedContainerColor = AccentDimColor,
                                    ),
                                    border = null,
                                )
                            }
                        }

                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(DividerColor),
                        )

                        // ── 内容区域 ──
                        when (val state = downloadState) {
                            is DanmakuDownloadState.Idle -> IdleHint()
                            is DanmakuDownloadState.Searching -> SearchingStep()
                            is DanmakuDownloadState.SearchResult -> SearchResultStep(
                                animeList = state.animeList,
                                onSelectAnime = onSelectAnime,
                                onBack = onResetSearch,
                            )
                            is DanmakuDownloadState.AnimeSelected -> EpisodeSelectionStep(
                                anime = state.anime,
                                episodes = state.episodes,
                                onSelectEpisode = onSelectEpisode,
                                onBack = onNavigateBack,
                            )
                            is DanmakuDownloadState.Downloading -> DownloadingStep()
                            is DanmakuDownloadState.Ready -> {}
                            is DanmakuDownloadState.Error -> ErrorStep(
                                message = state.message,
                                onRetry = onResetSearch,
                            )
                        }
                    }

                    DanmakuSearchViewMode.LOCAL_FILE -> {
                        val parent = currentLocalDir.parentFile
                        val canGoUp = parent != null
                            && parent != currentLocalDir
                            && currentLocalDir.absolutePath != browserRoot
                            && (parent.absolutePath + "/").startsWith(browserRoot + "/")

                        // 返回 + 标题
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FluxIconButton(
                                onClick = {
                                    if (canGoUp) {
                                        onLocalDirChange(parent!!)
                                    } else {
                                        onViewModeChange(DanmakuSearchViewMode.SEARCH)
                                    }
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_arrow_left),
                                    contentDescription = "返回",
                                    tint = TextPrimaryColor,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "选择弹幕文件",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextPrimaryColor,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }

                        // 当前路径
                        Text(
                            text = currentLocalDir.absolutePath,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondaryColor.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                        )

                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(DividerColor),
                        )

                        // 文件列表
                        if (!hasPermission) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    "需要存储权限才能浏览文件",
                                    color = TextSecondaryColor,
                                    style = MaterialTheme.typography.bodyMedium,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(12.dp))
                                TextButton(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                            context.startActivity(
                                                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                            )
                                        }
                                    },
                                ) {
                                    FluxText("授予权限", color = AccentColor)
                                }
                            }
                        } else if (isLoadingFiles) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                FluxCircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        } else if (fileList.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(150.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                FluxText(
                                    "未找到弹幕文件",
                                    color = TextSecondaryColor.copy(alpha = 0.6f),
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                items(fileList, key = { it.file.absolutePath }) { item ->
                                    FileItemRow(
                                        item = item,
                                        onClick = {
                                            if (item.isDirectory) {
                                                onLocalDirChange(item.file)
                                            } else {
                                                onFileSelected(item.file)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── 搜索相关步骤 ──

@Composable
private fun IdleHint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "输入关键字，点击搜索源开始搜索",
            color = TextSecondaryColor.copy(alpha = 0.4f),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun SearchingStep() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FluxCircularProgressIndicator(
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text("正在搜索…", color = TextSecondaryColor, fontSize = 12.sp)
        }
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FluxIconButton(onClick = onBack, modifier = Modifier.size(28.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = "返回",
                    tint = TextPrimaryColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "搜索结果",
                color = TextPrimaryColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        if (animeList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("未找到匹配结果", color = TextSecondaryColor.copy(alpha = 0.4f), fontSize = 12.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            ) {
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
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = anime.title,
                color = TextPrimaryColor,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (anime.type.isNotBlank()) {
                    Text(anime.type, color = AccentColor, fontSize = 11.sp)
                }
                if (anime.episodeCount > 0) {
                    Text(
                        "共${anime.episodeCount}集",
                        color = TextSecondaryColor,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeSelectionStep(
    anime: AnimeMatch,
    episodes: List<EpisodeInfo>,
    onSelectEpisode: (EpisodeInfo) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FluxIconButton(onClick = onBack, modifier = Modifier.size(28.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = "返回",
                    tint = TextPrimaryColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = anime.title,
                    color = TextPrimaryColor,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("选择剧集", color = TextSecondaryColor, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(4.dp))

        if (episodes.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无剧集信息", color = TextSecondaryColor.copy(alpha = 0.4f), fontSize = 12.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp),
            ) {
                items(episodes) { episode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectEpisode(episode) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = episode.title.ifBlank { "第${episode.episodeNumber}集" },
                            color = TextPrimaryColor,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "下载",
                            color = AccentColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadingStep() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(0.6f),
                color = AccentColor,
                trackColor = AccentDimColor,
            )
            Spacer(Modifier.height(8.dp))
            Text("正在下载弹幕…", color = TextSecondaryColor, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ErrorStep(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("加载失败", color = ErrorColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(message, color = TextSecondaryColor, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                shape = RoundedCornerShape(8.dp),
                contentPadding = ButtonDefaults.TextButtonContentPadding,
            ) {
                Text("重试", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

// ── 本地文件浏览器内部组件 ──

@Composable
private fun FileItemRow(item: FileItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FluxText(
            text = if (item.isDirectory) "📁" else "📄",
            modifier = Modifier.size(18.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = item.name,
            color = TextPrimaryColor,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class FileItem(
    val name: String,
    val file: File,
    val isDirectory: Boolean,
)

private fun checkStoragePermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

private fun listFilesInDirectory(dir: File, context: android.content.Context): List<File> {
    val directFiles = dir.listFiles()
    if (directFiles != null) return directFiles.toList()

    return try {
        val files = mutableListOf<File>()
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.MIME_TYPE,
        )
        val selection = "${MediaStore.Files.FileColumns.DATA} LIKE ?"
        val selectionArgs = arrayOf("${dir.absolutePath}/%")
        val cursor = context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection,
            selection,
            selectionArgs,
            null,
        )
        cursor?.use {
            val dataColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
            val seen = mutableSetOf<String>()
            while (it.moveToNext()) {
                val path = it.getString(dataColumn) ?: continue
                val relativePath = path.removePrefix(dir.absolutePath + "/")
                if (relativePath.contains("/")) {
                    val dirName = relativePath.substringBefore("/")
                    val childDir = File(dir, dirName)
                    if (seen.add(dirName) && childDir.isDirectory) {
                        files.add(childDir)
                    }
                } else {
                    if (seen.add(relativePath)) {
                        files.add(File(path))
                    }
                }
            }
        }
        files
    } catch (_: Exception) {
        emptyList()
    }
}

private fun naturalFileComparator(): Comparator<File> {
    val digitPattern = Pattern.compile("\\d+")
    return Comparator { a, b ->
        val nameA = a.name
        val nameB = b.name
        val matcherA = digitPattern.matcher(nameA)
        val matcherB = digitPattern.matcher(nameB)
        var indexA = 0
        var indexB = 0
        while (indexA < nameA.length && indexB < nameB.length) {
            val hasNumA = matcherA.find(indexA)
            val hasNumB = matcherB.find(indexB)
            if (hasNumA && hasNumB && matcherA.start() == indexA && matcherB.start() == indexB) {
                val numA = nameA.substring(matcherA.start(), matcherA.end()).toLong()
                val numB = nameB.substring(matcherB.start(), matcherB.end()).toLong()
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return@Comparator cmp
                indexA = matcherA.end()
                indexB = matcherB.end()
            } else {
                val charA = nameA[indexA]
                val charB = nameB[indexB]
                val cmp = charA.compareTo(charB)
                if (cmp != 0) return@Comparator cmp
                indexA++
                indexB++
            }
        }
        (nameA.length - indexA).compareTo(nameB.length - indexB)
    }
}
