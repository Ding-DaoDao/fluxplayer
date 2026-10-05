package com.fluxplayer.app.core.tingshu

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JdrFeaturesTest {
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
            repository.setEnabled(entry, false)
            repository.setEnabled(entry, true)
            assertFalse(repository.jdrLogin(id, "status").authenticated!!)
        } finally {
            repository.remove(entry)
            file.delete()
        }
    }
}
