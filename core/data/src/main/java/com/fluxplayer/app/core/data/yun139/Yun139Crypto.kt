package com.fluxplayer.app.core.data.yun139

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Yun139Crypto {
    private const val ALGORITHM = "AES/CBC/PKCS5Padding"

    fun decrypt(encryptedData: String, key: String, iv: String): String {
        val keyBytes = key.toByteArray(Charsets.UTF_8).copyOf(16)
        val ivBytes = iv.toByteArray(Charsets.UTF_8).copyOf(16)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        val decoded = Base64.decode(encryptedData, Base64.DEFAULT)
        return String(cipher.doFinal(decoded), Charsets.UTF_8)
    }

    fun encrypt(data: String, key: String, iv: String): String {
        val keyBytes = key.toByteArray(Charsets.UTF_8).copyOf(16)
        val ivBytes = iv.toByteArray(Charsets.UTF_8).copyOf(16)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        return Base64.encodeToString(cipher.doFinal(data.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
}
