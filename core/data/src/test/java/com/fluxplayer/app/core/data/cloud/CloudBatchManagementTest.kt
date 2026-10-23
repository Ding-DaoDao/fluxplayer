package com.fluxplayer.app.core.data.cloud

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudBatchManagementTest {
    private fun files(count: Int) = (1..count).map { CloudRemoteFile("$it", "$it.mp4", false, it.toLong(), parentId = "source") }

    @Test
    fun largeSelectionIsChunkedAndEveryMoveIsConfirmed() = runBlocking {
        val source = files(121).toMutableList()
        val selected = source.toList()
        val destination = mutableListOf<CloudRemoteFile>()
        val chunks = mutableListOf<Int>()
        val manager = CloudBatchManager("0", { "owner" }, { if (it == "source") source.toList() else destination.toList() }, { entries, target ->
            assertEquals("target", target)
            chunks.add(entries.size)
            source.removeAll(entries.toSet())
            destination.addAll(entries)
        })
        val result = manager.execute("source", selected, "target")
        assertNull(result.message)
        assertEquals(121, result.completedIds.size)
        assertEquals(listOf(50, 50, 21), chunks)
    }

    @Test
    fun incompleteBatchStopsAndReportsOnlyVerifiedFilesWithoutResubmitting() = runBlocking {
        val source = files(60).toMutableList()
        val selected = source.toList()
        var writes = 0
        val manager = CloudBatchManager("0", { "owner" }, { source.toList() }, { entries, _ ->
            writes++
            source.removeAll(entries.take(2).toSet())
        }, {})
        val result = manager.execute("source", selected)
        assertEquals(1, writes)
        assertEquals(setOf("1", "2"), result.completedIds)
        assertNotNull(result.message)
        assertEquals(58, source.size)
    }

    @Test
    fun failedSecondBatchRetainsConfirmedFirstBatch() = runBlocking {
        val source = files(60).toMutableList()
        val selected = source.toList()
        var writes = 0
        val manager = CloudBatchManager("0", { "owner" }, { source.toList() }, { entries, _ ->
            if (++writes == 2) error("拒绝操作")
            source.removeAll(entries.toSet())
        })
        val result = manager.execute("source", selected)
        assertEquals(50, result.completedIds.size)
        assertEquals("拒绝操作", result.message)
        assertEquals(2, writes)
    }

    @Test
    fun staleSelectionIsRejectedBeforeAnyWrite() = runBlocking {
        var writes = 0
        val manager = CloudBatchManager("0", { "owner" }, { files(1).map { it.copy(name = "已重命名") } }, { _, _ -> writes++ })
        assertNotNull(manager.execute("source", files(1)).message)
        assertEquals(0, writes)
    }

    @Test
    fun accountChangeDuringReadPreventsSubmission() = runBlocking {
        var owner = "one"
        var writes = 0
        val manager = CloudBatchManager("0", { owner }, {
            owner = "two"
            files(1)
        }, { _, _ -> writes++ })
        assertNotNull(manager.execute("source", files(1)).message)
        assertEquals(0, writes)
    }

    @Test
    fun freshMetadataRatherThanSelectionSnapshotIsSubmitted() = runBlocking {
        val current = files(1).map { it.copy(token = "完整原始条目") }.toMutableList()
        val manager = CloudBatchManager("0", { "owner" }, { current.toList() }, { entries, _ ->
            assertEquals("完整原始条目", entries.single().token)
            current.clear()
        })
        assertNull(manager.execute("source", files(1)).message)
    }

    @Test
    fun moveRequiresTargetPresenceAsWellAsSourceAbsence() = runBlocking {
        val source = files(1).toMutableList()
        val manager = CloudBatchManager("0", { "owner" }, { if (it == "source") source.toList() else emptyList() }, { _, _ -> source.clear() }, {})
        val result = manager.execute("source", files(1), "target")
        assertTrue(result.completedIds.isEmpty())
        assertNotNull(result.message)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotReportedAsOrdinaryFailure() = runBlocking {
        CloudBatchManager("0", { "owner" }, { throw CancellationException() }, { _, _ -> }).execute("source", files(1))
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun selectingRootIsRejected() = runBlocking {
        CloudBatchManager("0", { "owner" }, { error("不应读取") }, { _, _ -> }).execute("source", listOf(CloudRemoteFile("0", "根目录", true, parentId = "source")))
        Unit
    }

    @Test(expected = IllegalArgumentException::class)
    fun movingToSameDirectoryIsRejected() = runBlocking {
        CloudBatchManager("0", { "owner" }, { error("不应读取") }, { _, _ -> }).execute("source", files(1), "source")
        Unit
    }
}
