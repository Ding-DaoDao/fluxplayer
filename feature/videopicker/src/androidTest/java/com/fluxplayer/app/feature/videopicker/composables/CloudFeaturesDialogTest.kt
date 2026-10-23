package com.fluxplayer.app.feature.videopicker.composables

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxplayer.app.core.data.cloud.CloudProviderFeatures
import com.fluxplayer.app.core.data.quark.QuarkApiClient
import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.core.ui.theme.NextPlayerTheme
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CloudFeaturesDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private fun features(searchVideo: Boolean = false, searchGate: java.util.concurrent.CountDownLatch? = null): CloudProviderFeatures {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val body = when {
                path.endsWith("/token") -> """{"status":200,"code":0,"data":{"stoken":"token","first_fid":"0"}}"""
                path.endsWith("/detail") -> if (chain.request().url.queryParameter("pdir_fid") == "folder") {
                    """{"status":200,"code":0,"data":{"list":[{"fid":"second","file_name":"第二集.mp4","size":820000000}]}}"""
                } else {
                    """{"status":200,"code":0,"data":{"list":[{"fid":"folder","file_name":"纪录片","dir":true},{"fid":"video","file_name":"第一集.mp4","size":1200000000}]}}"""
                }
                path.endsWith("/sort") && searchVideo && chain.request().url.queryParameter("pdir_fid") == "library" -> {
                    searchGate?.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    """{"status":200,"code":0,"data":{"list":[]}}"""
                }
                path.endsWith("/sort") && searchVideo -> """{"status":200,"code":0,"data":{"list":[{"fid":"video","file_name":"视频.mp4","size":100},{"fid":"library","file_name":"目录","dir":true}]}}"""
                path.endsWith("/sort") -> if (chain.request().url.queryParameter("pdir_fid") == "library") {
                    """{"status":200,"code":0,"data":{"list":[]}}"""
                } else {
                    """{"status":200,"code":0,"data":{"list":[{"fid":"library","file_name":"影库","dir":true}]}}"""
                }
                path.endsWith("/share") -> """{"status":200,"code":0,"data":{"task_id":"task"}}"""
                path.endsWith("/task") -> """{"status":200,"code":0,"data":{"status":2,"share_id":"share"}}"""
                path.endsWith("/password") -> """{"status":200,"code":0,"data":{"share_url":"https://pan.quark.cn/s/test-share","passcode":"1234"}}"""
                else -> error("测试不允许访问真实网盘")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        return CloudProviderFeatures.from(QuarkApiClient(http), "quark")
    }

    @Test
    fun selectionCreatesShareAndDisplaysResult() {
        val features = features()
        compose.setContent {
            NextPlayerTheme(darkTheme = false, mediaShell = true, preferences = ApplicationPreferences()) {
                CloudFeaturesDialog(
                    features,
                    "0",
                    listOf(
                        WebDavResource("旅行记录 · 山间的清晨.mp4", "file", false, 1_842_500_000),
                        WebDavResource("纪录片", "folder", true),
                        WebDavResource("电影原声带.flac", "audio", false, 382_000_000),
                        WebDavResource("观影清单.pdf", "document", false, 240_000),
                    ),
                    {},
                    { _, _ -> },
                    {},
                )
            }
        }
        compose.onNodeWithText("分享名称").assertDoesNotExist()
        compose.onNodeWithText("优先原画").assertDoesNotExist()
        compose.onNodeWithText("下一步（0）").assertIsNotEnabled()
        saveScreenshot("cloud-share-selection.png")
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onNodeWithText("下一步（1）").performClick()
        compose.onNodeWithText("分享名称").assertIsDisplayed()
        saveScreenshot("cloud-share-options.png")
        compose.onNode(androidx.compose.ui.test.hasText("创建分享") and androidx.compose.ui.test.hasClickAction()).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("复制分享链接")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("复制分享链接").assertExists()
        saveScreenshot("cloud-features-share.png")
    }

    @Test
    fun guestCanBrowseSharesButCannotMutateAccount() {
        val features = features()
        compose.setContent {
            NextPlayerTheme(darkTheme = false, mediaShell = true, preferences = ApplicationPreferences()) {
                CloudFeaturesDialog(features, "0", emptyList(), {}, { _, _ -> }, {}, isLoggedIn = false)
            }
        }
        compose.onNodeWithText("上传文件").assertDoesNotExist()
        compose.onNodeWithText("创建分享").assertDoesNotExist()
        compose.onNodeWithText("分享链接").assertIsDisplayed()
        compose.onNodeWithText("转存所选（0）").assertDoesNotExist()
        saveScreenshot("cloud-features-guest.png")
    }

    @Test
    fun playbackSettingsStaySeparateFromSharing() {
        val features = features()
        compose.setContent {
            NextPlayerTheme(darkTheme = false, mediaShell = true, preferences = ApplicationPreferences()) {
                CloudFeaturesDialog(features, "0", emptyList(), {}, { _, _ -> }, {}, action = CloudFeatureAction.Playback)
            }
        }
        compose.onNodeWithText("优先原画").assertIsDisplayed()
        compose.onNodeWithText("分享链接").assertDoesNotExist()
        compose.onNodeWithText("下一步（0）").assertDoesNotExist()
        saveScreenshot("cloud-playback-settings.png")
    }

    @Test
    fun sharedFilesUseOverflowActionsAndSeparateDestinationPicker() {
        val features = features()
        compose.setContent {
            NextPlayerTheme(darkTheme = true, mediaShell = true, preferences = ApplicationPreferences()) {
                CloudFeaturesDialog(features, "0", emptyList(), {}, { _, _ -> }, {}, action = CloudFeatureAction.Import)
            }
        }
        compose.onNodeWithText("分享链接").performTextInput("https://pan.quark.cn/s/example")
        compose.onNodeWithText("打开分享").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("第一集.mp4")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("直链播放").assertDoesNotExist()
        saveScreenshot("cloud-shared-files-dark.png")
        compose.onNodeWithContentDescription("第一集.mp4 的更多操作").performClick()
        compose.onNodeWithText("直链播放").assertDoesNotExist()
        compose.onNodeWithText("转存并播放").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        compose.onNodeWithText("纪录片").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("第二集.mp4")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("返回").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("第一集.mp4")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("更改").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("影库")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("影库").performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("此目录没有子文件夹")).fetchSemanticsNodes().isNotEmpty() }
        saveScreenshot("cloud-share-destination-dark.png")
        compose.onNodeWithText("保存到此目录").performClick()
        compose.onNodeWithText("根目录 / 影库").assertIsDisplayed()
    }

    @Test
    fun rootMenuOpensSearchAndPlaysWithoutResolvingTwice() {
        val gate = java.util.concurrent.CountDownLatch(1)
        try {
            val features = features(searchVideo = true, searchGate = gate)
            val played = AtomicReference<Uri?>()
            compose.setContent {
                NextPlayerTheme(darkTheme = false, mediaShell = true, preferences = ApplicationPreferences()) {
                    CloudBrowserPanel(
                        items = emptyList(), breadcrumbs = listOf("根目录"), isLoading = false, isConfigured = true,
                        error = null, isLoadingMore = false, onItemClick = {}, onBreadcrumbClick = {}, onRefresh = {}, onLoadMore = {},
                        breadcrumbLabel = { it }, loginContent = {}, providerName = "夸克网盘", onSortClick = {}, onExitClick = {},
                        providerFeatures = features, currentFolderId = "0", onPlayExtraFile = { uri, _ -> played.set(uri) },
                    )
                }
            }
            compose.onNodeWithText("搜索文件及子文件夹").assertDoesNotExist()
            compose.onNodeWithContentDescription("全选当前目录").assertDoesNotExist()
            compose.onNodeWithContentDescription("菜单").performClick()
            compose.onNodeWithText("搜索").performClick()
            compose.onNodeWithText("搜索文件及子文件夹").performTextInput("视频")
            compose.onNodeWithText("搜索").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("视频.mp4")).fetchSemanticsNodes().isNotEmpty() }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "cloud-root-inline-search.png").outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithContentDescription("视频.mp4 的更多操作").performClick()
            compose.onNodeWithText("播放").performClick()
            compose.waitUntil(3000) { played.get() != null }
            assertEquals("cloud", played.get()!!.scheme)
            assertEquals("0", played.get()!!.getQueryParameter("extra_parent"))
            compose.onNodeWithContentDescription("清除搜索").performClick()
            compose.onNodeWithText("返回目录").performClick()
            compose.onNodeWithText("搜索文件及子文件夹").assertDoesNotExist()
            compose.onNodeWithText("排序").assertExists()
        } finally {
            gate.countDown()
        }
    }

    private fun saveScreenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), name)
        output.outputStream().use { stream -> compose.onAllNodes(isDialog()).fetchSemanticsNodes().last().let { node -> compose.onNode(androidx.compose.ui.test.SemanticsMatcher("顶层弹窗") { it.id == node.id }).captureToImage() }.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, stream) }
    }
}
