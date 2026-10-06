package com.fluxplayer.app.core.tingshu

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JdrFeaturesTest {
    @Test
    fun coverQueriesRunInParallelDeduplicateAndDoNotBlockBrowsing(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = TingshuRepository.get(context)
        val file = File(context.cacheDir, "parallel-cover.jdr")
        val entry = "jdr:com.timbre.tingshu-netdisk"
        val id = "jdr:parallelcover"
        val arrived = CountDownLatch(4)
        val release = CountDownLatch(1)
        val server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    arrived.countDown()
                    if (!release.await(10, TimeUnit.SECONDS)) return MockResponse().setResponseCode(503)
                    return MockResponse().setBody("ok")
                }
            }
            start()
        }
        val manifest = """{"id":"com.timbre.tingshu-netdisk","name":"并行封面","version":"1.1.5","sources":[
          {"id":"parallelcover","name":"并行封面","script":"source.js","capabilities":["browse","cover"]}]}"""
        val script = """
            registerSource({id:'parallelcover',
              browse(){return {items:[1,2,3,4].map(i=>({id:String(i),name:'书'+i,type:'book'}))};},
              async cover(p){
                const r=await http.get('${server.url("/query/")}'+p.bookId);
                if(r.status!==200) throw Error('请求失败');
                return 'https://example.com/'+p.bookId+'.jpg';
              }
            });
        """.trimIndent()
        ZipOutputStream(file.outputStream()).use { output ->
            mapOf("manifest.json" to manifest, "source.js" to script).forEach { (name, data) ->
                output.putNextEntry(ZipEntry(name))
                output.write(data.toByteArray())
                output.closeEntry()
            }
        }
        try {
            repository.importSource(Uri.fromFile(file))
            val books = repository.browseJdr(id, null).books
            coroutineScope {
                val requests = (books + books.first()).map { book -> async(Dispatchers.IO) { repository.resolveCover(id, book.coverUrl) } }
                try {
                    assertTrue("四个封面查询应同时发出", arrived.await(5, TimeUnit.SECONDS))
                    assertEquals(4, withTimeout(1500) { repository.browseJdr(id, null).books.size })
                } finally {
                    release.countDown()
                }
                assertEquals(5, requests.awaitAll().size)
                assertEquals(4, server.requestCount)
            }
        } finally {
            release.countDown()
            repository.remove(entry)
            file.delete()
            server.shutdown()
        }
    }

    @Test
    fun legacyNetdiskPackageUsesTianyiRootAndChapterBookIds(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = TingshuRepository.get(context)
        val file = File(context.cacheDir, "legacy-netdisk.jdr")
        val entry = "jdr:com.timbre.tingshu-netdisk"
        val id = "jdr:cloud189"
        val manifest = """{"id":"com.timbre.tingshu-netdisk","name":"旧网盘包","version":"1.1.4","sources":[
          {"id":"cloud189","name":"天翼","script":"source.js","capabilities":["browse","chapters","cover"]}]}"""
        val script = """
            registerSource({id:'cloud189',
              browse(p){if(p.directoryId!=='-11') throw Error('天翼目录错误');return {items:[{id:'42',name:'测试书',type:'book'}]};},
              chapters(p){if(p.bookId!=='D_42') throw Error('书籍ID错误');return [{chapter_id:'1',title:'第一章'}];},
              cover(p){if(p.bookId!=='D_42') throw Error('封面ID错误');return 'https://example.com/legacy.jpg';}
            });
        """.trimIndent()
        ZipOutputStream(file.outputStream()).use { output ->
            mapOf("manifest.json" to manifest, "source.js" to script).forEach { (name, data) ->
                output.putNextEntry(ZipEntry(name))
                output.write(data.toByteArray())
                output.closeEntry()
            }
        }
        try {
            repository.importSource(Uri.fromFile(file))
            assertEquals("-11", repository.jdrConfiguration(id).initialDirectory)
            val book = repository.browseJdr(id, null).books.single()
            assertEquals("D_42", book.bookUrl)
            assertEquals("第一章", repository.detail(id, book).episodes.single().title)
            assertEquals("https://example.com/legacy.jpg", repository.resolveCover(id, book.coverUrl)!!.url)
        } finally {
            repository.remove(entry)
            file.delete()
        }
    }

    @Test
    fun loginStateTypedSettingsAndLazyCoverUseTheSameContract(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = TingshuRepository.get(context)
        val file = File(context.cacheDir, "cover-contract.jdr")
        val entry = "jdr:flux-cover-contract"
        val id = "jdr:covercontract"
        val manifest = """{"id":"flux-cover-contract","name":"封面契约","version":"1","sources":[
          {"id":"covercontract","name":"封面契约","script":"source.js","initialDirectory":"-11",
           "capabilities":["login","browse","cover"],"settings":[
             {"key":"enabled","label":"启用","type":"switch","default":"true"},
             {"key":"cookie","label":"Cookie","type":"password","hint":"网页登录后自动更新"}]}]}"""
        val script = """
            let browsed=false;
            registerSource({id:'covercontract',
              login(p){
                if(typeof p.state !== 'object' || p.config.enabled !== true) throw Error('参数类型错误');
                if(p.action==='webComplete') {
                  if(p.state.requestId!=='request') throw Error('登录状态丢失');
                  host.settings.set('cookie',p.cookies);
                  host.settings.set('hiddenToken','secret-token');
                }
                return {authenticated:true,state:{requestId:'request'}};
              },
              browse(p){
                if(p.directoryId!=='-11') throw Error('目录起点错误');
                browsed=true;
                return {items:[{id:'D_42',name:'测试书',type:'book',opaque:'extra'}]};
              },
              cover(p){
                if(!browsed) throw Error('源内存状态丢失');
                if(p.bookId!=='D_42' || p.opaque!=='extra' || p.config.hiddenToken!=='secret-token') throw Error('封面参数丢失');
                return {url:'https://example.com/cover.jpg',headers:{Referer:'https://example.com/',Cookie:p.config.cookie}};
              }
            });
        """.trimIndent()
        ZipOutputStream(file.outputStream()).use { output ->
            mapOf("manifest.json" to manifest, "source.js" to script).forEach { (name, data) ->
                output.putNextEntry(ZipEntry(name))
                output.write(data.toByteArray())
                output.closeEntry()
            }
        }
        try {
            repository.importSource(Uri.fromFile(file))
            assertEquals("网页登录后自动更新", repository.jdrConfiguration(id).fields.last().hint)
            val status = repository.jdrLogin(id, "status")
            repository.jdrLogin(id, "webComplete", status.state, "session=test")
            assertEquals("session=test", repository.jdrConfiguration(id).values["cookie"])
            val book = repository.browseJdr(id, null).books.single()
            assertTrue(book.coverUrl.startsWith("jdr-cover:"))
            val cover = repository.resolveCover(id, book.coverUrl)!!
            assertEquals("https://example.com/cover.jpg", cover.url)
            assertEquals("session=test", cover.headers["Cookie"])
            assertEquals("https://example.com/", cover.headers["Referer"])
            assertEquals(cover, repository.resolveCover(id, book.coverUrl))
        } finally {
            repository.remove(entry)
            file.delete()
        }
    }

    @Test
    fun jarStyleConfigSavesCredentialsRunsButtonsAndPreservesHiddenState(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = TingshuRepository.get(context)
        val file = File(context.cacheDir, "config-parity.jdr")
        val id = "jdr:configparity"
        val entry = "jdr:flux-config-parity"
        val manifest = """{"id":"flux-config-parity","name":"Config","version":"1","sources":[{"id":"configparity","name":"Config","script":"source.js"}]}"""
        val script = """
            registerSource({id:'configparity',
              getCustomConfigItems(){return [
                {type:'Text',key:'cookie',label:'Cookie'},
                {type:'Text',key:'token',label:'Token'},
                {type:'MultiSelect',key:'formats',label:'格式',options:['mp3','m4a'],default:['mp3']},
                {type:'Button',key:'verify',label:'验证',click(){
                  ExternalSourcePrefs.putString('configparity.token','updated-token');
                  ExternalSourcePrefs.putString('configparity.opaque','hidden-state');
                  return {message:'done'};
                }}
              ];},
              search(){return [{id:'book',bookTitle:ExternalSourcePrefs.getString('configparity.cookie','')+'|'+ExternalSourcePrefs.getString('configparity.token','')+'|'+ExternalSourcePrefs.getString('configparity.opaque','')}];}
            });
        """.trimIndent()
        ZipOutputStream(file.outputStream()).use { output ->
            mapOf("manifest.json" to manifest, "source.js" to script).forEach { (name, data) ->
                output.putNextEntry(ZipEntry(name))
                output.write(data.toByteArray())
                output.closeEntry()
            }
        }
        try {
            repository.importSource(Uri.fromFile(file))
            val config = repository.jdrConfiguration(id)
            assertEquals(listOf("text", "text", "multiselect", "button"), config.fields.map { it.type })
            repository.saveConfig(id, mapOf("cookie" to "test-cookie", "formats" to "mp3,m4a"))
            assertEquals("done", repository.jdrConfigAction(id, "verify", repository.jdrConfiguration(id).values))
            assertEquals("updated-token", repository.jdrConfiguration(id).values["token"])
            repository.saveConfig(id, mapOf("cookie" to "changed-cookie"))
            repository.setEnabled(entry, false)
            repository.setEnabled(entry, true)
            assertTrue(repository.config(id).any { it is com.github.eprendre.tingshu.utils.ConfigItem.Button })
            assertEquals("changed-cookie|updated-token|hidden-state", repository.search(id, "test", 1).first.single().title)
            val stored = context.getSharedPreferences("jdr_state", android.content.Context.MODE_PRIVATE).all.values.joinToString()
            assertFalse(stored.contains("changed-cookie"))
            assertFalse(stored.contains("updated-token"))
        } finally {
            repository.remove(entry)
            file.delete()
        }
    }

    @Test
    fun encryptedSettingsSessionRootAndCacheSurviveReload(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = TingshuRepository.get(context)
        val file = File(context.cacheDir, "features.jdr")
        val entry = "jdr:flux-feature-test"
        val id = "jdr:featuretest"
        val manifest = """{"id":"flux-feature-test","name":"Fixture","version":"1","sources":[
          {"id":"featuretest","name":"Feature test","script":"source.js","capabilities":["login","browse","chapters","audio"],
           "settings":[{"key":"username","label":"账号"},{"key":"password","label":"密码","type":"password"},
             {"key":"root","label":"听书路径","type":"directory","default":"0"}]}]}"""
        val script = """
            registerSource({id:'featuretest',
              async login(p) {
                if (p.action==='login') {
                  if(host.settings.get('password')!=='private-secret') throw Error('wrong password');
                  host.storage.set('session', {user:host.settings.get('username'),token:'private-token'});
                  host.cache.set('metadata', {available:true},60);
                }
                if(p.action==='logout') host.storage.remove('session');
                return {authenticated:!!host.storage.get('session'),message:JSON.stringify(host.cache.get('metadata'))};
              },
              async browse(p) {
                if (!host.storage.get('session')) throw Error('请先登录');
                return p.directoryId==='0' ? {items:[{id:'books',name:'有声书',type:'directory'}]} :
                  {items:[{id:'book-'+p.page,name:'测试书籍',type:'book',token:'extra'}],nextPage:p.page===1?2:null};
              },
              async chapters(p){ if(p.token!=='extra') throw Error('lost extras'); return [{chapter_id:'one',title:'第一章'}]; },
              async audio(){return 'https://example.com/one.mp3';}
            });
        """.trimIndent()
        ZipOutputStream(file.outputStream()).use { output ->
            mapOf("manifest.json" to manifest, "source.js" to script).forEach { (name, data) ->
                output.putNextEntry(ZipEntry(name))
                output.write(data.toByteArray())
                output.closeEntry()
            }
        }
        try {
            repository.importSource(Uri.fromFile(file))
            assertTrue(repository.jdrConfiguration(id).canLogin)
            assertFalse(repository.jdrLogin(id, "status").authenticated!!)
            assertNotNull(runCatching { repository.browseJdr(id, null) }.exceptionOrNull())
            repository.saveJdrConfiguration(id, mapOf("username" to "user", "password" to "private-secret", "root" to "0"))
            assertTrue(repository.jdrLogin(id, "login").authenticated!!)
            assertEquals("books", repository.browseJdr(id, null).folders.single().id)
            repository.saveJdrConfiguration(id, mapOf("root" to "books"))
            val page = repository.browseJdr(id, null)
            assertEquals("books", page.directoryId)
            assertEquals(2, page.nextPage)
            assertEquals("book-2", repository.browseJdr(id, "books", 2).books.single().bookUrl)
            assertEquals(1, repository.detail(id, page.books.single()).episodes.size)
            repository.setEnabled(entry, false)
            repository.setEnabled(entry, true)
            assertTrue(repository.jdrLogin(id, "status").authenticated!!)
            repository.clearJdrMetadataCache(id)
            assertEquals("null", repository.jdrLogin(id, "status").message)
            assertEquals("books", repository.jdrConfiguration(id).values["root"])
            val stored = context.getSharedPreferences("jdr_state", android.content.Context.MODE_PRIVATE).all.values.joinToString()
            assertFalse(stored.contains("private-secret"))
            assertFalse(stored.contains("private-token"))
            assertFalse(repository.jdrLogin(id, "logout").authenticated!!)
            val loggedOut = repository.jdrConfiguration(id).values
            assertEquals("", loggedOut["username"])
            assertEquals("", loggedOut["password"])
            assertEquals("books", loggedOut["root"])
            repository.setEnabled(entry, false)
            repository.setEnabled(entry, true)
            assertFalse(repository.jdrLogin(id, "status").authenticated!!)
        } finally {
            repository.remove(entry)
            file.delete()
        }
    }
}
