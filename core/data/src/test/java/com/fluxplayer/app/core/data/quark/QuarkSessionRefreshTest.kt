package com.fluxplayer.app.core.data.quark

import com.fluxplayer.app.core.data.GlobalCookieJar
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarkSessionRefreshTest {
    @Test
    fun refreshRemovesStaleCookieAndAcceptsAllSetCookies() = runBlocking {
        var listRequests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val refresh = request.url.encodedPath.endsWith("/config")
            val body = if (refresh) {
                assertFalse(request.header("cookie").orEmpty().contains("__puus="))
                """{"status":200,"code":0,"data":{}}"""
            } else {
                listRequests++
                if (listRequests == 1) {
                    """{"status":401,"code":31001}"""
                } else {
                    assertTrue(request.header("cookie").orEmpty().contains("__puus=new"))
                    """{"status":200,"code":0,"data":{"list":[]}}"""
                }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).apply {
                if (refresh) addHeader("Set-Cookie", "__pus=renewed; Path=/").addHeader("Set-Cookie", "__puus=new; Path=/")
            }.build()
        }.build()
        val api = QuarkApiClient(http).apply { setCookie("__uid=user;__pus=old;__puus=stale") }
        assertTrue(api.listFiles().getOrThrow().isEmpty())
        assertEquals(2, listRequests)
        assertTrue(api.getCookie().contains("__pus=renewed"))
        assertFalse(api.getCookie().contains("Path="))
        api.clearCookie()
        assertEquals("", GlobalCookieJar.getQuarkCookie())
    }
}
