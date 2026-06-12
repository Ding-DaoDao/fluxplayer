package com.fluxplayer.app.core.data.cloud189

import android.util.Base64
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

object RsaHelper {
    fun encryptToRsaHex(plainText: String, publicKeyBase64: String): String {
        val keyBytes = Base64.decode(publicKeyBase64, Base64.DEFAULT)
        val keySpec = X509EncodedKeySpec(keyBytes)
        val key = KeyFactory.getInstance("RSA").generatePublic(keySpec)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return encrypted.joinToString("") { "%02x".format(it) }
    }
}
