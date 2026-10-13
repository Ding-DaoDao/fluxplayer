package com.fluxplayer.app.core.data.backup

import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** 备份归档已加密，但调用方没有提供密码（需要向用户索取后重试）。 */
class BackupPassphraseRequiredException : Exception("该备份已加密，请输入备份密码")

/** 提供的密码不对，或密文被篡改／损坏。 */
class BackupDecryptException(
    message: String = "备份密码错误，或文件已损坏",
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * 备份归档的密码信封。
 *
 * 备份 ZIP 里装着 123 明文账号密码、各平台 Cookie 等凭证（见 [BackupManager]），
 * 直接落磁盘或传 WebDAV 等于把账号暴露出去，所以归档出厂即加密：
 *
 * - **KDF**：`PBKDF2WithHmacSHA1`，200,000 次迭代，随机 16 字节 salt。
 *   这里用 SHA1 而非 SHA256 是**被 minSdk 23 逼的**——`PBKDF2WithHmacSHA256`
 *   需要 API 26+，在 API 23~25 上 `SecretKeyFactory.getInstance` 会直接抛
 *   `NoSuchAlgorithmException`。PBKDF2-HMAC-SHA1 是标准算法，配合 20 万次迭代与
 *   随机 salt 仍然满足抗暴力破解要求。
 * - **加密**：AES-256-GCM，随机 12 字节 IV，128 位认证标签。
 * - **AAD**：魔数本身，防止信封头被替换拼接。
 *
 * 文件格式（全部定长，解析不需要额外长度字段）：
 * ```
 * [ MAGIC 8B ][ salt 16B ][ iv 12B ][ ciphertext + GCM tag ]
 * ```
 * 明文头部让 [isEncrypted] 只读前 8 字节就能判断，无需先解密。
 */
internal object BackupCrypto {

    /** 加密信封魔数：出现它即代表这是加密归档，而不是裸 ZIP（`PK`）或裸 JSON。 */
    private const val MAGIC_SIZE = 8
    private val MAGIC = byteArrayOf(
        'F'.code.toByte(), 'L'.code.toByte(), 'U'.code.toByte(), 'X'.code.toByte(),
        'B'.code.toByte(), 'A'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte(),
    ).also { check(it.size == MAGIC_SIZE) }

    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256

    /** 迭代次数：低端机上约 100~300ms，用户无感，但显著抬高离线爆破成本。 */
    private const val ITERATIONS = 200_000

    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val AES_ALGORITHM = "AES"

    private const val HEADER_BYTES = MAGIC_SIZE + SALT_BYTES + IV_BYTES

    /** 只读前 8 字节判断是否为加密归档（不触发任何解密）。 */
    fun isEncrypted(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER_BYTES) return false
        return MAGIC.indices.all { bytes[it] == MAGIC[it] }
    }

    /** 把明文载荷包成密码信封。 */
    fun encrypt(plain: ByteArray, passphrase: CharArray): ByteArray {
        if (passphrase.isEmpty()) throw BackupPassphraseRequiredException()
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val keyBytes = deriveKeyBytes(passphrase, salt)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, AES_ALGORITHM), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(MAGIC)
            val ciphertext = cipher.doFinal(plain)
            return ByteArrayOutputStream(HEADER_BYTES + ciphertext.size).use { out ->
                out.write(MAGIC)
                out.write(salt)
                out.write(iv)
                out.write(ciphertext)
                out.toByteArray()
            }
        } finally {
            keyBytes.fill(0)
        }
    }

    /**
     * 解开密码信封，返回明文载荷。
     *
     * @throws BackupPassphraseRequiredException 没有提供密码
     * @throws BackupDecryptException 密码不对，或密文被篡改／损坏
     */
    fun decrypt(envelope: ByteArray, passphrase: CharArray): ByteArray {
        if (!isEncrypted(envelope)) throw BackupDecryptException("不是加密的备份文件")
        if (passphrase.isEmpty()) throw BackupPassphraseRequiredException()
        val salt = envelope.copyOfRange(MAGIC_SIZE, MAGIC_SIZE + SALT_BYTES)
        val iv = envelope.copyOfRange(MAGIC_SIZE + SALT_BYTES, HEADER_BYTES)
        val ciphertext = envelope.copyOfRange(HEADER_BYTES, envelope.size)
        val keyBytes = deriveKeyBytes(passphrase, salt)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, AES_ALGORITHM), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(MAGIC)
            return cipher.doFinal(ciphertext)
        } catch (error: AEADBadTagException) {
            // GCM 认证标签不匹配：密码错误，或密文被改过
            throw BackupDecryptException(cause = error)
        } catch (error: GeneralSecurityException) {
            throw BackupDecryptException(cause = error)
        } finally {
            keyBytes.fill(0)
        }
    }

    /** PBKDF2 派生 256 位 AES 密钥原始字节；`PBEKeySpec` 用完立即清掉口令副本。 */
    private fun deriveKeyBytes(passphrase: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
