package com.fluxplayer.app.feature.tingshu

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListeningHomeLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<ListeningHomePreviewActivity>()

    @Test
    fun recentHistorySwitchesLayoutAfterScrolling() {
        repeat(20) {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("书 15"))
            compose.onNodeWithText("书 15").assertIsDisplayed()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("最近听过"))
            val previousWidth = compose.onNodeWithText("书 1").fetchSemanticsNode().boundsInRoot.width
            // 按钮使用无障碍描述，切换后须重新布局并能继续滚动到同一本书。
            val current = if (compose.onAllNodes(hasContentDescription("切换为列表")).fetchSemanticsNodes().isNotEmpty()) "切换为列表" else "切换为网格"
            compose.onNodeWithContentDescription(current).performClick()
            compose.waitForIdle()
            compose.onNodeWithContentDescription(if (current == "切换为列表") "切换为网格" else "切换为列表").assertIsDisplayed()
            val width = compose.onNodeWithText("书 1").fetchSemanticsNode().boundsInRoot.width
            assertTrue(if (current == "切换为列表") width > previousWidth * 1.5f else previousWidth > width * 1.5f)
        }
    }
}
