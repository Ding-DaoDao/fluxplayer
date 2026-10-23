package com.fluxplayer.app.feature.videopicker.composables

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxplayer.app.core.data.cloud.CloudProviderFeatures
import com.fluxplayer.app.core.data.cloud.CloudSearchHistory
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
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CloudSearchParityTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun currentFolderCategoryHistoryAndBrowsedCacheWorkTogether() {
        val requests = AtomicInteger()
        val client = QuarkApiClient(
            OkHttpClient.Builder().addInterceptor { _ ->
                requests.incrementAndGet()
                error("完整浏览目录不应再次请求网络")
            }.build(),
        ).apply { setCookie("__uid=search-parity-test") }
        val features = CloudProviderFeatures.from(client)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val history = CloudSearchHistory.from(context)
        val key = features.searchSessionKey() + ":personal"
        history.clear(key)
        try {
            compose.setContent {
                NextPlayerTheme(darkTheme = false, mediaShell = true, preferences = ApplicationPreferences()) {
                    CloudBrowserPanel(
                        items = listOf(WebDavResource("课件.mp4", "video", false), WebDavResource("课件.pdf", "document", false)),
                        breadcrumbs = listOf("根目录", "课程"), isLoading = false, isConfigured = true, error = null,
                        isLoadingMore = false, onItemClick = {}, onBreadcrumbClick = {}, onRefresh = {}, onLoadMore = {},
                        breadcrumbLabel = { it }, loginContent = {}, providerName = "夸克网盘", onSortClick = {}, onExitClick = {},
                        providerFeatures = features, currentFolderId = "10", isCurrentDirectoryComplete = true,
                    )
                }
            }
            compose.onNodeWithText("搜索文件及子文件夹").assertDoesNotExist()
            compose.onNodeWithContentDescription("菜单").performClick()
            compose.onNodeWithText("搜索").performClick()
            compose.onNodeWithText("搜索文件及子文件夹").performTextInput("课件")
            compose.onNodeWithText("当前文件夹").performClick()
            compose.onNodeWithText("搜索").performClick()
            waitFor("课件.pdf")
            compose.onNodeWithText("类型：全部").performClick()
            compose.onNodeWithText("文档").performClick()
            compose.waitForIdle()
            waitFor("课件.pdf")
            compose.onNodeWithText("课件.mp4").assertDoesNotExist()
            assertEquals(0, requests.get())
            File(context.getExternalFilesDir(null), "cloud-search-parity.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithContentDescription("清除搜索").performClick()
            waitFor("最近搜索")
            compose.onNodeWithContentDescription("搜索历史：课件").performClick()
            compose.waitUntil(5000) { compose.onAllNodes(hasContentDescription("清除搜索")).fetchSemanticsNodes().isNotEmpty() }
            waitFor("课件.pdf")
            compose.onNodeWithContentDescription("打开所在文件夹：课件.pdf").performClick()
            compose.onNodeWithText("返回").performClick()
            compose.onNodeWithContentDescription("清除搜索").performClick()
            waitFor("最近搜索")
            compose.onNodeWithContentDescription("删除搜索历史：课件").performClick()
            compose.waitUntil(5000) { history.read(key).isEmpty() }
        } finally {
            history.clear(key)
        }
    }

    @Test
    fun failedFolderDetailsAndRefreshRemainAvailable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = QuarkApiClient(
            OkHttpClient.Builder().addInterceptor { chain ->
                val response = if (chain.request().url.queryParameter("pdir_fid") == "0") {
                    """{"status":200,"code":0,"data":{"list":[{"fid":"broken","file_name":"损坏目录","dir":true},{"fid":"movie","file_name":"电影.mp4"}]}}"""
                } else {
                    """{"status":200,"code":1,"message":"目录暂不可读"}"""
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(response.toResponseBody()).build()
            }.build(),
        ).apply { setCookie("__uid=search-issues-test") }
        val features = CloudProviderFeatures.from(client)
        try {
            compose.setContent {
                NextPlayerTheme(darkTheme = true, mediaShell = true, preferences = ApplicationPreferences()) {
                    CloudSearchDialog(features, { _, _ -> }, {}, embedded = true, queryRequest = "电影")
                }
            }
            waitFor("查看失败详情（1）")
            compose.onNodeWithText("查看失败详情（1）").performClick()
            waitFor("未能读取的目录")
            compose.onNodeWithText("网盘 / 损坏目录").assertExists()
            compose.onNodeWithText("API error: 目录暂不可读").assertExists()
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithText("重新搜索").performClick()
            waitFor("查看失败详情（1）")
        } finally {
            CloudSearchHistory.from(context).clear(features.searchSessionKey() + ":personal")
        }
    }

    private fun waitFor(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }
    }
}
