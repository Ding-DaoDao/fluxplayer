package com.fluxplayer.app.core.data.security

import com.fluxplayer.app.core.common.CloudAccountIdentity
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSessionCookieJarTest {
    @Test fun lateResponseAfterLogoutCannotWriteCookies() {
        val provider = "fixture-cookie-provider"
        var writes = 0
        val delegate = object : CookieJar {
            override fun loadForRequest(url: HttpUrl) = emptyList<Cookie>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                writes++
            }
        }
        CloudAccountIdentity.setActive(provider, "first")
        val jar = CloudSessionCookieJar(provider, delegate)
        val url = "https://fixture.invalid".toHttpUrl()
        jar.loadForRequest(url)
        CloudSessionFence.advance(provider)
        jar.saveFromResponse(url, emptyList())
        assertEquals(0, writes)
        assertTrue(runCatching { jar.loadForRequest(url) }.isFailure)
    }

    @Test fun networkThreadKeepsOriginalAccountAfterBrowserSwitch() {
        val provider = "fixture-cookie-account"
        CloudAccountIdentity.setActive(provider, "first")
        var seen = ""
        val delegate = object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                seen = CloudAccountIdentity.active(provider)
                return emptyList()
            }
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                seen = CloudAccountIdentity.active(provider)
            }
        }
        val jar = CloudSessionCookieJar(provider, delegate)
        CloudAccountIdentity.setActive(provider, "second")
        val thread = Thread { jar.loadForRequest("https://fixture.invalid".toHttpUrl()) }
        thread.start()
        thread.join()
        assertEquals("first", seen)
        assertEquals("second", CloudAccountIdentity.active(provider))
        jar.saveFromResponse("https://fixture.invalid".toHttpUrl(), emptyList())
        assertEquals("first", seen)
    }
}
