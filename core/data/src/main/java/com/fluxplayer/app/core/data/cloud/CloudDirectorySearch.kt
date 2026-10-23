package com.fluxplayer.app.core.data.cloud

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

data class CloudSearchHit(val file: CloudRemoteFile, val folderPath: String, val trail: List<Pair<String, String>> = emptyList())
data class CloudSearchProgress(
    val hits: List<CloudSearchHit>,
    val folders: Int,
    val entries: Int,
    val failedFolders: Int,
    val limited: Boolean = false,
    val issues: List<CloudSearchIssue> = emptyList(),
)

data class CloudSearchIssue(val path: String, val message: String)

/** 目录缓存仅属于当前搜索会话，边界与文析保持一致。 */
class CloudDirectorySearchCache(private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(val timestamp: Long, val files: List<CloudRemoteFile>)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)
    private var fileCount = 0

    @Synchronized
    fun get(id: String): List<CloudRemoteFile>? {
        val entry = entries[id] ?: return null
        if (now() - entry.timestamp !in 0 until 45_000) {
            fileCount -= entry.files.size
            entries.remove(id)
            return null
        }
        return entry.files
    }

    @Synchronized
    fun put(id: String, files: List<CloudRemoteFile>) {
        entries.remove(id)?.let { fileCount -= it.files.size }
        if (files.size > 20_000) return
        while (entries.isNotEmpty() && (entries.size >= 128 || fileCount + files.size > 20_000)) {
            val oldest = entries.entries.iterator()
            fileCount -= oldest.next().value.files.size
            oldest.remove()
        }
        entries[id] = Entry(now(), files.toList())
        fileCount += files.size
    }

    @Synchronized
    fun clear() {
        entries.clear()
        fileCount = 0
    }

    @Synchronized
    fun invalidate(id: String) {
        entries.remove(id)?.let { fileCount -= it.files.size }
    }
}

private data class SearchDirectory(val id: String, val path: String, val depth: Int, val trail: List<Pair<String, String>>)
private data class SearchDirectoryRead(val directory: SearchDirectory, val files: List<CloudRemoteFile>?, val error: Exception?)

/** 与文析一样并发读取两个目录、复用短期缓存，并在限流后降为串行。 */
suspend fun searchCloudTree(
    query: String,
    root: String,
    rootLabel: String,
    read: suspend (String) -> List<CloudRemoteFile>,
    onProgress: (CloudSearchProgress) -> Unit,
    cache: CloudDirectorySearchCache = CloudDirectorySearchCache(),
    category: CloudSearchCategory = CloudSearchCategory.All,
    validate: () -> Unit = {},
): CloudSearchProgress = coroutineScope {
    require(query.isNotBlank() || category != CloudSearchCategory.All) { "请输入搜索关键词" }
    val pending = ArrayDeque<SearchDirectory>()
    pending.add(SearchDirectory(root, rootLabel, 0, listOf(root to rootLabel)))
    val seen = mutableSetOf(root)
    val seenFiles = mutableSetOf<Pair<String, String>>()
    val issues = mutableListOf<CloudSearchIssue>()
    val hits = mutableListOf<CloudSearchHit>()
    val keyword = query.trim()
    var folders = 0
    var entries = 0
    var failures = 0
    var consecutiveFailures = 0
    var limited = false
    var halt = false
    val parallel = AtomicInteger(2)
    fun progress() = CloudSearchProgress(hits.toList(), folders, entries, failures, limited, issues.toList())
    while (pending.isNotEmpty() && !halt) {
        currentCoroutineContext().ensureActive()
        validate()
        if (folders >= 2000 || entries >= 50000 || hits.size >= 5000) {
            limited = true
            break
        }
        val count = minOf(parallel.get(), pending.size, 2000 - folders)
        val completed = Channel<SearchDirectoryRead>(count)
        repeat(count) {
            val directory = pending.removeFirst()
            val id = directory.id
            launch {
                val outcome = try {
                    validate()
                    val files = cache.get(id) ?: cloudSearchRead(validate, onRateLimit = { parallel.set(1) }) { read(id) }
                        .also {
                            validate()
                            cache.put(id, it)
                        }
                    currentCoroutineContext().ensureActive()
                    validate()
                    SearchDirectoryRead(directory, files, null)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    SearchDirectoryRead(directory, null, error)
                }
                completed.send(outcome)
            }
        }
        repeat(count) {
            val result = completed.receive()
            validate()
            folders++
            if (result.error != null) {
                failures++
                consecutiveFailures++
                if (issues.size < 30) issues.add(CloudSearchIssue(result.directory.path, result.error.message ?: "文件夹读取失败，请检查网络后重试"))
                onProgress(progress())
                if (isCloudSearchLoginFailure(result.error)) throw result.error
                if (consecutiveFailures >= 5) error("连续多个文件夹读取失败，已停止搜索，请检查网络或登录状态")
            } else {
                consecutiveFailures = 0
                for (file in checkNotNull(result.files)) {
                    currentCoroutineContext().ensureActive()
                    if (entries >= 50000 || hits.size >= 5000) {
                        limited = true
                        halt = true
                        break
                    }
                    entries++
                    if (!seenFiles.add(result.directory.id to file.id)) continue
                    val actual = file.copy(parentId = result.directory.id)
                    if (file.name.contains(keyword, ignoreCase = true) && category.matches(file)) hits.add(CloudSearchHit(actual, result.directory.path, result.directory.trail))
                    if (file.directory && file.id !in seen) {
                        if (result.directory.depth >= 64 || seen.size >= 2000) {
                            limited = true
                        } else {
                            seen.add(file.id)
                            pending.add(SearchDirectory(file.id, "${result.directory.path} / ${file.name}", result.directory.depth + 1, result.directory.trail + (file.id to file.name)))
                        }
                    }
                    if (entries % 200 == 0) {
                        onProgress(progress())
                        yield()
                    }
                }
                onProgress(progress())
            }
        }
        completed.close()
    }
    progress().also(onProgress)
}
