package com.fluxplayer.app.core.tingshu

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 书源集合包（.zip 里放多个 JAR）的导入行为。 */
@RunWith(AndroidJUnit4::class)
class SourceBundleImportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val repository = TingshuRepository.get(context)
    private val imported = mutableSetOf<String>()

    @After
    fun cleanUp() {
        imported.forEach { runCatching { runBlocking { repository.remove(it) } } }
        imported.clear()
        File(context.cacheDir, "bundle").deleteRecursively()
    }

    @Test
    fun importsEveryJarInsideZipBundle(): Unit = runBlocking {
        val zip = bundle(
            "jars/sources_by_pan123.jar" to asset("sources_by_pan123.jar"),
            "sources_by_quark.jar" to asset("sources_by_quark.jar"),
            "readme.txt" to "书源集合包说明".toByteArray(),
            "__MACOSX/._sources_by_quark.jar" to byteArrayOf(0, 1, 2, 3),
        )
        imported += listOf("sources_by_pan123", "sources_by_quark")

        repository.importSource(Uri.fromFile(zip))

        val entries = repository.packages.value.map { it.entry }.toSet()
        assertTrue("集合包里的两个 JAR 都应该导入：$entries", entries.containsAll(imported))
        imported.forEach { entry ->
            val pkg = repository.packages.value.single { it.entry == entry }
            assertTrue("$entry 必须至少包含一个书源", pkg.sources.isNotEmpty())
            assertEquals("$entry 不应该带错误", null, pkg.error)
            val file = File(context.filesDir, "tingshu/packages/${pkg.file}")
            assertTrue("$entry 的包文件应该落盘", file.isFile)
            assertTrue("$entry 的包文件必须是只读", !file.canWrite())
        }
        assertTrue(repository.sources.value.any { it.packageEntry == "sources_by_pan123" })
        assertTrue(repository.sources.value.any { it.packageEntry == "sources_by_quark" })
    }

    @Test
    fun brokenMemberRollsBackWholeBundle(): Unit = runBlocking {
        val zip = bundle(
            "sources_by_pan123.jar" to asset("sources_by_pan123.jar"),
            // 没有 classes.dex，必然过不了 DEX 校验
            "sources_by_broken.jar" to "not a dex".toByteArray(),
        )
        imported += "sources_by_pan123"

        val error = runCatching { repository.importSource(Uri.fromFile(zip)) }.exceptionOrNull()

        assertNotNull("坏包必须让整包导入失败", error)
        assertTrue(
            "错误信息要指出是哪个 JAR：${error?.message}",
            error?.message.orEmpty().contains("sources_by_broken.jar"),
        )
        assertTrue(
            "整包回滚后不应留下任何成员",
            repository.packages.value.none { it.entry == "sources_by_pan123" || it.entry == "sources_by_broken" },
        )
    }

    @Test
    fun rejectsBundleWithoutJar(): Unit = runBlocking {
        val zip = bundle("readme.txt" to "只有说明".toByteArray())
        val error = runCatching { repository.importSource(Uri.fromFile(zip)) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error?.message.orEmpty().contains("没有找到 JAR 书源"))
    }

    private fun asset(name: String): ByteArray = instrumentation.context.assets.open(name).use { it.readBytes() }

    private fun bundle(vararg members: Pair<String, ByteArray>): File {
        val directory = File(context.cacheDir, "bundle").apply { mkdirs() }
        val file = File(directory, "sources-bundle-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            members.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }
}
