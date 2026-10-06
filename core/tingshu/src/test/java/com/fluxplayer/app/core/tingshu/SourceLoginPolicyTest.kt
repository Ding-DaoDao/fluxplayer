package com.fluxplayer.app.core.tingshu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import voice.core.extension.engine.SourceLogin
import voice.core.extension.engine.SourceSetting

class SourceLoginPolicyTest {
    @Test
    fun logoutRemovesCredentialsButRetainsDirectoryAndPreferences() {
        val fields = listOf(
            SourceSetting("passport", "账号"),
            SourceSetting("password", "密码", "password", default = "old-password"),
            SourceSetting("cookie", "Cookie"),
            SourceSetting("authorization", "认证"),
            SourceSetting("access_token", "凭证"),
            SourceSetting("root", "听书目录", "directory"),
            SourceSetting("timeout", "超时"),
        )
        val values = fields.associate { it.key to "saved" } + ("root" to "books") + ("hiddenSession" to "old-session")
        val cleared = settingsAfterLogout(fields, values)
        listOf("passport", "password", "cookie", "authorization", "access_token").forEach { assertEquals("", cleared[it]) }
        assertEquals("books", cleared["root"])
        assertEquals("saved", cleared["timeout"])
        assertTrue("未声明的内部登录状态也应清除", "hiddenSession" !in cleared)
    }

    @Test
    fun loggedInSourcesStillOfferWebLogin() {
        listOf("quark", "yun139", "cloud189").forEach { id ->
            val login = netdiskWebLogin(id, SourceLogin(authenticated = true))
            assertTrue(login.webUrl.startsWith("https://"))
            assertTrue(login.cookieUrl.startsWith("https://"))
            assertEquals(true, login.authenticated)
        }
    }

    @Test
    fun passwordOnlySourceDoesNotGainUnsupportedWebLogin() {
        assertEquals("", netdiskWebLogin("pan123", SourceLogin()).webUrl)
    }
}
