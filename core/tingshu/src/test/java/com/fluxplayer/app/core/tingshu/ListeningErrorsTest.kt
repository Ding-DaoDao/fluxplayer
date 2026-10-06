package com.fluxplayer.app.core.tingshu

import java.net.SocketTimeoutException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningErrorsTest {
    @Test
    fun wrappedTimeoutExplainsRetry() {
        val result = ListeningErrors.describe(IllegalStateException("解析失败", SocketTimeoutException("timeout")), "章节加载失败")
        assertTrue(result.startsWith("章节加载失败：请求超时"))
    }

    @Test
    fun forbiddenPlaybackExplainsPermissionsAndExpiredLink() {
        val result = ListeningErrors.describe(IllegalStateException("HTTP 403"), "音频播放失败", 403)
        assertTrue(result.contains("权限不足"))
        assertTrue(result.contains("链接已失效"))
    }

    @Test
    fun credentialsAndSignedAddressesAreRemovedFromDetails() {
        val result = ListeningErrors.describe(IllegalStateException("HTTP 401 token=secret-token\nhttps://example.com/?signature=secret-signature"), "播放地址解析失败")
        assertTrue(result.contains("凭证已失效"))
        assertFalse(result.contains("secret-token"))
        assertFalse(result.contains("secret-signature"))
    }

    @Test
    fun jsonCredentialsAndCookieValuesAreRemoved() {
        val result = ListeningErrors.sanitize("""{"token":"secret-one","password":"secret-two"} __puus=secret-three""")
        assertFalse(result.contains("secret-one"))
        assertFalse(result.contains("secret-two"))
        assertFalse(result.contains("secret-three"))
    }
}
