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

/**
 * 基于 Android Keystore 的凭证加解密：AES-256-GCM，密钥不可导出。
 *
 * 这里的密钥绑定设备，用于加密
 * **落盘的登录凭证**（Cookie / access token / 账号密码）。落盘格式见 [SecureValueCodec]。
 *
 * 三个必须守住的点：
 *
 * 1. **绝不加 `setUserAuthenticationRequired(true)`**：那会让密钥在用户改锁屏密码时被系统
 *    **必然**作废（`KeyPermanentlyInvalidatedException`），等于把老用户的账号全废掉。
 * 2. **条目坏了要自愈**：删掉坏条目 → 生成新密钥 → 原样重跑一次。否则「每一次」读写都失败。
 * 3. **删条目之前先确认 Keystore 可达**：如果连 `KeyStore.load` 都进不去（设备还没解锁），
 *    那只是**暂时**读不到，此时删键会把用户本来还能解开的凭证全部作废。这种抛
 *    [CredentialKeyException.Unavailable]，让调用方原样失败、下次再试。
 *
 * 本类**不抛裸的 java.security 异常**给调用方——上游常见的崩溃就是主线程被
 * `InvalidKeyException` / `KeystoreException` 击穿。失败一律转成 [CredentialKeyException]。
 */
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

    /**
     * 跑一次加解密；遇到「条目坏了」先删掉坏条目，再原样重跑一次。
     *
     * 重试必须落在**整段流程**外面，而不是只重试 `key()`：部分机型把「操作初始化失败」推迟到
     * `doFinal` 才报，只重试取密钥接不住。
     */
    private inline fun <T> withRetry(block: () -> T): T = try {
        block()
    } catch (first: Throwable) {
        if (!isKeyProblem(first)) throw first
        discardStaleEntry()
        try {
            block()
        } catch (second: Throwable) {
            throw if (isKeyProblem(second)) PermanentlyInvalid(second) else second
        }
    }

    /** 取密钥：缓存 → Keystore 已有 → 新建。失败一律转成 [CredentialKeyException]。 */
    private fun key(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val loaded = try {
                loadOrCreateKey()
            } catch (first: Throwable) {
                if (!isKeyProblem(first)) throw first
                discardStaleEntry()
                try {
                    loadOrCreateKey()
                } catch (second: Throwable) {
                    throw if (isKeyProblem(second)) PermanentlyInvalid(second) else second
                }
            }
            cachedKey = loaded
            return loaded
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

    /**
     * 删掉不可用的 Keystore 条目。
     *
     * 删之前先确认 Keystore **可达**：进不去就说明只是暂时读不到，抛 [CredentialKeyException.Unavailable]
     * 让调用方原样失败，而不是把用户凭证毁掉。
     */
    private fun discardStaleEntry() {
        val keyStore = try {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }
        } catch (error: Throwable) {
            throw CredentialKeyException.Unavailable(describe(error), error)
        }
        cachedKey = null
        runCatching { keyStore.deleteEntry(KEY_ALIAS) }
    }

    private fun PermanentlyInvalid(error: Throwable) =
        CredentialKeyException.PermanentlyInvalid(describe(error), error)

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
