package com.fluxplayer.app.feature.videopicker.composables

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxplayer.app.core.data.cloud.CloudAccountStore
import com.fluxplayer.app.core.data.security.putSecret
import com.fluxplayer.app.core.model.ApplicationPreferences
import com.fluxplayer.app.core.ui.theme.NextPlayerTheme
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 在独立界面测试应用中验证账号入口与切换，使用样例账号。 */
class CloudAccountsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun prepare() {
        context.getSharedPreferences("cloud_accounts", Context.MODE_PRIVATE).edit().clear().commit()
    }
    private fun show(loggedIn: Boolean, changed: AtomicInteger = AtomicInteger()) {
        CloudAccountStore.preferences(context, "quark")
        compose.setContent {
            NextPlayerTheme(preferences = ApplicationPreferences()) {
                CloudBrowserPanel(
                    items = emptyList(), breadcrumbs = listOf("根目录"), isLoading = false, isConfigured = loggedIn,
                    error = null, isLoadingMore = false, onItemClick = {}, onBreadcrumbClick = {}, onRefresh = {},
                    onLoadMore = {}, breadcrumbLabel = { it }, loginContent = {}, cloudProviderKey = "quark",
                    onExitClick = {}, onAccountChanged = { changed.incrementAndGet() },
                )
            }
        }
    }

    @Test fun loggedOutAccountsEntryCanAddAccountAndTriggerSessionReload() {
        val changed = AtomicInteger()
        show(false, changed)
        compose.onNodeWithText("账号管理与恢复").performClick()
        compose.onNodeWithText("添加账号").performScrollTo().performClick()
        compose.onNodeWithText("名称").performTextInput("测试账号二")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { CloudAccountStore.accounts(context, "quark").size == 2 && changed.get() > 0 }
        compose.onNodeWithText("✓ 测试账号二").assertExists()
        assertEquals("测试账号二", CloudAccountStore.accounts(context, "quark").first { it.active }.name)
    }

    @Test fun loggedInMenuHasAccountManagementAndRecoveryControls() {
        show(true)
        compose.onNodeWithContentDescription("菜单").performClick()
        compose.onNodeWithText("账号管理与恢复").performClick()
        compose.onNodeWithText("备份与恢复").performClick()
        compose.onNodeWithText("设置 / 更换恢复密码").performScrollTo().assertExists()
        compose.onNodeWithText("导入密码备份").performScrollTo().assertExists()
        compose.onNodeWithText("导出密码备份").performScrollTo().assertExists()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.getExternalFilesDir(null), "cloud-account-management.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun passwordConfirmationPreventsMismatchedRecoveryPassword() {
        show(false)
        compose.onNodeWithText("账号管理与恢复").performClick()
        compose.onNodeWithText("备份与恢复").performClick()
        compose.onNodeWithText("设置 / 更换恢复密码").performScrollTo().performClick()
        compose.onNodeWithText("恢复密码").performTextInput("fixture-password")
        compose.onNodeWithText("确认密码").performTextInput("different-password")
        compose.onNodeWithText("保存").assertIsNotEnabled()
    }

    @Test fun accountDetailsAndUcTvMenuDoNotSwitchBrowserAccount() {
        CloudAccountStore.preferences(context, "uc").edit().putSecret("uc", "cookie", "__uid=fixture-first; __pus=fixture").commit()
        val selected = CloudAccountStore.add(context, "uc", "浏览账号")
        compose.setContent { NextPlayerTheme(preferences = ApplicationPreferences()) { CloudAccountsDialog("uc") {} } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("更多").fetchSemanticsNodes().size == 2 }
        compose.onAllNodesWithText("更多").onFirst().performClick()
        compose.onNodeWithText("TV 播放授权").assertExists()
        compose.onNodeWithText("复制登录信息").assertExists()
        compose.onNodeWithText("账号详情与登录信息").performClick()
        compose.onNodeWithText("复制全部登录信息").assertExists()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Cookie").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("••••••••").assertExists()
        assertEquals(selected, CloudAccountStore.accounts(context, "uc").single { it.active }.id)
    }
}
