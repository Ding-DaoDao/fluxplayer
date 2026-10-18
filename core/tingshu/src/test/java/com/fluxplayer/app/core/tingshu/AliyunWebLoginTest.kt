package com.fluxplayer.app.core.tingshu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AliyunWebLoginTest {
    @Test
    fun capturesOnlyOfficialDrivePageAfterExplicitAliyunLogin() {
        val login = "https://www.alipan.com/sign/in"
        assertTrue(AliyunWebLogin.canReadToken(login, "https://www.alipan.com/drive/file/all"))
        assertFalse(AliyunWebLogin.canReadToken(login, "https://www.alipan.com/sign/in"))
        assertFalse(AliyunWebLogin.canReadToken(login, "https://www.alipan.com/drive-fake"))
        assertFalse(AliyunWebLogin.canReadToken(login, "https://www.alipan.com.evil.example/drive"))
        assertFalse(AliyunWebLogin.canReadToken(login, "http://www.alipan.com/drive"))
        assertFalse(AliyunWebLogin.canReadToken(login, "https://www.alipan.com:8443/drive"))
        assertFalse(AliyunWebLogin.canReadToken("https://other.example/", "https://www.alipan.com/drive"))
    }

    @Test
    fun decodesJavascriptStringWithoutLosingEscapesAndRejectsEmptyToken() {
        val token = """{"access_token":"token-with-\\-and-\"","refresh_token":"refresh"}"""
        assertEquals(Json.parseToJsonElement(token).toString(), AliyunWebLogin.tokenFromJavascript(JsonPrimitive(token).toString()))
        assertNull(AliyunWebLogin.tokenFromJavascript("null"))
        assertNull(AliyunWebLogin.tokenFromJavascript("\"\""))
        assertNull(AliyunWebLogin.tokenFromJavascript("\"{}\""))
        assertNull(AliyunWebLogin.tokenFromJavascript("x".repeat(256 * 1024 + 1)))
    }

    @Test
    fun deviceHeadersMustBelongToTheCapturedAccount() {
        val token = """{"access_token":"current"}"""
        val wrong = Json.parseToJsonElement(AliyunWebLogin.tokenWithRequestHeaders(token, "Bearer previous", "device", "signature")).jsonObject
        assertFalse("device_id" in wrong)
        val correct = Json.parseToJsonElement(AliyunWebLogin.tokenWithRequestHeaders(token, "Bearer current", "device", "signature")).jsonObject
        assertEquals("device", correct["device_id"]?.jsonPrimitive?.content)
        assertEquals("signature", correct["signature"]?.jsonPrimitive?.content)
    }
}
