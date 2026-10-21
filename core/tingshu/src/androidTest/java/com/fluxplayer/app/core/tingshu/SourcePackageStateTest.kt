package com.fluxplayer.app.core.tingshu

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourcePackageStateTest {
    @Test
    fun refreshingCoverBypassesNullAndExpiredScriptCacheWithoutClearingOtherBooks(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = TingshuRepository.get(context)
        val entry = "jdr:com.timbre.tingshu-netdisk"
        val id = "jdr:coverrefresh"
        val file = File(context.cacheDir, "fresh-cover.jdr")
        val manifest = """{"id":"com.timbre.tingshu-netdisk","name":"封面刷新测试","version":"1.1.5","sources":[
          {"id":"coverrefresh","name":"刷新测试","script":"source.js","capabilities":["browse","cover"]}]}"""
        val script = """
            registerSource({id:'coverrefresh',
              browse(){return {items:[1,2].map(i=>({id:String(i),name:'书'+i,type:'book'}))};},
              cover(p){
                var key='cover:'+p.bookId;
                var cached=host.cache.get(key,null);
                if(cached) return cached.url;
                var count=storage.get('count:'+p.bookId,0)+1;
                storage.set('count:'+p.bookId,count);
                var url=p.bookId==='1'&&count===1?null:'https://example.com/'+p.bookId+'/'+count+'.jpg';
                host.cache.set(key,{url:url},300);
                return url;
              }
            });
        """.trimIndent()
        ZipOutputStream(file.outputStream()).use { output ->
            mapOf("manifest.json" to manifest, "source.js" to script).forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        try {
            repository.importSource(Uri.fromFile(file))
            val books = repository.browseJdr(id, null).books
            assertEquals(null, repository.resolveCover(id, books[0].coverUrl))
            assertEquals("https://example.com/2/1.jpg", repository.resolveCover(id, books[1].coverUrl)?.url)
            assertEquals("https://example.com/1/2.jpg", repository.resolveCover(id, books[0].coverUrl, forceRefresh = true)?.url)
            assertEquals("https://example.com/1/3.jpg", repository.resolveCover(id, books[0].coverUrl, forceRefresh = true)?.url)
            assertEquals("https://example.com/2/1.jpg", repository.resolveCover(id, books[1].coverUrl)?.url)
        } finally {
            repository.remove(entry)
            file.delete()
        }
    }

    @Test
    fun bilibiliUsesInlineCoverWithoutInvokingCoverScript(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = TingshuRepository.get(context)
        val entry = "jdr:inline-cover-test"
        val file = File(context.cacheDir, "inline-cover.jdr")
        val manifest = """{"id":"inline-cover-test","name":"直接封面测试","version":"1","sources":[
          {"id":"bilibili","name":"B站测试","script":"source.js","capabilities":["browse","cover"]}]}"""
        val script = """
            registerSource({id:'bilibili',
              browse(){return {items:[{id:'book',name:'书',type:'book',bookImage:'https://example.com/cover.jpg'}]};},
              cover(){throw Error('不应再次查询封面');}
            });
        """.trimIndent()
        ZipOutputStream(file.outputStream()).use { output ->
            mapOf("manifest.json" to manifest, "source.js" to script).forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        try {
            repository.importSource(Uri.fromFile(file))
            val book = repository.browseJdr("jdr:bilibili", null).books.single()
            assertEquals("https://example.com/cover.jpg", book.coverUrl)
            val resource = repository.resolveCover("jdr:bilibili", book.coverUrl)
            assertEquals(book.coverUrl, resource?.url)
            assertEquals("https://www.bilibili.com/", resource?.headers?.get("Referer"))
        } finally {
            repository.remove(entry)
            file.delete()
        }
    }

    @Test
    fun disabledJarRetainsNameAndPersistedSourceMetadata(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = TingshuRepository.get(context)
        val file = File(context.cacheDir, "sources_by_quark.jar")
        instrumentation.context.assets.open("sources_by_quark.jar").use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        val entry = "sources_by_quark"
        try {
            repository.importSource(Uri.fromFile(file))
            val before = repository.packages.value.single { it.entry == entry }
            assertTrue(before.sources.isNotEmpty())
            repository.setEnabled(entry, false)
            val disabled = repository.packages.value.single { it.entry == entry }
            assertEquals(before.displayName, disabled.displayName)
            assertEquals(before.sources, disabled.sources)
            assertFalse(repository.sources.value.any { it.packageEntry == entry })
            val stored = JSONArray(context.getSharedPreferences("tingshu_library", Context.MODE_PRIVATE).getString("packages", "[]"))
            val snapshot = (0 until stored.length()).map(stored::getJSONObject).single { it.getString("entry") == entry }
            assertEquals(before.displayName, snapshot.getString("name"))
            assertEquals(before.sources.size, snapshot.getJSONArray("sources").length())
            // 模拟旧版已停用但尚未记录名称的包，升级时应能补全展示信息。
            snapshot.remove("name")
            snapshot.remove("sources")
            context.getSharedPreferences("tingshu_library", Context.MODE_PRIVATE).edit().putString("packages", stored.toString()).commit()
            repository.setEnabled(entry, false)
            assertEquals(before.displayName, repository.packages.value.single { it.entry == entry }.displayName)
            assertFalse(repository.sources.value.any { it.packageEntry == entry })
            repository.setEnabled(entry, true)
            assertEquals(before.sources.map { it.id }, repository.sources.value.filter { it.packageEntry == entry }.map { it.id })
        } finally {
            repository.remove(entry)
            file.delete()
        }
    }

    @Test
    fun disabledJdrRetainsAllSourcesAndCoverEnginesAreReused(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = TingshuRepository.get(context)
        val entry = "jdr:com.timbre.tingshu-netdisk"
        val id = "jdr:reusecover"
        val file = File(context.cacheDir, "retained-sources.jdr")
        val manifest = """{"id":"com.timbre.tingshu-netdisk","name":"保留源名称测试","version":"1.1.5","sources":[
          {"id":"reusecover","name":"测试网盘一","script":"first.js","capabilities":["browse","cover"]},
          {"id":"anothersource","name":"测试网盘二","script":"second.js","capabilities":["browse"]}]}"""
        val first = """
            var count = 0;
            registerSource({id:'reusecover',
              browse(){return {items:[1,2,3].map(i=>({id:String(i),name:'书'+i,type:'book'}))};},
              cover(){count++;return 'https://example.com/'+count+'.jpg';}
            });
        """.trimIndent()
        ZipOutputStream(file.outputStream()).use { output ->
            mapOf("manifest.json" to manifest, "first.js" to first, "second.js" to "registerSource({id:'anothersource',browse(){return {items:[]};}});").forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        try {
            repository.importSource(Uri.fromFile(file))
            val books = repository.browseJdr(id, null).books
            assertEquals("https://example.com/1.jpg", repository.resolveCover(id, books[0].coverUrl)?.url)
            assertEquals("https://example.com/2.jpg", repository.resolveCover(id, books[1].coverUrl)?.url)
            repository.saveJdrConfiguration(id, emptyMap())
            assertEquals("配置更新后应使用新环境", "https://example.com/1.jpg", repository.resolveCover(id, books[2].coverUrl)?.url)
            repository.setEnabled(entry, false)
            val disabled = repository.packages.value.single { it.entry == entry }
            assertEquals("保留源名称测试", disabled.displayName)
            assertEquals(listOf("测试网盘一", "测试网盘二"), disabled.sources.map { it.name })
            assertFalse(repository.sources.value.any { it.packageEntry == entry })
            repository.setEnabled(entry, true)
            assertEquals(2, repository.sources.value.count { it.packageEntry == entry })
        } finally {
            repository.remove(entry)
            file.delete()
        }
    }
}
