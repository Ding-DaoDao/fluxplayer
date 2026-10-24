package com.fluxplayer.app.core.data.backup

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupPasswordRequired : IllegalArgumentException("请输入此备份的密码")
class BackupPasswordInvalid : IllegalArgumentException("密码错误或备份文件已损坏")

/** 完整归档统一加密；旧版 ZIP 和 JSON 仍按原格式导入。 */
internal object PasswordProtectedBackup {
    private val magic = "FLUXBACKUP:1\n".toByteArray(Charsets.US_ASCII)
    private const val maxSize = 65 * 1024 * 1024
    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, 310_000, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
    fun encrypt(bytes: ByteArray, password: CharArray): ByteArray {
        require(password.size >= 8) { "请先设置至少 8 位的备份密码" }
        require(bytes.size <= maxSize) { "备份文件过大" }
        val random = SecureRandom()
        val salt = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val header = magic + salt + iv
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        cipher.updateAAD(header)
        return header + cipher.doFinal(bytes)
    }
    fun decrypt(bytes: ByteArray, password: CharArray?): ByteArray {
        require(bytes.size <= maxSize + 128) { "备份文件过大" }
        if (!bytes.take(magic.size).toByteArray().contentEquals(magic)) return bytes
        if (password == null || password.isEmpty()) throw BackupPasswordRequired()
        try {
            require(bytes.size >= magic.size + 32 + 12 + 16)
            val end = magic.size + 44
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, bytes.copyOfRange(magic.size, magic.size + 32)), GCMParameterSpec(128, bytes.copyOfRange(magic.size + 32, end)))
            cipher.updateAAD(bytes.copyOfRange(0, end))
            return cipher.doFinal(bytes, end, bytes.size - end)
        } catch (_: Exception) {
            throw BackupPasswordInvalid()
        }
    }
}
