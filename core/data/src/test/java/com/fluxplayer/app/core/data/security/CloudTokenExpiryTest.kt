package com.fluxplayer.app.core.data.security

import okio.ByteString.Companion.encodeUtf8
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTokenExpiryTest {
    @Test fun jwtExpiryUsesMillisecondsAndAcceptsBearerPrefix() {
        val token = "header.${"{\"exp\":2000000000}".encodeUtf8().base64Url()}.signature"
        assertEquals(2_000_000_000_000, CloudTokenExpiry.fromToken("Bearer $token"))
    }

    @Test fun opaqueAndMalformedTokensDoNotPretendToBeExpired() {
        assertEquals(0, CloudTokenExpiry.fromToken("opaque-token"))
        assertEquals(0, CloudTokenExpiry.fromToken("a.***.c"))
        assertFalse(CloudTokenExpiry.needsRefresh(0, 100_000))
    }

    @Test fun responseLifetimeAndEarlyRefreshMatchWenxiWindow() {
        val expires = CloudTokenExpiry.fromResponse(JSONObject().put("expires_in", 3600), "opaque", 100_000)
        assertEquals(3_700_000, expires)
        assertTrue(CloudTokenExpiry.needsRefresh(expires, 3_640_000))
        assertFalse(CloudTokenExpiry.needsRefresh(expires, 3_639_999))
    }
}
