package com.fluxplayer.app.feature.player.ui

import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.fluxplayer.app.core.ui.R
import com.fluxplayer.app.feature.player.extensions.getName
import com.fluxplayer.app.feature.player.state.SubtitleOptionsEvent
import com.fluxplayer.app.feature.player.state.rememberSubtitleOptionsState
import com.fluxplayer.app.feature.player.state.rememberTracksState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.regex.Pattern

// ── Dark theme colors ──
private val SurfaceVariantColor = Color(0xFF2A2A3E)
private val TextPrimaryColor = Color(0xFFE0E0E0)
private val TextSecondaryColor = Color(0xFF9CA3AF)
private val AccentColor = Color(0xFF60A5FA)
private val AccentDimColor = Color(0xFF1E3A5F)
private val DividerColor = Color(0xFF374151)

// ── Preset values ──
private val SUBTITLE_DELAY_PRESETS = longArrayOf(
    -30000, -15000, -10000, -5000, -2000, 0, 2000, 5000, 10000, 15000, 30000,
)
private val SUBTITLE_SPEED_PRESETS = floatArrayOf(
    0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f, 4.0f,
)

// ── Subtitle file extensions ──
private val SUBTITLE_EXTENSIONS = setOf("ass", "srt", "ssa", "sub", "vtt", "ttml", "smi", "txt")

private fun formatDelayMs(ms: Long): String {
    val s = ms / 1000f
    return if (ms >= 0) "+${"%.1f".format(s)}s" else "${"%.1f".format(s)}s"
}

private fun formatSpeed(sp: Float): String = "%.2fx".format(sp)

// ── File item ──
private data class FileItem(
    val name: String,
    val file: File,
    val isDirectory: Boolean,
)

@OptIn(UnstableApi::class)
@Composable
fun BoxScope.PlayerSettingsSheet(
    modifier: Modifier = Modifier,
    show: Boolean,
    player: Player,
    onSubtitleFileSelected: (Uri) -> Unit = {},
    onSubtitleOptionEvent: (SubtitleOptionsEvent) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val audioTracksState = rememberTracksState(player, C.TRACK_TYPE_AUDIO)
    val subtitleTracksState = rememberTracksState(player, C.TRACK_TYPE_TEXT)
    val subtitleOptionsState = rememberSubtitleOptionsState(player, onSubtitleOptionEvent)

    // ── 文件浏览器状态 ──
    var isBrowsing by remember { mutableStateOf(false) }
    var currentDir by remember { mutableStateOf(File("/storage/emulated/0")) }
    var fileList by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isLoadingFiles by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(checkStoragePermission()) }

    // 根目录 —— 不允许退回此目录之上
    val rootDir = remember { File("/storage/emulated/0") }

    LaunchedEffect(isBrowsing, currentDir, hasPermission) {
        if (!isBrowsing) return@LaunchedEffect
        if (!hasPermission) return@LaunchedEffect
        isLoadingFiles = true
        fileList = withContext(Dispatchers.IO) {
            try {
                val dir = if (currentDir.isDirectory) currentDir else File("/storage/emulated/0")
                val files = listFilesSafe(dir)
                files.sortedWith(naturalFileComparator()).mapNotNull { file ->
                    if (file.isDirectory) {
                        FileItem(file.name, file, true)
                    } else if (isSubtitleFile(file)) {
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

    if (isBrowsing) {
        SubtitleFileBrowser(
            show = show,
            currentDir = currentDir,
            rootDir = rootDir,
            fileList = fileList,
            isLoading = isLoadingFiles,
            hasPermission = hasPermission,
            onNavigateUp = {
                val parent = currentDir.canonicalFile.parentFile
                val root = rootDir.canonicalFile
                if (parent != null && parent.canonicalPath.startsWith(root.canonicalPath)) {
                    currentDir = parent
                } else {
                    // 已在根目录或之上 → 回设置面板（保留目录记忆）
                    isBrowsing = false
                }
            },
            onEnterDir = { currentDir = it },
            onFileSelected = { file ->
                onSubtitleFileSelected(Uri.fromFile(file))
                onDismiss()
            },
            onRequestPermission = {
                hasPermission = checkStoragePermission()
            },
            onDismiss = onDismiss,
        )
        return
    }

    OverlayView(
        modifier = modifier,
        show = show,
        title = stringResource(R.string.settings),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
                .padding(horizontal = 24.dp),
        ) {
            // ── 音轨 ──
            SectionLabel(stringResource(R.string.select_audio_track))
            Spacer(modifier = Modifier.size(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(
                    items = audioTracksState.tracks,
                    key = { i, t -> "audio_${t.mediaTrackGroup.hashCode()}_$i" },
                ) { index, track ->
                    DarkChip(
                        label = track.mediaTrackGroup.getName(C.TRACK_TYPE_AUDIO, index),
                        selected = track.isSelected,
                        onClick = { audioTracksState.switchTrack(index) },
                    )
                }
                item(key = "audio_off") {
                    DarkChip(
                        label = stringResource(R.string.disable),
                        selected = audioTracksState.tracks.none { it.isSelected },
                        onClick = { audioTracksState.switchTrack(-1) },
                    )
                }
            }

            Spacer(modifier = Modifier.size(16.dp))

            // ── 字幕 ──
            SectionLabel(stringResource(R.string.select_subtitle_track))
            Spacer(modifier = Modifier.size(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(
                    items = subtitleTracksState.tracks,
                    key = { i, t -> "sub_${t.mediaTrackGroup.hashCode()}_$i" },
                ) { index, track ->
                    DarkChip(
                        label = track.mediaTrackGroup.getName(C.TRACK_TYPE_TEXT, index),
                        selected = track.isSelected,
                        onClick = { subtitleTracksState.switchTrack(index) },
                    )
                }
                item(key = "sub_off") {
                    DarkChip(
                        label = stringResource(R.string.disable),
                        selected = subtitleTracksState.tracks.none { it.isSelected },
                        onClick = { subtitleTracksState.switchTrack(-1) },
                    )
                }
                item(key = "sub_external") {
                    DarkChip(
                        label = stringResource(R.string.open_subtitle),
                        selected = false,
                        accentOverride = true,
                        onClick = {
                            isBrowsing = true
                            hasPermission = checkStoragePermission()
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.size(20.dp))

            // ── 字幕延迟 ──
            SectionLabel("字幕延迟")
            Spacer(modifier = Modifier.size(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(
                    items = SUBTITLE_DELAY_PRESETS.toList(),
                    key = { "delay_$it" },
                ) { ms ->
                    DarkChip(
                        label = formatDelayMs(ms),
                        selected = subtitleOptionsState.delayMilliseconds == ms,
                        onClick = { subtitleOptionsState.setDelay(ms) },
                    )
                }
            }

            Spacer(modifier = Modifier.size(20.dp))

            // ── 字幕速度 ──
            SectionLabel("字幕速度")
            Spacer(modifier = Modifier.size(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(
                    items = SUBTITLE_SPEED_PRESETS.toList(),
                    key = { "speed_$it" },
                ) { sp ->
                    DarkChip(
                        label = formatSpeed(sp),
                        selected = subtitleOptionsState.speedMultiplier == sp,
                        onClick = { subtitleOptionsState.setSpeed(sp) },
                    )
                }
            }
        }
    }
}

// ── 文件浏览器 ──
@Composable
private fun BoxScope.SubtitleFileBrowser(
    show: Boolean,
    currentDir: File,
    rootDir: File,
    fileList: List<FileItem>,
    isLoading: Boolean,
    hasPermission: Boolean,
    onNavigateUp: () -> Unit,
    onEnterDir: (File) -> Unit,
    onFileSelected: (File) -> Unit,
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    OverlayView(
        show = show,
        title = "选择字幕文件",
    ) {
        Column(
            modifier = Modifier
                .padding(bottom = 24.dp)
                .padding(horizontal = 24.dp),
        ) {
            // 顶部导航栏 —— 只有一个返回按钮（上级目录 / 回设置面板）
            val isAtRoot = currentDir.canonicalFile == rootDir.canonicalFile
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = if (isAtRoot) "返回设置" else "上级目录",
                    tint = TextSecondaryColor,
                    modifier = Modifier
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onNavigateUp() }
                        .padding(4.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = currentDir.absolutePath,
                    color = TextSecondaryColor,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(8.dp))
            // 分割线
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(DividerColor),
            )
            Spacer(Modifier.height(8.dp))

            when {
                !hasPermission -> {
                    Text(
                        "需要存储权限才能浏览文件",
                        color = TextSecondaryColor,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    DarkChip(
                        label = "授予权限",
                        selected = true,
                        onClick = onRequestPermission,
                    )
                }
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = AccentColor)
                    }
                }
                fileList.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "未找到字幕文件",
                            color = TextSecondaryColor.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                        )
                    }
                }
                else -> {
                    LazyColumn {
                        items(
                            items = fileList,
                            key = { it.file.absolutePath },
                        ) { item ->
                            val displayName = if (item.isDirectory) "📁 ${item.name}" else item.name
                            val textColor = if (item.isDirectory) AccentColor else TextPrimaryColor
                            val fontWeight = if (item.isDirectory) FontWeight.Medium else FontWeight.Normal

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (item.isDirectory) onEnterDir(item.file)
                                        else onFileSelected(item.file)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = displayName,
                                    color = textColor,
                                    fontSize = 13.sp,
                                    fontWeight = fontWeight,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = TextSecondaryColor,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
private fun DarkChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    accentOverride: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = if (selected || accentOverride) AccentDimColor else SurfaceVariantColor
    val border = if (selected || accentOverride) AccentColor else DividerColor
    val textColor = if (selected || accentOverride) AccentColor else TextSecondaryColor
    val borderWidth = if (selected) 1.dp else 0.5.dp

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(bg, RoundedCornerShape(16.dp))
            .border(borderWidth, border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ── 工具函数 ──

private fun isSubtitleFile(file: File): Boolean {
    val ext = file.extension.lowercase()
    return ext in SUBTITLE_EXTENSIONS
}

private fun listFilesSafe(dir: File): List<File> {
    val directFiles = dir.listFiles()
    if (directFiles != null) return directFiles.toList()
    return emptyList()
}

private fun checkStoragePermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true
    }
}

private fun naturalFileComparator(): Comparator<File> {
    val digitPattern = Pattern.compile("\\d+")
    return Comparator { a, b ->
        val aIsDir = a.isDirectory
        val bIsDir = b.isDirectory
        if (aIsDir != bIsDir) return@Comparator if (aIsDir) -1 else 1

        val na = a.name
        val nb = b.name
        val ma = digitPattern.matcher(na)
        val mb = digitPattern.matcher(nb)

        var la = 0
        var lb = 0
        while (true) {
            val ha = ma.find(la)
            val hb = mb.find(lb)
            if (!ha && !hb) break

            val pa = if (ha) ma.start() else na.length
            val pb = if (hb) mb.start() else nb.length
            val prefixCmp = na.substring(la, pa).compareTo(nb.substring(lb, pb), ignoreCase = true)
            if (prefixCmp != 0) return@Comparator prefixCmp

            if (!ha || !hb) break
            val numA = na.substring(ma.start(), ma.end()).toLongOrNull() ?: 0
            val numB = nb.substring(mb.start(), mb.end()).toLongOrNull() ?: 0
            if (numA != numB) return@Comparator numA.compareTo(numB)

            la = ma.end()
            lb = mb.end()
        }
        na.length.compareTo(nb.length)
    }
}
