package com.fluxplayer.app.core.data.cloud

import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

data class CloudSearchHit(val file: CloudRemoteFile, val folderPath: String)
data class CloudSearchProgress(
    val hits: List<CloudSearchHit>,
    val folders: Int,
    val entries: Int,
    val failedFolders: Int,
    val limited: Boolean = false,
)

/** 与文析一致，搜索遍历实际目录，不依赖各家行为不同的搜索参数。 */
suspend fun searchCloudTree(
    query: String,
    root: String,
    rootLabel: String,
    read: suspend (String) -> List<CloudRemoteFile>,
    onProgress: (CloudSearchProgress) -> Unit,
): CloudSearchProgress {
    require(query.isNotBlank()) { "请输入搜索关键词" }
    val pending = ArrayDeque<Pair<String, String>>()
    pending.add(root to rootLabel)
    val seen = mutableSetOf<String>()
    val hits = mutableListOf<CloudSearchHit>()
    var folders = 0
    var entries = 0
    var failures = 0
    var consecutiveFailures = 0
    var limited = false
    fun progress() = CloudSearchProgress(hits.toList(), folders, entries, failures, limited)
    while (pending.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        if (folders >= 2000 || entries >= 50000 || hits.size >= 5000) {
            limited = true
            break
        }
        val (id, path) = pending.removeFirst()
        if (!seen.add(id)) continue
        val files = try {
            read(id).also { consecutiveFailures = 0 }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failures++
            consecutiveFailures++
            onProgress(progress())
            if (folders == 0 || consecutiveFailures >= 3) throw error
            continue
        }
        folders++
        for (file in files) {
            currentCoroutineContext().ensureActive()
            if (entries >= 50000 || hits.size >= 5000) { limited = true; break }
            entries++
            val actual = file.copy(parentId = id)
            if (file.name.contains(query.trim(), ignoreCase = true)) hits.add(CloudSearchHit(actual, path))
            if (file.directory && file.id !in seen) pending.add(file.id to "$path / ${file.name}")
        }
        onProgress(progress())
        if (pending.isNotEmpty()) delay(150)
    }
    return progress().also(onProgress)
}
