package dev.anilbeesetti.nextplayer.feature.player.danmaku

import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.Settings
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.anilbeesetti.nextplayer.core.ui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.provider.MediaStore
import java.io.File
import java.util.regex.Pattern

/**
 * 本地弹幕文件浏览器（弹出式小窗口）。
 *
 * 以半透明黑色小窗口形式展示，支持目录导航、返回上级，
 * 浏览本地存储中的 XML/JSON 弹幕文件。
 */
@Composable
fun LocalDanmakuFileBrowser(
    show: Boolean,
    currentDir: File,
    startDir: File = File("/storage/emulated/0/Video"),
    onDirChange: (File) -> Unit = {},
    onFileSelected: (File) -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    if (!show) return

    val context = LocalContext.current
    var fileList by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var hasPermission by remember { mutableStateOf(checkStoragePermission()) }

    // 每次显示时重新检查权限
    LaunchedEffect(show) {
        hasPermission = checkStoragePermission()
    }

    LaunchedEffect(currentDir, hasPermission) {
        if (!hasPermission) {
            fileList = emptyList()
            isLoading = false
            return@LaunchedEffect
        }
        isLoading = true
        fileList = withContext(Dispatchers.IO) {
            try {
                val dir = if (currentDir.isDirectory) currentDir else startDir
                val files = listFilesInDirectory(dir, context)
                val sorted = files.sortedWith(naturalFileComparator())
                sorted.mapNotNull { file ->
                    if (file.isDirectory) {
                        FileItem(file.name, file, true)
                    } else if (file.name.endsWith(".xml", true) ||
                        file.name.endsWith(".json", true) ||
                        file.name.endsWith(".bilibili", true)) {
                        FileItem(file.name, file, false)
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        isLoading = false
    }

    val parent = currentDir.parentFile
    val canGoUp = parent != null
        && parent != currentDir
        && currentDir.absolutePath != startDir.absolutePath
        && (parent.absolutePath + "/").startsWith(startDir.absolutePath + "/")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = 56.dp, end = 12.dp)
                    .widthIn(max = 320.dp)
                    .fillMaxWidth(0.75f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                ) {
                    // ── 标题栏：返回按钮 + 标题 + 关闭 ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 返回上级按钮
                        if (canGoUp) {
                            IconButton(
                                onClick = { onDirChange(parent) },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_arrow_left),
                                    contentDescription = "返回上级",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.size(36.dp))
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Text(
                            text = "选择弹幕文件",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = "关闭",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    // 当前路径
                    Text(
                        text = currentDir.absolutePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // ── 内容区域 ──
                    if (!hasPermission) {
                        // 无权限提示
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = "需要存储权限才能浏览文件",
                                color = Color.White.copy(alpha = 0.7f),
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
                                Text("授予权限", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    } else if (isLoading) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(150.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(color = Color.White)
                        }
                    } else if (fileList.isEmpty()) {
                        Text(
                            text = "未找到弹幕文件",
                            color = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.padding(vertical = 32.dp),
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            items(fileList, key = { it.file.absolutePath }) { item ->
                                FileItemRow(
                                    item = item,
                                    onClick = {
                                        if (item.isDirectory) {
                                            onDirChange(item.file)
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

/** 检查是否有完整文件访问权限 */
private fun checkStoragePermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        true // Android 10 以下通过 manifest 权限即可
    }
}

/**
 * 列出目录中的文件。
 * 优先使用 File.listFiles()，如果返回 null（scoped storage 限制），
 * 则通过 MediaStore 回退查询。
 */
private fun listFilesInDirectory(dir: File, context: android.content.Context): List<File> {
    // 直接方式
    val directFiles = dir.listFiles()
    if (directFiles != null) return directFiles.toList()

    // MediaStore 回退
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
            val mimeColumn = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val seen = mutableSetOf<String>()
            while (it.moveToNext()) {
                val path = it.getString(dataColumn) ?: continue
                val mime = it.getString(mimeColumn)
                // 只取当前目录的直接子项（不包含子目录的深层文件）
                val relativePath = path.removePrefix(dir.absolutePath + "/")
                if (relativePath.contains("/")) {
                    // 这是子目录中的文件，只记录子目录名
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
    } catch (e: Exception) {
        emptyList()
    }
}

@Composable
private fun FileItemRow(
    item: FileItem,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.05f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (item.isDirectory) "📁" else "📄",
                modifier = Modifier.size(20.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = item.name,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private data class FileItem(
    val name: String,
    val file: File,
    val isDirectory: Boolean,
)

/** 自然排序比较器（数字按数值排序） */
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
