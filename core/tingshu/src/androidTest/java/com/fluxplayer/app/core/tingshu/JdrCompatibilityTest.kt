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
class JdrCompatibilityTest {
    @Test
    fun importSearchPersistReloadResolveAndUpdate(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = TingshuRepository.get(context)
        val file = File(context.cacheDir, "renamed-source.jdr")
        val entry = "jdr:flux-test"
        try {
            writePackage(file)
            repository.importSource(Uri.fromFile(file))
            val source = repository.sources.value.single { it.packageEntry == entry }
            assertEquals("jdr:fluxdemo", source.id)
            assertTrue(repository.menus(source.id).isEmpty())
            val (results, total) = repository.search(source.id, "测试", 1)
            assertEquals(1, total)
            assertTrue(repository.search(source.id, "测试", 2).first.isEmpty())
            val detail = repository.detail(source.id, results.single())
            assertEquals("第一章", detail.episodes.single().title)
            val restored = repository.book(detail.key)
            assertEquals(detail.jdrChapterExtras, restored.jdrChapterExtras)

            // Rebuild the engine, then resolve only from the saved snapshot.
            repository.setEnabled(entry, false)
            assertFalse(repository.sources.value.any { it.id == source.id })
            assertNotNull(runCatching { repository.resolve(restored, 0) }.exceptionOrNull())
            repository.setEnabled(entry, true)
            val audio = repository.resolve(repository.book(detail.key), 0)
            assertEquals("https://example.com/album/chapter.mp3", audio.url)
            assertEquals("https://example.com/", audio.headers["Referer"])
            assertEquals("session=fixture", audio.headers["Cookie"])

            file.writeText("invalid package")
            assertNotNull(runCatching { repository.importSource(Uri.fromFile(file)) }.exceptionOrNull())
            assertEquals(source, repository.sources.value.single { it.id == source.id })

            repository.setEnabled(entry, false)
            writePackage(file, "更新后的书源")
            repository.importSource(Uri.fromFile(file))
            assertFalse(repository.packages.value.single { it.entry == entry }.enabled)
            repository.setEnabled(entry, true)
            assertEquals("更新后的书源", repository.sources.value.single { it.id == source.id }.name)

            writePackage(file, packageId = "flux-other")
            assertNotNull(runCatching { repository.importSource(Uri.fromFile(file)) }.exceptionOrNull())
            assertEquals(1, repository.sources.value.count { it.id == source.id })
        } finally {
            repository.remove(entry)
            repository.remove("jdr:flux-other")
            file.delete()
        }
    }

    private fun writePackage(file: File, name: String = "测试书源", packageId: String = "flux-test") {
        val manifest = """
            {"id":"$packageId","name":"Fixture","version":"1","sources":[
              {"id":"fluxdemo","name":"$name","script":"demo.js"}
            ]}
        """.trimIndent()
        val script = """
            registerSource({
              id: 'fluxdemo',
              async search(p) {
                return [{id:'album', bookTitle:p.keyword, token:'search-token', nested:{value:7}}];
              },
              async chapters(p) {
                if (p.token !== 'search-token' || p.nested.value !== 7 || p.bookId !== 'album') throw Error('lost book extras');
                return [{chapter_id:'chapter', title:'第一章', token:'chapter-token'}];
              },
              async audio(p) {
                if (p.token !== 'chapter-token') throw Error('lost chapter extras');
                return {url:'https://example.com/'+p.bookId+'/'+p.chapterId+'.mp3',
                  headers:{Referer:'https://example.com/', Cookie:'session=fixture'}};
              }
            });
        """.trimIndent()
        ZipOutputStream(file.outputStream()).use { output ->
            mapOf("manifest.json" to manifest, "demo.js" to script).forEach { (path, content) ->
                output.putNextEntry(ZipEntry(path))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
    }
}
