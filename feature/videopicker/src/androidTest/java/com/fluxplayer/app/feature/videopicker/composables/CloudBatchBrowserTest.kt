package com.fluxplayer.app.feature.videopicker.composables

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxplayer.app.core.data.cloud.CloudProviderFeatures
import com.fluxplayer.app.core.data.quark.QuarkApiClient
import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.core.ui.theme.NextPlayerTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CloudBatchBrowserTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun leftCheckboxSelectsWithoutOpeningAndRowStillOpensFile() {
        val opens = AtomicInteger()
        val requests = AtomicInteger()
        val entries = listOf(file("11", "电影.mp4"))
        val features = features { _, _, _, _ ->
            requests.incrementAndGet()
            listing(entries)
        }
        showBrowser(features, { entries }, onOpen = { opens.incrementAndGet() })
        val checkbox = compose.onNodeWithContentDescription("选择：电影.mp4")
        assertTrue(checkbox.fetchSemanticsNode().boundsInRoot.left < compose.onNodeWithText("电影.mp4", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left)
        checkbox.performClick()
        checkbox.assertIsOn()
        compose.onNodeWithText("已选 1 项").assertExists()
        assertEquals(0, opens.get())
        assertEquals(0, requests.get())
        compose.onNodeWithText("电影.mp4").performClick()
        assertEquals(1, opens.get())
        checkbox.assertIsOn()
        compose.onNodeWithContentDescription("取消选择").performClick()
        compose.onNodeWithText("已选 1 项").assertDoesNotExist()
        compose.onNodeWithContentDescription("菜单").performClick()
        compose.onNodeWithText("批量管理").assertDoesNotExist()
    }

    @Test
    fun changingDirectoryClearsSelectionInDirectoryStack() {
        val features = features { _, _, _, _ -> error("勾选和导航不应触发完整目录读取") }
        compose.setContent {
            var parent by remember { mutableStateOf("0") }
            val entries = if (parent == "0") listOf(WebDavResource("影视", "11", true)) else listOf(WebDavResource("电影.mp4", "12", false))
            val entry = object : com.fluxplayer.app.feature.videopicker.DirectoryState {
                override val key = parent
                override val items = entries
                override val isLoading = false
                override val error: String? = null
            }
            NextPlayerTheme(preferences = ApplicationPreferences()) {
                CloudBrowserPanel(
                    items = entries, breadcrumbs = listOf("根目录"), isLoading = false, isConfigured = true, error = null, isLoadingMore = false,
                    onItemClick = { parent = it.path }, onBreadcrumbClick = {}, onRefresh = {}, onLoadMore = {}, breadcrumbLabel = { it }, loginContent = {},
                    providerFeatures = features, currentFolderId = parent, navigationStack = listOf(entry),
                )
            }
        }
        compose.onNodeWithContentDescription("选择：影视").performClick()
        compose.onNodeWithText("已选 1 项").assertExists()
        compose.onNodeWithText("影视").performClick()
        waitFor("电影.mp4")
        compose.onNodeWithText("已选 1 项").assertDoesNotExist()
        compose.onNodeWithContentDescription("选择：电影.mp4").assertExists()
    }

    @Test
    fun selectAllButtonAppearsAfterSelectionAndReusesCompleteDirectory() {
        val reads = AtomicInteger()
        val entries = listOf(file("1", "视频1.mp4"), file("2", "视频2.mp4"))
        val features = features { _, _, _, _ ->
            reads.incrementAndGet()
            listing(entries)
        }
        showBrowser(features, { entries }, directoryComplete = true)
        compose.onNodeWithText("全选").assertDoesNotExist()
        compose.onNodeWithContentDescription("全选当前目录").assertDoesNotExist()
        compose.onNodeWithContentDescription("选择：视频1.mp4").performClick()
        assertTrue(compose.onNodeWithText("全选").fetchSemanticsNode().boundsInRoot.right <= compose.onNodeWithText("移动").fetchSemanticsNode().boundsInRoot.left)
        compose.onNodeWithText("全选").performClick()
        compose.onNodeWithText("已选 2 项").assertExists()
        compose.onNodeWithContentDescription("选择：视频2.mp4").assertIsOn()
        assertEquals(0, reads.get())
        compose.onNodeWithText("取消全选").performClick()
        compose.onNodeWithText("移动").assertDoesNotExist()
        assertEquals(0, reads.get())
    }

    @Test
    fun selectAllReadsLaterPagesOnlyWhenRequested() {
        val reads = AtomicInteger()
        val entries = (1..102).map { file("$it", "视频$it.mp4") }
        val features = features { _, _, page, _ ->
            reads.incrementAndGet()
            listing(entries.drop((page - 1) * 100).take(100))
        }
        showBrowser(features, { entries.take(100) })
        compose.onNodeWithContentDescription("选择：视频1.mp4").performClick()
        assertEquals(0, reads.get())
        compose.onNodeWithText("全选").performClick()
        waitFor("已选 102 项")
        assertEquals(2, reads.get())
        compose.onNodeWithText("取消全选").performClick()
        compose.onNodeWithText("移动").assertDoesNotExist()
        assertEquals(2, reads.get())
    }

    @Test
    fun onlyCheckedFilesAreDeletedAndDeletionRequiresConfirmation() {
        val entries = (1..102).map { file("$it", "视频$it.mp4") }.toMutableList()
        val writes = AtomicInteger()
        val features = features { path, parent, page, body ->
            when {
                path.endsWith("/sort") -> synchronized(entries) { listing(entries.drop((page - 1) * 100).take(100)) }
                path.endsWith("/delete") -> {
                    writes.incrementAndGet()
                    val ids = body.getJSONArray("filelist").let { (0 until it.length()).map(it::getString).toSet() }
                    synchronized(entries) { entries.removeAll { it.getString("fid") in ids } }
                    """{"status":200,"code":0,"data":{}}"""
                }
                else -> error("意外请求：$path $parent")
            }
        }
        showBrowser(features, { synchronized(entries) { entries.take(100) } })
        compose.onNodeWithText("删除").assertDoesNotExist()
        compose.onNodeWithContentDescription("全选当前目录").assertDoesNotExist()
        compose.onNodeWithText("全选").assertDoesNotExist()
        compose.onNodeWithText("搜索文件及子文件夹").assertDoesNotExist()
        compose.onNodeWithContentDescription("选择：视频1.mp4").performClick()
        compose.onNodeWithContentDescription("选择：视频2.mp4").performClick()
        compose.onNodeWithText("已选 2 项").assertExists()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "cloud-batch-management.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, writes.get())
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("确认删除").performClick()
        waitFor("已删除 2 项")
        compose.onNodeWithContentDescription("选择：视频1.mp4").assertDoesNotExist()
        assertEquals(1, writes.get())
        assertEquals(100, entries.size)
    }

    @Test
    fun destinationExcludesSelectedFolderAndMoveRefreshesSelection() {
        val source = mutableListOf(file("11", "影视", true), file("20", "目标", true))
        val destination = mutableListOf<JSONObject>()
        val writes = AtomicInteger()
        val features = features { path, parent, _, body ->
            when {
                path.endsWith("/sort") -> synchronized(source) { listing(if (parent == "20") destination else source) }
                path.endsWith("/move") -> {
                    assertEquals("20", body.getString("to_pdir_fid"))
                    assertEquals(1, body.getJSONArray("filelist").length())
                    assertEquals("11", body.getJSONArray("filelist").getString(0))
                    synchronized(source) {
                        destination.add(source.first { it.getString("fid") == "11" })
                        source.removeAll { it.getString("fid") == "11" }
                    }
                    writes.incrementAndGet()
                    """{"status":200,"code":0,"data":{}}"""
                }
                else -> error("意外请求：$path")
            }
        }
        showBrowser(features, { synchronized(source) { source.toList() } })
        compose.onNodeWithContentDescription("选择：影视").performClick()
        compose.onNodeWithText("移动").performClick()
        waitFor("移动到：根目录")
        compose.onNodeWithText("移动到此处").assertIsNotEnabled()
        compose.waitUntil(5000) { compose.onAllNodes(hasText("目标") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("影视").assertCountEquals(1)
        compose.onNode(hasText("目标") and hasAnyAncestor(isDialog())).performClick()
        waitFor("移动到：目标")
        waitFor("此目录没有可选的子文件夹")
        compose.onNodeWithText("移动到此处").performClick()
        waitFor("已移动 1 项")
        compose.onNodeWithContentDescription("选择：影视").assertDoesNotExist()
        compose.onNodeWithText("已选 1 项").assertDoesNotExist()
        assertEquals(1, writes.get())
    }

    private fun showBrowser(features: CloudProviderFeatures, listing: () -> List<JSONObject>, onOpen: () -> Unit = {}, directoryComplete: Boolean = false) {
        compose.setContent {
            var revision by remember { mutableIntStateOf(0) }
            val items = remember(revision) { listing().map { WebDavResource(it.getString("file_name"), it.getString("fid"), it.getBoolean("dir"), it.optLong("size")) } }
            NextPlayerTheme(preferences = ApplicationPreferences()) {
                CloudBrowserPanel(
                    items = items, breadcrumbs = listOf("根目录"), isLoading = false, isConfigured = true, error = null, isLoadingMore = false,
                    onItemClick = { onOpen() }, onBreadcrumbClick = {}, onRefresh = { revision++ }, onLoadMore = {}, breadcrumbLabel = { it }, loginContent = {}, onExitClick = {},
                    providerFeatures = features, currentFolderId = "0", providerName = "夸克网盘", isCurrentDirectoryComplete = directoryComplete,
                )
            }
        }
    }

    private fun file(id: String, name: String, directory: Boolean = false) = JSONObject().put("fid", id).put("file_name", name).put("dir", directory).put("size", 0)

    private fun listing(entries: List<JSONObject>) = JSONObject().put("status", 200).put("code", 0).put("data", JSONObject().put("list", JSONArray(entries))).toString()

    private fun features(handler: (String, String, Int, JSONObject) -> String): CloudProviderFeatures {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val text = Buffer().also { request.body?.writeTo(it) }.readUtf8()
            val body = if (text.isBlank()) JSONObject() else JSONObject(text)
            val response = handler(request.url.encodedPath, request.url.queryParameter("pdir_fid").orEmpty(), request.url.queryParameter("_page")?.toInt() ?: 1, body)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(response.toResponseBody()).build()
        }.build()
        return CloudProviderFeatures.from(QuarkApiClient(http).apply { setCookie("__uid=batch-fixture; __pus=fixture") })
    }

    private fun waitFor(text: String) {
        compose.waitUntil(15000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}
