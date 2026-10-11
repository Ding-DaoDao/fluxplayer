package com.fluxplayer.app.feature.tingshu

import android.app.Application
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxplayer.app.core.tingshu.TingshuRepository
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JdrSearchTest {
    @Test
    fun searchLoadsPagesRefreshesAndReturnsToBrowse() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = TingshuRepository.get(context)
        val file = File(context.cacheDir, "search-test.jdr")
        val entry = "jdr:search-test"
        val manifest = """{"id":"search-test","name":"测试","version":"1","sources":[
          {"id":"search-test","name":"测试","script":"source.js","capabilities":["search","browse"]}]}"""
        val script = """
            registerSource({id:'search-test',
              browse: async function(p) {return {items:[{id:'folder',name:'分类',type:'directory'}]};},
              search: async function(p) {
                return {items:[{id:'book'+p.page,bookTitle:p.keyword+p.page,token:'extra'}],totalPages:2};
              }
            });
        """.trimIndent()
        try {
            ZipOutputStream(file.outputStream()).use { output ->
                mapOf("manifest.json" to manifest, "source.js" to script).forEach { (name, text) ->
                    output.putNextEntry(ZipEntry(name))
                    output.write(text.toByteArray())
                    output.closeEntry()
                }
            }
            repository.importSource(Uri.fromFile(file))
            val source = repository.sources.value.single { it.packageEntry == entry }
            val model = TingshuViewModel(context.applicationContext as Application)
            instrumentation.runOnMainSync { model.open(source) }
            withTimeout(10_000) { while (model.state.value.loading) delay(50) }
            instrumentation.runOnMainSync { model.search("测试") }
            withTimeout(10_000) { while (model.state.value.loading) delay(50) }
            assertEquals("测试1", model.state.value.books.single().title)
            assertEquals(2, model.state.value.totalPages)
            assertTrue(model.state.value.books.single().jdrExtras.contains("extra"))
            instrumentation.runOnMainSync { assertTrue(model.loadMoreIfNeeded()) }
            withTimeout(10_000) { while (model.state.value.loading) delay(50) }
            assertEquals(listOf("book1", "book2"), model.state.value.books.map { it.bookUrl })
            instrumentation.runOnMainSync { assertTrue(!model.loadMoreIfNeeded()) }
            instrumentation.runOnMainSync { model.refresh() }
            withTimeout(10_000) { while (model.state.value.loading) delay(50) }
            assertEquals(1, model.state.value.page)
            assertEquals("测试", model.state.value.searchKeyword)
            instrumentation.runOnMainSync { model.back() }
            assertEquals("folder", model.state.value.folders.single().id)
            assertEquals(null, model.state.value.searchKeyword)
        } finally {
            repository.remove(entry)
            file.delete()
        }
    }

    @Test
    fun bilibiliLiveImportSearchAndPlay(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("bilibiliLive") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = TingshuRepository.get(context)
        val file = File(context.cacheDir, "bilibili-live.jdr")
        instrumentation.context.assets.open("com.fluxplayer.bilibili-0.1.2.jdr").use { input ->
            file.outputStream().use(input::copyTo)
        }
        try {
            repository.importSource(Uri.fromFile(file))
            val source = repository.sources.value.single { it.packageEntry == "jdr:com.fluxplayer.bilibili" }
            val home = repository.browseJdr(source.id, null)
            assertTrue(home.books.isNotEmpty())
            assertTrue(home.folders.isEmpty())
            assertEquals(7, home.shortcuts.size)
            assertTrue(repository.browseJdr(source.id, home.directoryId, 2).books.isNotEmpty())
            val monitor = instrumentation.addMonitor(JdrDiscoveryPreviewActivity::class.java.name, null, false)
            try {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(
                        "am start -W -n ${context.packageName}/${JdrDiscoveryPreviewActivity::class.java.name}",
                    ),
                ).bufferedReader().use { it.readText() }
                val preview = instrumentation.waitForMonitorWithTimeout(monitor, 15_000) as JdrDiscoveryPreviewActivity
                withTimeout(30_000) { while (preview.model.state.value.loading) delay(100) }
                assertTrue(preview.model.state.value.books.isNotEmpty())
                assertEquals(7, preview.model.state.value.shortcuts.size)
                delay(if (InstrumentationRegistry.getArguments().getString("bilibiliScreenshot") == "true") 15_000 else 1500)
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    File(context.cacheDir, "bilibili-home.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
                instrumentation.runOnMainSync { preview.model.browseDirectory("category:1", "广播剧", shortcut = true) }
                withTimeout(30_000) { while (preview.model.state.value.loading) delay(100) }
                assertTrue(preview.model.state.value.books.isNotEmpty())
                assertEquals("category:1", preview.model.state.value.directoryId)
                assertTrue(!preview.model.state.value.canGoBack)
                instrumentation.runOnMainSync { preview.model.refresh() }
                withTimeout(30_000) { while (preview.model.state.value.loading) delay(100) }
                assertEquals("category:1", preview.model.state.value.directoryId)
                instrumentation.runOnMainSync { preview.finish() }
            } finally {
                instrumentation.removeMonitor(monitor)
            }
            val (books, total) = repository.search(source.id, "有声小说", 1)
            assertTrue(books.isNotEmpty())
            assertTrue(total > 1)
            assertTrue(repository.search(source.id, "有声小说", 2).first.isNotEmpty())
            val direct = repository.search(source.id, "https://www.bilibili.com/video/${books.first().bookUrl}", 1).first.single()
            assertEquals(books.first().bookUrl, direct.bookUrl)
            val book = repository.detail(source.id, direct)
            assertTrue(book.episodes.isNotEmpty())
            android.os.ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "am start -W -n ${context.packageName}/${TingshuPlayerActivity::class.java.name} --es book ${book.key} --ei index 0",
                ),
            ).bufferedReader().use { it.readText() }
            withTimeout(60_000) {
                while (!ListeningPlayback.state.value.playing || ListeningPlayback.state.value.position < 3000) delay(200)
            }
            android.util.Log.i("BilibiliJdrTest", "搜索=${books.size} 总页=$total 章节=${book.episodes.size} 播放进度=${ListeningPlayback.state.value.position}")
        } finally {
            context.stopService(android.content.Intent(context, TingshuPlaybackService::class.java))
            repository.remove("jdr:com.fluxplayer.bilibili")
            file.delete()
        }
    }
}
