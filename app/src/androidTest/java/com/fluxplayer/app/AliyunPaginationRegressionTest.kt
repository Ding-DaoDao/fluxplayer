package com.fluxplayer.app

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxplayer.app.core.common.CustomDownloadManager
import com.fluxplayer.app.core.common.VideoQualityCache
import com.fluxplayer.app.core.data.aliyun.AliyunApiClient
import com.fluxplayer.app.core.data.cloud.CloudUriResolver
import com.fluxplayer.app.core.data.repository.CloudDownloadRepository
import com.fluxplayer.app.core.data.repository.PlaybackHistoryRepository
import com.fluxplayer.app.core.database.dao.DownloadTaskDao
import com.fluxplayer.app.core.model.PlaybackHistory
import com.fluxplayer.app.feature.videopicker.aliyun.AliyunBrowserViewModel
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 使用模拟接口验证真实 ViewModel，不依赖设备上的网盘账号。 */
class AliyunPaginationRegressionTest {
    @Test
    fun returningFromChildRetainsAllPagesAndTheNextCursor() = runBlocking {
        withBrowser { browser, requests, _ ->
            main { browser.loadDirectory("root") }
            await { browser.uiState.value.nextMarker == "page2" }
            main { browser.loadMore() }
            await { browser.uiState.value.nextMarker == "page3" }
            assertEquals(listOf("child", "file1", "file2"), browser.uiState.value.items.map { it.path })
            main { browser.navigateToDir(0) }
            await { browser.uiState.value.currentFileId == "child" && !browser.uiState.value.isLoading }
            main { browser.navigateUp() }
            assertEquals(listOf("child", "file1", "file2"), browser.uiState.value.items.map { it.path })
            assertEquals("page3", browser.uiState.value.nextMarker)
            main { browser.loadMore() }
            await { browser.uiState.value.items.any { it.path == "file3" } }
            assertNull(browser.uiState.value.nextMarker)
            assertEquals(listOf("", "page2", "", "page3"), requests.map { it.optString("marker") })
            assertTrue(requests.all { it.optString("order_by") == "name" && it.optString("order_direction") == "ASC" })
        }
    }

    @Test
    fun returningToCachedParentIgnoresAnOutstandingChildResponse() = runBlocking {
        withBrowser { browser, _, childGate ->
            main { browser.loadDirectory("root") }
            await { browser.uiState.value.nextMarker == "page2" }
            main { browser.navigateToDir(0) }
            await { browser.uiState.value.currentFileId == "child" }
            main { browser.navigateUp() }
            childGate.countDown()
            delay(300)
            assertEquals("root", browser.uiState.value.currentFileId)
            assertEquals(listOf("child", "file1"), browser.uiState.value.items.map { it.path })
            assertEquals("page2", browser.uiState.value.nextMarker)
        }
    }

    private suspend fun withBrowser(
        block: suspend (AliyunBrowserViewModel, List<JSONObject>, CountDownLatch) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val preferences = (application as NextPlayerApplication).preferencesRepository
        val history = Proxy.newProxyInstance(PlaybackHistoryRepository::class.java.classLoader, arrayOf(PlaybackHistoryRepository::class.java)) { _, method, _ ->
            if (method.name == "getAllHistoryFlow") flowOf(emptyList<PlaybackHistory>()) else error("测试未使用的方法：${method.name}")
        } as PlaybackHistoryRepository
        val downloadDao = Proxy.newProxyInstance(DownloadTaskDao::class.java.classLoader, arrayOf(DownloadTaskDao::class.java)) { _, method, _ ->
            error("测试未使用下载功能：${method.name}")
        } as DownloadTaskDao
        val requests = CopyOnWriteArrayList<JSONObject>()
        val gate = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            requireNotNull(chain.request().body).writeTo(buffer)
            val request = JSONObject(buffer.readUtf8())
            requests += request
            val child = request.optString("parent_file_id") == "child"
            if (child) gate.await(500, TimeUnit.MILLISECONDS)
            val marker = request.optString("marker")
            val items = JSONArray()
            val next = when {
                child -> {
                    items.put(item("childFile", "file"))
                    ""
                }
                marker == "page2" -> {
                    items.put(item("file2", "file"))
                    "page3"
                }
                marker == "page3" -> {
                    items.put(item("file3", "file"))
                    ""
                }
                else -> {
                    items.put(item("child", "folder")).put(item("file1", "file"))
                    "page2"
                }
            }
            val body = JSONObject().put("items", items).put("next_marker", next).toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("test").body(body.toResponseBody()).build()
        }.build()
        val store = ViewModelStore()
        lateinit var browser: AliyunBrowserViewModel
        main {
            browser = AliyunBrowserViewModel(
                application,
                preferences,
                CloudUriResolver(application, VideoQualityCache(application)),
                history,
                CloudDownloadRepository(downloadDao, CustomDownloadManager(), preferences),
            )
            // 仅替换测试实例的接口客户端，所有请求都由内存响应处理。
            val field = AliyunBrowserViewModel::class.java.getDeclaredField("apiClient").apply { isAccessible = true }
            field.set(browser, AliyunApiClient(client).apply { driveId = "test-drive" })
            store.put("browser", browser)
        }
        try {
            block(browser, requests, gate)
        } finally {
            gate.countDown()
            main { store.clear() }
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun item(id: String, type: String): JSONObject = JSONObject().put("file_id", id).put("name", id).put("type", type)

    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    private suspend fun await(condition: () -> Boolean) = withTimeout(5000) {
        while (!condition()) delay(20)
    }
}
