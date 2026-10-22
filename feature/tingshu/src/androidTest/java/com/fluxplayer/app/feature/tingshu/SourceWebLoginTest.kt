package com.fluxplayer.app.feature.tingshu

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * 两条入口的「打开网页登录」都必须真的把登录请求消费掉。
 *
 * 回归点：`pendingLogin` 只有配置面板里的 `WebLoginHost` 会清零。宿主原来只挂在
 * 「设置 → 听书配置」页面上，听书首页长按书源复用同一个面板时没有宿主，点了
 * 只会写状态、不启动 SourceLoginActivity，表现就是点了没反应。
 */
@RunWith(AndroidJUnit4::class)
class PanelEntryWebLoginTest {
    private val compose = createAndroidComposeRule<SourceWebLoginPanelPreviewActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(quarkRule()).around(compose)

    @Test
    fun webLoginClickIsConsumed() {
        compose.waitUntil(20_000) {
            WebLoginTestSupport.hasNode(compose.onAllNodesWithText("打开网页登录"))
        }
        compose.onNodeWithText("打开网页登录").performClick()
        assertNull(
            "听书首页这条入口点击后 pendingLogin 应该被 WebLoginHost 消费，仍为非空说明没有宿主去启动登录页",
            awaitConsumed(compose, compose.activity.viewModel),
        )
    }
}

/** 设置 → 听书配置里的同一条链路：先点书源，再点「打开网页登录」。 */
@RunWith(AndroidJUnit4::class)
class SettingsEntryWebLoginTest {
    private val compose = createAndroidComposeRule<SourceWebLoginSettingsPreviewActivity>()

    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(quarkRule()).around(compose)

    @Test
    fun webLoginClickIsConsumed() {
        compose.waitUntil(20_000) {
            WebLoginTestSupport.hasNode(compose.onAllNodesWithText(WebLoginTestSupport.QUARK_NAME))
        }
        compose.onNodeWithText(WebLoginTestSupport.QUARK_NAME).performClick()
        compose.waitUntil(20_000) {
            WebLoginTestSupport.hasNode(compose.onAllNodesWithText("打开网页登录"))
        }
        compose.onNodeWithText("打开网页登录").performClick()
        assertNull(
            "设置页这条入口点击后 pendingLogin 应该被 WebLoginHost 消费，仍为非空说明没有宿主去启动登录页",
            awaitConsumed(compose, compose.activity.viewModel),
        )
    }
}

private fun quarkRule(): TestRule = TestRule { base: Statement, _ ->
    object : Statement() {
        override fun evaluate() {
            WebLoginTestSupport.importQuark()
            try {
                base.evaluate()
            } finally {
                WebLoginTestSupport.removeQuark()
            }
        }
    }
}

/**
 * 等 `pendingLogin` 被消费：只要在 3 秒内变空就算通过，返回 null。
 * 一直非空说明没有任何组件启动登录页（就是这次的 bug），返回残留值供断言报错用。
 */
private fun awaitConsumed(
    compose: androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>,
    viewModel: TingshuViewModel,
): PendingLogin? {
    val deadline = System.currentTimeMillis() + 3_000
    while (System.currentTimeMillis() < deadline) {
        compose.waitForIdle()
        if (viewModel.state.value.pendingLogin == null) return null
        Thread.sleep(50)
    }
    return viewModel.state.value.pendingLogin
}
