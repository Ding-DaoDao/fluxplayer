package com.fluxplayer.app.core.data.pan123

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Pan123WebSignatureTest {
    @Test
    fun parsesCommonShareDomainsAndRejectsLookalikeHosts() {
        val api = Pan123ApiClient()
        assertEquals("abc-123" to "pass", api.parseShareUrl("https://www.123pan.com/s/abc-123 提取码：pass"))
        assertEquals("abc" to "code", api.parseShareUrl("https://123865.com/s/abc?pwd=code"))
        assertNull(api.parseShareUrl("https://evil123865.com/s/abc"))
        assertNull(api.parseShareUrl("https://123865.com.evil.test/s/abc"))
    }

    @Test
    fun knownWebSignaturesIncludeTimeBoundaryAndUnpaddedCrc() {
        assertEquals("cbf43926", Pan123WebSignature.crc32("123456789"))
        assertEquals("f2ac3dd" to "1700000000-123456-d49a505", Pan123WebSignature.sign("/b/api/user/info", 1700000000, 123456))
        assertEquals("6b61bf1b" to "0-0-3d983037", Pan123WebSignature.sign("/b/api/share/download/info", 0, 0))
        assertEquals("dd96a6bf" to "1798734600-9999999-212b35d7", Pan123WebSignature.sign("/api/file/download_info", 1798734600, 9999999))
    }
}
