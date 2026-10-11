package com.fluxplayer.app.feature.tingshu

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListeningControlsTest {
    private val compose = createAndroidComposeRule<ListeningControlsPreviewActivity>()

    @get:Rule
    val rules: org.junit.rules.TestRule = org.junit.rules.RuleChain.outerRule(
        org.junit.rules.TestRule { statement, _ ->
            object : org.junit.runners.model.Statement() {
                override fun evaluate() {
                    val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
                    val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
                    // 锁屏时不启动交互测试，避免后台页面启动被系统拦截而挂起。
                    org.junit.Assume.assumeFalse("界面测试需要设备解锁", keyguard.isKeyguardLocked)
                    statement.evaluate()
                }
            }
        },
    ).around(compose)

    private val activity get() = compose.activity

    @Test
    fun timerDraftNeedsConfirmationAndEpisodeModeIsVisible() {
        compose.onNodeWithText("定时").performClick()
        compose.onNodeWithText("按集数").performClick()
        compose.onNodeWithText("2集").performClick()
        compose.runOnIdle { assertEquals(0, activity.timer.remainingEpisodes) }
        compose.onNodeWithText("开始定时").performClick()
        compose.onNodeWithText("再听2集").assertIsDisplayed().performClick()
        compose.onNodeWithText("取消定时").performClick()
        compose.onNodeWithText("定时").performClick()
        compose.onNodeWithText("30分钟").performClick()
        compose.runOnIdle { assertEquals(1800, activity.timer.remainingSeconds) }
        compose.onNodeWithText("30:00").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.runOnIdle { assertEquals(1800, activity.timer.remainingSeconds) }
    }

    @Test
    fun skipDraftSyncsAfterBookChangeAndSpeedSupportsFineAdjustment() {
        compose.onNodeWithText("头30s/尾15s").performClick()
        compose.onNodeWithText("片头秒数（0–300）").performTextReplacement("38")
        compose.runOnIdle { assertEquals(30, activity.intro) }
        compose.onNodeWithText("保存设置").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(38, activity.intro)
            activity.bookPath = "controls-book-2"
            activity.intro = 80
            activity.outro = 20
        }
        compose.onNodeWithText("头80s/尾20s").performClick()
        compose.onNodeWithText("片头秒数（0–300）").assertTextContains("80")
        compose.onNodeWithText("片尾秒数（0–300）").assertTextContains("20")
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithText("1x").performClick()
        compose.onNodeWithText("1.25x").performClick()
        compose.onNodeWithText("＋").performClick()
        compose.runOnIdle { assertEquals(1.3f, activity.savedSpeed, 0.001f) }
    }

    @Test
    fun directoryHasTextRefreshSearchAndChapterLocation() {
        compose.onNodeWithText("目录").performClick()
        compose.onNodeWithText("更新目录").performClick()
        compose.runOnIdle { assertEquals(1, activity.refreshCount) }
        compose.onNodeWithText("搜索章节标题").performTextReplacement("章节 99")
        compose.onNodeWithText("找到 1 章").assertIsDisplayed()
        compose.onNodeWithText("输入章数（1–100）").performTextReplacement("100")
        compose.onNodeWithText("定位章节").performClick()
        compose.onNodeWithText("章节 100").assertIsDisplayed()
    }
}
