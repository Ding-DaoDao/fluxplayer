package com.fluxplayer.app.feature.tingshu

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxplayer.app.core.tingshu.TingshuRepository
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TingshuBrowseTest {
    @Test
    fun cloudLibrariesOpenBooksDirectlyAndDetailReturnsToBooks() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val repository = TingshuRepository.get(application)
        val fixture = File(application.cacheDir, "test_source.jar")
        instrumentation.context.assets.open("test_source.jar").use { input -> fixture.outputStream().use(input::copyTo) }
        repository.importJar(Uri.fromFile(fixture))
        val source = repository.sources.value.first { it.packageEntry == "test_source" }
        val store = ViewModelStore()
        val model = TingshuViewModel(application)
        instrumentation.runOnMainSync { store.put("browse", model) }
        try {
            for (entry in listOf("sources_by_pan123", "sources_by_quark", "sources_by_cloud189", "sources_by_yun139")) {
                val cloud = source.copy(packageEntry = entry)
                assertTrue(cloud.isCloudLibrary)
                instrumentation.runOnMainSync { model.open(cloud) }
                val root = withTimeout(10_000) { model.state.first { !it.loading } }
                assertEquals(null, root.error)
                assertTrue(root.menus.isEmpty())
                assertEquals(1, root.books.size)
                assertFalse(root.canGoBack)
                instrumentation.runOnMainSync { model.detail(root.books.single()) }
                withTimeout(10_000) { model.state.first { !it.loading } }
                assertTrue(model.state.value.detail != null)
                instrumentation.runOnMainSync { model.back() }
                assertEquals(root.books, model.state.value.books)
                assertFalse(model.state.value.canGoBack)
            }
            // 普通书源仍保留分类与搜索流程。
            assertFalse(source.isCloudLibrary)
            instrumentation.runOnMainSync { model.open(source) }
            val regular = withTimeout(10_000) { model.state.first { !it.loading } }
            assertTrue(regular.menus.isNotEmpty())
            assertTrue(regular.books.isEmpty())
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }
}
