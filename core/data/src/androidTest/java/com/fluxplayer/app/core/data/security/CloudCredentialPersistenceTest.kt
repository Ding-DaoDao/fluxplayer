package com.fluxplayer.app.core.data.security

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxplayer.app.core.data.cloud189.C189AuthProvider
import com.fluxplayer.app.core.data.quark.QuarkApiClient
import com.fluxplayer.app.core.data.yun139.Yun139ApiClient
import com.fluxplayer.app.core.data.yun139.Yun139AuthProvider
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 只在独立测试应用内保存样例凭证，验证真实 Keystore 和进程内并发写回。 */
class CloudCredentialPersistenceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun prefs(provider: String) = context.getSharedPreferences(provider, Context.MODE_PRIVATE)
    private val oldCookie = "__uid=fixture; __pus=old; __puus=session"

    @Before fun prepare() {
        C189AuthProvider.initialize(context)
        C189AuthProvider.logout()
        listOf("quark", "uc", "yun139").forEach {
            CloudSessionFence.advance(it)
            prefs(it).edit().clear().commit()
        }
        Yun139AuthProvider.clear()
    }

    @After fun clear() = prepare()

    private fun http(handler: (Request) -> Pair<String, String?>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (body, cookie) = handler(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .apply { if (cookie != null) addHeader("Set-Cookie", cookie) }.body(body.toResponseBody()).build()
    }.build()

    private fun client(http: OkHttpClient, provider: String = "quark") = QuarkApiClient(http, provider).apply {
        configureSession(context)
        setCookie(prefs(provider).getSecret(provider, "cookie").orEmpty())
    }

    private fun saveCookie(provider: String = "quark", cookie: String = oldCookie, refreshed: Long = System.currentTimeMillis()) {
        prefs(provider).edit().putSecret(provider, "cookie", cookie).putLong("cookie_refreshed_at", refreshed).commit()
    }

    @Test fun tianyiLegacyPlaintextMigratesAndRestoresWithoutLosingSession() {
        val store = prefs("cloud189")
        store.edit().putString("accessToken", "fixture-access").putString("sessionKey", "fixture-key")
            .putString("sessionSecret", "fixture-secret").putString("refreshToken", "fixture-refresh").putString("cookies", "fixture=value").commit()
        com.fluxplayer.app.core.data.GlobalCookieJar.setCookie("cloud.189.cn", "old_account_cookie", "fixture-old")
        C189AuthProvider.restoreSavedSession()
        assertTrue(C189AuthProvider.hasValidSession())
        assertFalse(com.fluxplayer.app.core.data.cloud189.C189ApiClient().saveCookies().contains("old_account_cookie"))
        listOf("accessToken", "sessionKey", "sessionSecret", "refreshToken", "cookies").forEach {
            assertTrue(store.getString(it, "")!!.startsWith("enc:v1:"))
        }
        C189AuthProvider.clear()
        C189AuthProvider.restoreSavedSession()
        assertEquals("fixture-access", C189AuthProvider.accessToken)
        assertEquals("fixture-secret", C189AuthProvider.sessionSecret)
    }

    @Test fun quarkLateResponseCannotOverwriteNewLogin() = runBlocking {
        saveCookie()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val api = client(
            http {
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                """{"status":200,"code":0,"data":{"list":[]}}""" to "__pus=late"
            },
        )
        try {
            val pending = async(Dispatchers.IO) { api.listFiles() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            CloudSessionFence.advance("quark")
            saveCookie(cookie = "__uid=new; __pus=new-session")
            release.countDown()
            assertTrue(pending.await().isFailure)
            assertEquals("__uid=new; __pus=new-session", prefs("quark").getSecret("quark", "cookie"))
        } finally {
            release.countDown()
        }
    }

    @Test fun concurrentOlderCookieResponseDoesNotUndoSuccessfulRotation() = runBlocking {
        saveCookie()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transport = http { request ->
            if (request.url.queryParameter("pdir_fid") == "slow") {
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            """{"status":200,"code":0,"data":{"list":[]}}""" to if (request.url.queryParameter("pdir_fid") == "slow") "__pus=older" else "__pus=newer"
        }
        val first = client(transport)
        val second = client(transport)
        try {
            val pending = async(Dispatchers.IO) { first.listFiles("slow") }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            second.listFiles("fast").getOrThrow()
            release.countDown()
            pending.await().getOrThrow()
            val stored = prefs("quark").getSecret("quark", "cookie").orEmpty()
            assertTrue(stored.contains("__pus=newer"))
            assertFalse(stored.contains("older"))
        } finally {
            release.countDown()
        }
    }

    @Test fun ucCookieRefreshIsPersistedAndNotRepeatedBeforeNinetyMinutes() = runBlocking {
        saveCookie("uc", refreshed = 0)
        val refreshes = AtomicInteger()
        val api = client(
            http { request ->
                if (request.url.encodedPath.endsWith("/config")) {
                    refreshes.incrementAndGet()
                    """{"status":200,"code":0,"data":{}}""" to "__puus=renewed"
                } else {
                    """{"status":200,"code":0,"data":{"list":[]}}""" to null
                }
            },
            "uc",
        )
        api.listFiles().getOrThrow()
        api.listFiles().getOrThrow()
        assertEquals(1, refreshes.get())
        assertTrue(prefs("uc").getSecret("uc", "cookie")!!.contains("__puus=renewed"))
        assertTrue(prefs("uc").getLong("cookie_refreshed_at", 0) > 0)
    }

    @Test fun mobileRefreshWritesRotatedTokenBeforeReturning() = runBlocking {
        prefs("yun139").edit().putSecret("yun139", "authorization", "Basic old").commit()
        val api = Yun139ApiClient(http { """{"success":true,"data":{"token":"renewed"}}""" to null }).apply {
            configureSession(context)
            setToken("Basic old", "13800000000", "domain")
        }
        api.refreshToken().getOrThrow()
        val stored = prefs("yun139").getSecret("yun139", "authorization")
        assertEquals(Yun139AuthProvider.authorization, stored)
        assertFalse(stored == "Basic old")
        assertTrue(prefs("yun139").getLong("lastRefresh", 0) > 0)
    }

    @Test fun mobileLogoutDuringRefreshCannotRestoreCredentials() = runBlocking {
        prefs("yun139").edit().putSecret("yun139", "authorization", "Basic old").commit()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val api = Yun139ApiClient(
            http {
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                """{"success":true,"data":{"token":"late"}}""" to null
            },
        ).apply {
            configureSession(context)
            setToken("Basic old", "13800000000", "domain")
        }
        try {
            val pending = async(Dispatchers.IO) { api.refreshToken() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            api.logout()
            prefs("yun139").edit().clear().commit()
            release.countDown()
            assertTrue(pending.await().isFailure)
            assertEquals("", Yun139AuthProvider.authorization)
            assertFalse(prefs("yun139").contains("authorization"))
        } finally {
            release.countDown()
        }
    }
}
