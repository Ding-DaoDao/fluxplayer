package com.fluxplayer.app.core.tingshu

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.eprendre.tingshu.sources.AudioUrlCustomExtractor
import com.github.eprendre.tingshu.sources.AudioUrlDirectExtractor
import com.github.eprendre.tingshu.utils.ConfigItem
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
class TingshuCompatibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository = TingshuRepository.get(context)

    @Test
    fun actualNetdiskJarsLoadConfigureDisableAndReload(): Unit = runBlocking {
        val names = listOf("pan123", "yun139", "cloud189", "quark")
        names.forEach { provider ->
            val filename = "sources_by_$provider.jar"
            val input = File(context.cacheDir, filename)
            instrumentation.context.assets.open(filename).use { source -> input.outputStream().use(source::copyTo) }
            repository.importJar(Uri.fromFile(input))
            val metadata = repository.sources.value.single { it.packageEntry == "sources_by_$provider" }
            assertTrue(metadata.name.isNotBlank())
            val configuration = repository.config(metadata.id)
            assertTrue("$provider 必须能读取配置项", configuration.isNotEmpty())
            val field = configuration.filterIsInstance<ConfigItem.Text>().first()
            repository.saveConfig(metadata.id, mapOf(field.key to "compatibility-test"))
            assertEquals("compatibility-test", SourceHost.getString("${metadata.id}.${field.key}", null))
            repository.setEnabled(metadata.packageEntry, false)
            assertFalse(repository.sources.value.any { it.id == metadata.id })
            repository.setEnabled(metadata.packageEntry, true)
            assertTrue(repository.sources.value.any { it.id == metadata.id })
            assertEquals(null, repository.packages.value.single { it.entry == metadata.packageEntry }.error)
            repository.saveConfig(metadata.id, mapOf(field.key to field.default))
            input.delete()
        }
        assertEquals(4, repository.sources.value.size)
        names.forEach { repository.remove("sources_by_$it") }
        assertTrue(repository.sources.value.isEmpty())
    }

    @Test
    fun invalidImportKeepsPreviouslyLoadedSource(): Unit = runBlocking {
        val filename = "sources_by_pan123.jar"
        val input = File(context.cacheDir, filename)
        instrumentation.context.assets.open(filename).use { source -> input.outputStream().use(source::copyTo) }
        repository.importJar(Uri.fromFile(input))
        val previous = repository.sources.value.single()
        input.writeText("invalid jar")
        val error = runCatching { repository.importJar(Uri.fromFile(input)) }.exceptionOrNull()
        assertNotNull(error)
        assertEquals(previous, repository.sources.value.single())
        repository.remove("sources_by_pan123")
        input.delete()
    }

    @Test
    fun rejectsJvmJarWithoutDex() {
        val file = File(context.cacheDir, "invalid.jar")
        ZipOutputStream(file.outputStream()).use {
            it.putNextEntry(ZipEntry("Example.class"))
            it.write(byteArrayOf(1, 2, 3))
            it.closeEntry()
        }
        assertNotNull(runCatching { TingshuRepository.validateDex(file) }.exceptionOrNull())
        file.delete()
    }

    @Test
    fun customAndDirectExtractionKeepTheirOwnInvocationContext() {
        AudioUrlCustomExtractor.setUp { "https://example.com/$it.mp3" }
        AudioUrlCustomExtractor.extract("chapter", true, false, false)
        assertEquals("https://example.com/chapter.mp3", SourceHost.extractedUrl.get())
        AudioUrlDirectExtractor.extract("https://example.com/direct.mp3", true, false, false)
        assertEquals("https://example.com/direct.mp3", SourceHost.extractedUrl.get())
        SourceHost.extractedUrl.remove()
    }
}
