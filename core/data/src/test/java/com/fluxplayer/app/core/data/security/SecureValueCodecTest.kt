package com.fluxplayer.app.core.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SecureValueCodec] 的行为约定。用假 cipher 跑，因为 Android Keystore 在 JVM 单测里不可用；
 * 这里要验的也正是「跟密钥无关」的那部分逻辑：旧明文迁移、以及**解密失败必须降级成 null
 * 而不是抛异常**（凭证读不出来应该显示登录页，而不是把 App 打崩）。
 */
class SecureValueCodecTest {

    private val failures = mutableListOf<String>()

    private fun codecWith(cipher: CredentialCipher) = SecureValueCodec(cipher) { message, _ -> failures += message }

    /** 把 purpose 编进密文，用来模拟 GCM 的 AAD 绑定：换字段就解不开。 */
    private class PurposeBoundCipher : CredentialCipher {
        override fun encrypt(plain: String, purpose: String): String = "$purpose|$plain".reversed()
        override fun decrypt(stored: String, purpose: String): String {
            val decoded = stored.reversed()
            require(decoded.startsWith("$purpose|")) { "purpose 不匹配" }
            return decoded.removePrefix("$purpose|")
        }
    }

    private class ThrowingCipher(private val error: Throwable) : CredentialCipher {
        override fun encrypt(plain: String, purpose: String): String = plain
        override fun decrypt(stored: String, purpose: String): String = throw error
    }

    @Test
    fun encryptedValueRoundTripsAndIsPrefixed() {
        val codec = codecWith(PurposeBoundCipher())
        val encoded = codec.encode("my-cookie", "quark.cookie")
        assertTrue(codec.isEncrypted(encoded))
        assertFalse(encoded.contains("my-cookie"))
        assertEquals("my-cookie", codec.decode(encoded, "quark.cookie"))
    }

    @Test
    fun legacyPlaintextIsStillReadableSoUpgradeNeedsNoReLogin() {
        val codec = codecWith(PurposeBoundCipher())
        assertFalse(codec.isEncrypted("__uid=abc; __puus=xyz"))
        assertEquals("__uid=abc; __puus=xyz", codec.decode("__uid=abc; __puus=xyz", "quark.cookie"))
    }

    @Test
    fun absentValueIsNull() {
        assertNull(codecWith(PurposeBoundCipher()).decode(null, "pan123.password"))
    }

    @Test
    fun ciphertextFromAnotherFieldFailsClosed() {
        val codec = codecWith(PurposeBoundCipher())
        val encoded = codec.encode("secret", "pan123.password")
        // 把 pan123.password 的密文贴到 quark.cookie 上：必须读不出来，而不是读出个错值
        assertNull(codec.decode(encoded, "quark.cookie"))
        assertEquals(1, failures.size)
    }

    @Test
    fun temporarilyUnavailableKeyReturnsNullAndKeepsData() {
        val codec = codecWith(ThrowingCipher(CredentialKeyException.Unavailable("设备未解锁")))
        assertNull(codec.decode(SecureValueCodec.PREFIX + "ciphertext", "pan123.token"))
        assertEquals(1, failures.size)
    }

    @Test
    fun permanentlyInvalidKeyReturnsNullWithoutCrashing() {
        val codec = codecWith(ThrowingCipher(CredentialKeyException.PermanentlyInvalid("改过锁屏密码")))
        assertNull(codec.decode(SecureValueCodec.PREFIX + "ciphertext", "pan123.token"))
        assertEquals(1, failures.size)
    }

    @Test
    fun corruptedCiphertextReturnsNullWithoutCrashing() {
        val codec = codecWith(ThrowingCipher(IllegalStateException("AEADBadTag")))
        assertNull(codec.decode(SecureValueCodec.PREFIX + "broken", "pan123.token"))
        assertEquals(1, failures.size)
    }

    @Test
    fun malformedEnvelopeIsTreatedAsUnreadable() {
        val codec = codecWith(PurposeBoundCipher())
        // 有前缀但内容被截断/乱码
        assertNull(codec.decode(SecureValueCodec.PREFIX + "!!!", "pan123.token"))
    }
}
