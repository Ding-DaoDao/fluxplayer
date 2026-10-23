package com.fluxplayer.app.core.data.cloud

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudDirectorySearchTest {
    @Test
    fun categoryOnlySearchStillTraversesFoldersAndDeduplicatesFiles() = runBlocking {
        val result = searchCloudTree("", "root", "网盘", read = { id ->
            if (id == "root") {
                listOf(CloudRemoteFile("folder", "目录", true))
            } else {
                listOf(
                    CloudRemoteFile("movie", "MOVIE.MKV", false),
                    CloudRemoteFile("movie", "MOVIE.MKV", false),
                    CloudRemoteFile("doc", "movie.pdf", false),
                    CloudRemoteFile("root", "环", true),
                )
            }
        }, onProgress = {}, category = CloudSearchCategory.Video)
        assertEquals(listOf("movie"), result.hits.map { it.file.id })
        assertEquals(2, result.folders)
        assertEquals("folder", result.hits.single().file.parentId)
    }

    @Test
    fun depthLimitDoesNotDiscardOtherQueuedDirectories() = runBlocking {
        val result = searchCloudTree("电影", "0", "网盘", read = { id ->
            val depth = id.toInt()
            listOf(CloudRemoteFile("${depth + 1}", "目录", true), CloudRemoteFile("file$depth", "电影.mp4", false))
        }, onProgress = {})
        assertTrue(result.limited)
        assertEquals(65, result.folders)
        assertEquals(65, result.hits.size)
    }

    @Test
    fun fiveConsecutiveFailuresStopAndPreserveDirectoryIssues() = runBlocking {
        var latest = CloudSearchProgress(emptyList(), 0, 0, 0)
        val failure = runCatching {
            searchCloudTree("电影", "root", "网盘", read = { id ->
                if (id == "root") (1..8).map { CloudRemoteFile("$it", "目录$it", true) } else error("响应格式错误")
            }, onProgress = { latest = it })
        }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("连续多个"))
        assertEquals(5, latest.failedFolders)
        assertEquals(5, latest.issues.size)
        assertTrue(latest.issues.first().path.startsWith("网盘 / 目录"))
    }

    @Test
    fun loginFailureStopsImmediatelyWithoutRetrying() = runBlocking {
        var calls = 0
        val error = runCatching {
            searchCloudTree("电影", "root", "网盘", read = {
                calls++
                throw CloudSearchHttpException(401)
            }, onProgress = {})
        }.exceptionOrNull()
        assertTrue(error is CloudSearchHttpException)
        assertEquals(1, calls)
    }

    @Test
    fun browsedDirectorySnapshotAvoidsRefetchAndInvalidationRestoresFreshRead() = runBlocking {
        val cache = CloudDirectorySearchCache()
        cache.put("folder", listOf(CloudRemoteFile("file", "电影.mp4", false)))
        var reads = 0
        val read: suspend (String) -> List<CloudRemoteFile> = {
            reads++
            emptyList()
        }
        assertEquals(1, searchCloudTree("电影", "folder", "当前文件夹", read, {}, cache).hits.size)
        assertEquals(0, reads)
        cache.invalidate("folder")
        assertEquals(0, searchCloudTree("电影", "folder", "当前文件夹", read, {}, cache).hits.size)
        assertEquals(1, reads)
    }

    @Test
    fun accountChangeDuringReadCannotPublishOrCacheOldResults() = runBlocking {
        var session = "old"
        val cache = CloudDirectorySearchCache()
        val result = runCatching {
            searchCloudTree(
                "电影",
                "root",
                "网盘",
                read = {
                    session = "new"
                    listOf(CloudRemoteFile("file", "电影.mp4", false))
                },
                onProgress = {},
                cache = cache,
                validate = { check(session == "old") { "账号已变化" } },
            )
        }
        assertTrue(result.isFailure)
        assertEquals(null, cache.get("root"))
    }

    @Test
    fun readsTwoDirectoriesConcurrentlyAndPublishesFastResultFirst() = runBlocking {
        withTimeout(3000) {
            val slowRelease = CompletableDeferred<Unit>()
            val active = AtomicInteger()
            val peak = AtomicInteger()
            val result = searchCloudTree("movie", "root", "网盘", read = { id ->
                if (id == "root") {
                    (1..3).map { CloudRemoteFile("$it", "目录$it", true) }
                } else {
                    val count = active.incrementAndGet()
                    peak.updateAndGet { maxOf(it, count) }
                    if (id == "1") slowRelease.await()
                    active.decrementAndGet()
                    listOf(CloudRemoteFile("file$id", "movie$id.mp4", false))
                }
            }, onProgress = { progress ->
                if (progress.hits.any { it.file.id == "file2" }) slowRelease.complete(Unit)
            })
            assertEquals(2, peak.get())
            assertEquals("file2", result.hits.first().file.id)
            assertEquals(3, result.hits.size)
        }
    }

    @Test
    fun repeatedKeywordsReuseDirectoryReadsAndExpiredCacheRefreshes() = runBlocking {
        var now = 1000L
        var calls = 0
        val cache = CloudDirectorySearchCache { now }
        val read: suspend (String) -> List<CloudRemoteFile> = { id ->
            calls++
            if (id == "root") listOf(CloudRemoteFile("folder", "目录", true)) else listOf(CloudRemoteFile("file", "电影音乐.mp4", false))
        }
        searchCloudTree("电影", "root", "网盘", read, {}, cache)
        assertEquals(2, calls)
        assertEquals(1, searchCloudTree("音乐", "root", "网盘", read, {}, cache).hits.size)
        assertEquals(2, calls)
        now += 45_000
        searchCloudTree("音乐", "root", "网盘", read, {}, cache)
        assertEquals(4, calls)
    }

    @Test
    fun cancellingSearchCancelsBothReadsAndDoesNotCachePartialDirectories() = runBlocking {
        withTimeout(3000) {
            val started = CompletableDeferred<Unit>()
            val reading = AtomicInteger()
            val stopped = AtomicInteger()
            val cache = CloudDirectorySearchCache()
            val search = launch {
                searchCloudTree("电影", "root", "网盘", read = { id ->
                    if (id == "root") {
                        listOf(CloudRemoteFile("a", "甲", true), CloudRemoteFile("b", "乙", true))
                    } else {
                        if (reading.incrementAndGet() == 2) started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            stopped.incrementAndGet()
                        }
                    }
                }, onProgress = {}, cache = cache)
            }
            started.await()
            search.cancelAndJoin()
            assertEquals(2, stopped.get())
            assertEquals(null, cache.get("a"))
            assertEquals(null, cache.get("b"))
        }
    }

    @Test
    fun cacheEvictsOldDirectoriesAndClearInvalidatesSearchSnapshots() {
        val cache = CloudDirectorySearchCache()
        repeat(129) { cache.put("$it", listOf(CloudRemoteFile("file", "文件", false))) }
        assertEquals(null, cache.get("0"))
        assertTrue(cache.get("128") != null)
        cache.clear()
        assertEquals(null, cache.get("128"))
    }

    @Test
    fun failedRootReadIsRetriedOnNextSearchRatherThanCachedAsEmpty() = runBlocking {
        val cache = CloudDirectorySearchCache()
        var fail = true
        val read: suspend (String) -> List<CloudRemoteFile> = { if (fail) error("读取失败") else listOf(CloudRemoteFile("file", "电影.mp4", false)) }
        assertEquals(1, searchCloudTree("电影", "root", "网盘", read, {}, cache).failedFolders)
        fail = false
        assertFalse(searchCloudTree("电影", "root", "网盘", read, {}, cache).hits.isEmpty())
    }
}
