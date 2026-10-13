package com.fluxplayer.app.core.data.cloud189

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class C189AuthProviderTest {
    @Before
    fun prepare() {
        C189AuthProvider.clear()
    }

    @After
    fun cleanup() {
        C189AuthProvider.clear()
    }

    @Test
    fun pendingRefreshCannotRestoreLoggedOutAccount() {
        C189AuthProvider.setTokens("old", "key", "secret")
        val pending = C189AuthProvider.snapshot()
        C189AuthProvider.logout()
        assertFalse(C189AuthProvider.replaceIfCurrent(pending, pending.copy(accessToken = "new")))
        assertFalse(C189AuthProvider.isActive)
        assertEquals("", C189AuthProvider.accessToken)
    }

    @Test
    fun pendingRefreshCannotOverwriteAnotherAccount() {
        C189AuthProvider.setTokens("first", "key", "secret")
        val pending = C189AuthProvider.snapshot()
        C189AuthProvider.setTokens("second", "other-key", "other-secret")
        assertFalse(C189AuthProvider.replaceIfCurrent(pending, pending.copy(accessToken = "refreshed-first")))
        assertEquals("second", C189AuthProvider.accessToken)
    }

    @Test
    fun rotatedTokenSurvivesWhileSessionIsBeingRecovered() {
        C189AuthProvider.setTokens("old", "key", "secret", "old-refresh")
        val pending = C189AuthProvider.snapshot()
        assertTrue(
            C189AuthProvider.replaceIfCurrent(
                pending,
                pending.copy(accessToken = "new", refreshToken = "new-refresh", sessionKey = "", sessionSecret = ""),
            ),
        )
        assertEquals("new-refresh", C189AuthProvider.refreshToken)
        assertFalse(C189AuthProvider.hasValidSession())
        val current = C189AuthProvider.snapshot()
        assertTrue(C189AuthProvider.replaceIfCurrent(current, current.copy(sessionKey = "new-key", sessionSecret = "new-secret")))
        assertTrue(C189AuthProvider.hasValidSession())
    }

    @Test
    fun missingSecretDoesNotCountAsValidSession() {
        C189AuthProvider.setTokens("token", "key", "")
        assertFalse(C189AuthProvider.hasValidSession())
    }

    @Test
    fun networkAndFileErrorsAreNotAuthenticationErrors() {
        assertFalse(C189SessionErrors.isAuthenticationError("timeout"))
        assertFalse(C189SessionErrors.isAuthenticationError("HTTP 503"))
        assertFalse(C189SessionErrors.isAuthenticationError("FileNotFound"))
        assertTrue(C189SessionErrors.isAuthenticationError("InvalidSessionKey"))
        assertTrue(C189SessionErrors.isAuthenticationError("UserInvalidOpenToken"))
        assertTrue(C189SessionErrors.isAuthenticationError("userSessionBO is null"))
    }

    @Test
    fun http200AuthenticationFailureStillRequiresRecovery() {
        val error = C189SessionErrors.classify(200, "InvalidAccessToken", "")
        assertTrue(error?.authentication == true)
        assertEquals("InvalidAccessToken", error?.code)
    }

    @Test
    fun http200BusinessFailureIsNotAnEmptySuccessfulDirectory() {
        val error = C189SessionErrors.classify(200, "FileNotFound", "文件不存在")
        assertTrue(error != null)
        assertFalse(error!!.authentication)
        assertTrue(C189SessionErrors.classify(200, null, "操作失败", false) != null)
        assertNull(C189SessionErrors.classify(200, null, ""))
    }
    @Test
    fun emptyCookieSigningKeyProducesStandardHmac() {
        val method = C189ApiClient::class.java.getDeclaredMethod("hmacSha1", ByteArray::class.java, String::class.java)
        method.isAccessible = true
        val signature = method.invoke(C189ApiClient(okhttp3.OkHttpClient()), ByteArray(0), "example")
        assertEquals("87ee879326b04040398a1a38e10f490f3e1637a3", signature)
    }

}
