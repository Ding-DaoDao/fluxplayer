package com.fluxplayer.app.core.data.aliyun

import org.junit.Assert.assertEquals
import org.junit.Test

class AliyunDeviceSignatureTest {
    @Test
    fun recoverableSignatureMatchesIndependentRfc6979Vector() {
        val (signature, publicKey) = AliyunDeviceSignature.sign("1".padStart(64, '0'), "device-1", "user-1")
        assertEquals("fa15bd45e74826c1d6b124c32ea1ce51c5836e3efcfa8f2b02506d57f4562cd712903728132ee96c8e39ff890a1c513e23876823b2dc74948f9be3f5f3655dad00", signature)
        assertEquals("0479be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798483ada7726a3c4655da4fbfc0e1108a8fd17b448a68554199c47d08ffb10d4b8", publicKey)
    }

    @Test
    fun signatureSupportsOddRecoveryBit() {
        val (signature, _) = AliyunDeviceSignature.sign("3".padStart(64, '0'), "device-1", "user-1")
        assertEquals("6639fef70b22e16f52c8fe15e52389a13d2f13a259e9c81edf515c3ff7df23336b5931b1c2b0627d84c10cf6f8b173176af54ee9b75d75c82f08b5dc710e067e01", signature)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsZeroPrivateKey() {
        AliyunDeviceSignature.sign("0".repeat(64), "device", "user")
    }
}
