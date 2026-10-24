package com.fluxplayer.app.core.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android Keystore 保护本机凭证；故障时保留原密钥与密文，由密码恢复流程显式修复。 */
internal class AndroidKeystoreCredentialCipher : CredentialCipher {

    @Volatile
    private var cachedKey: SecretKey? = null

    override fun encrypt(plain: String, purpose: String): String {
        val plainBytes = plain.toByteArray(Charsets.UTF_8)
        try {
            return withRetry {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, key())
                cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
                val ciphertext = cipher.doFinal(plainBytes)
                encode(cipher.iv) + IV_SEPARATOR + encode(ciphertext)
            }
        } finally {
            plainBytes.fill(0)
        }
    }

    override fun decrypt(stored: String, purpose: String): String {
        val separator = stored.indexOf(IV_SEPARATOR)
        if (separator <= 0) throw IllegalArgumentException("无法识别的凭证密文格式")
        val iv = decode(stored.substring(0, separator))
        val ciphertext = decode(stored.substring(separator + IV_SEPARATOR.length))
        return withRetry {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(purpose.toByteArray(Charsets.UTF_8))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        }
    }

    /** 密钥读取或解密失败时保留条目，交由恢复界面处理，不能自动删除。 */
    private inline fun <T> withRetry(block: () -> T): T = try {
        block()
    } catch (error: Throwable) {
        cachedKey = null
        if (error is android.security.keystore.KeyPermanentlyInvalidatedException || error is java.security.UnrecoverableKeyException) {
            throw CredentialKeyException.PermanentlyInvalid(describe(error), error)
        }
        if (isKeyProblem(error)) throw CredentialKeyException.Unavailable(describe(error), error)
        throw error
    }

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        return synchronized(this) {
            cachedKey ?: loadOrCreateKey().also { cachedKey = it }
        }
    }

    /** 仅在密码恢复校验成功且原文件已存档后显式调用。 */
    @Synchronized
    internal fun prepareForRecovery() {
        try {
            encrypt("recovery-check", "cloud_recovery.check")
        } catch (_: CredentialKeyException.PermanentlyInvalid) {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            store.deleteEntry(KEY_ALIAS)
            cachedKey = null
        }
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)

    companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "fluxplayer.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        private const val IV_SEPARATOR = ":"

        /**
         * 哪些异常算「本机密钥故障」。
         *
         * 只认**白名单**：不能见错就删键重建，误判的代价是**好密钥被删掉、全部凭证真的作废**。
         * **判断顺序有陷阱**：`AEADBadTagException` 是 `GeneralSecurityException` 的子类，
         * 必须先单独排除，否则「密文被改 / 跨版本残留」会被误认成密钥故障。
         */
        fun isKeyProblem(error: Throwable): Boolean {
            if (error is CredentialKeyException) return error is CredentialKeyException.PermanentlyInvalid
            // GCM 认证标签不匹配：密文坏了，换密钥也解不开 —— 必须先于 GeneralSecurityException
            if (error is AEADBadTagException) return false
            if (error is javax.crypto.NoSuchPaddingException) return false
            if (error is java.security.NoSuchAlgorithmException) return false
            if (error is android.security.KeyStoreException) return true
            if (error is java.security.ProviderException) return true
            if (error is java.security.GeneralSecurityException) return true
            val message = error.message.orEmpty()
            return message.contains("keystore", ignoreCase = true) ||
                message.contains("key blob", ignoreCase = true) ||
                message.contains("key not found", ignoreCase = true)
        }

        /** 把机型的原始报错压成一行，便于日后对日志。 */
        fun describe(error: Throwable): String {
            val type = error::class.java.simpleName.ifBlank { "Keystore 异常" }
            val detail = error.message.orEmpty().replace('\n', ' ').trim()
            return if (detail.isEmpty()) type else "$type: ${detail.take(160)}"
        }
    }
}
