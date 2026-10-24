package com.fluxplayer.app.core.data.cloud

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import com.fluxplayer.app.core.data.security.CredentialCrypto
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/** 密码保护的可迁移备份，以及随凭证更新的本地恢复快照。 */
object CloudAccountRecovery {
    private val executor = Executors.newSingleThreadExecutor()
    private val queued = AtomicBoolean(false)
    private val listeners = java.util.concurrent.ConcurrentHashMap<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>()
    private const val MAX_BYTES = 8 * 1024 * 1024
    private fun file(context: Context, name: String) = AtomicFile(File(context.noBackupFilesDir, name))
    private fun bundle(context: Context) = file(context, "cloud-accounts-recovery.json")
    private fun readBundle(context: Context): JSONObject {
        val snapshot = bundle(context)
        require(listOf(snapshot.baseFile, File(snapshot.baseFile.path + ".bak")).all { !it.exists() || it.length() <= MAX_BYTES }) { "本地恢复文件过大" }
        return JSONObject(snapshot.readFully().toString(Charsets.UTF_8))
    }
    private fun random(size: Int) = ByteArray(size).also { SecureRandom().nextBytes(it) }
    private fun encode(bytes: ByteArray) = bytes.toByteString().base64()
    private fun decode(value: String) = value.decodeBase64()?.toByteArray() ?: error("恢复数据格式无效")

    private fun seal(data: ByteArray, key: ByteArray): String {
        val iv = random(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("FluxPlayer.cloud-accounts.v1".toByteArray())
        return JSONObject().put("iv", encode(iv)).put("data", encode(cipher.doFinal(data))).toString()
    }
    private fun open(data: String, key: ByteArray): ByteArray {
        require(data.length <= MAX_BYTES) { "恢复文件过大" }
        val json = JSONObject(data)
        val iv = decode(json.getString("iv"))
        require(iv.size == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("FluxPlayer.cloud-accounts.v1".toByteArray())
        return cipher.doFinal(decode(json.getString("data")))
    }
    private fun passwordKey(password: CharArray, salt: ByteArray): ByteArray {
        require(password.size >= 8) { "恢复密码至少需要 8 位" }
        require(salt.size == 32)
        val spec = PBEKeySpec(password, salt, 310_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    @Synchronized fun export(context: Context, password: CharArray): ByteArray {
        val plain = CloudAccountStore.exportData(context).toByteArray()
        val salt = random(32)
        val key = passwordKey(password, salt)
        return try {
            JSONObject().put("format", "fluxplayer-accounts-v1").put("salt", encode(salt)).put("payload", seal(plain, key)).toString().toByteArray()
                .also { require(it.size <= MAX_BYTES) { "账号备份过大，请减少账号配置后重试" } }
        } finally {
            plain.fill(0)
            key.fill(0)
        }
    }

    @Synchronized fun restore(context: Context, data: ByteArray, password: CharArray) {
        require(data.size <= MAX_BYTES) { "恢复文件过大" }
        val envelope = JSONObject(data.toString(Charsets.UTF_8))
        require(envelope.getString("format") == "fluxplayer-accounts-v1") { "不是 FluxPlayer 账号备份" }
        val key = passwordKey(password, decode(envelope.getString("salt")))
        val plain = try {
            open(envelope.getString("payload"), key)
        } catch (error: Exception) {
            throw IllegalArgumentException("密码错误或备份已损坏，现有账号未修改", error)
        } finally {
            key.fill(0)
        }
        try {
            prepareRecovery(context, plain)
            CloudAccountStore.importData(context, plain.toString(Charsets.UTF_8))
        } finally {
            plain.fill(0)
        }
        enable(context, password)
    }

    @Synchronized fun enable(context: Context, password: CharArray) {
        val plain = CloudAccountStore.exportData(context).toByteArray()
        val key = random(32)
        val salt = random(32)
        val wrapping = passwordKey(password, salt)
        try {
            val envelope = JSONObject().put("salt", encode(salt)).put("wrapped", seal(key, wrapping))
                .put("local", CredentialCrypto.encode(encode(key), "cloud_recovery.key"))
                .put("payload", seal(plain, key))
            write(bundle(context), envelope.toString().toByteArray())
        } finally {
            key.fill(0)
            wrapping.fill(0)
            plain.fill(0)
        }
    }
    fun available(context: Context): Boolean = bundle(context).let { it.baseFile.exists() || File(it.baseFile.path + ".bak").exists() }

    @Synchronized fun unlock(context: Context, password: CharArray) {
        val envelope = readBundle(context)
        val wrapping = passwordKey(password, decode(envelope.getString("salt")))
        val key = try {
            open(envelope.getString("wrapped"), wrapping)
        } catch (error: Exception) {
            throw IllegalArgumentException("恢复密码错误或恢复密钥已损坏，原数据未修改", error)
        } finally {
            wrapping.fill(0)
        }
        try {
            val plain = open(envelope.getString("payload"), key)
            try {
                prepareRecovery(context, plain)
                CloudAccountStore.importData(context, plain.toString(Charsets.UTF_8))
            } finally {
                plain.fill(0)
            }
            envelope.put("local", CredentialCrypto.encode(encode(key), "cloud_recovery.key"))
            write(bundle(context), envelope.toString().toByteArray())
        } finally {
            key.fill(0)
        }
    }
    internal fun watch(context: Context, prefs: SharedPreferences) {
        listeners.computeIfAbsent(prefs) {
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> schedule(context) }.also { prefs.registerOnSharedPreferenceChangeListener(it) }
        }
    }
    internal fun schedule(context: Context) {
        if (!available(context) || !queued.compareAndSet(false, true)) return
        executor.execute {
            queued.set(false)
            synchronized(this) {
                runCatching {
                    val envelope = readBundle(context)
                    val stored = CredentialCrypto.decode(envelope.getString("local"), "cloud_recovery.key") ?: return@runCatching
                    val key = decode(stored)
                    try {
                        val data = CloudAccountStore.exportData(context).toByteArray()
                        try {
                            write(bundle(context), envelope.put("payload", seal(data, key)).toString().toByteArray())
                        } finally {
                            data.fill(0)
                        }
                    } finally {
                        key.fill(0)
                    }
                }.onFailure { android.util.Log.w("CloudAccountRecovery", "恢复快照更新失败，上一份有效副本已保留", it) }
            }
        }
    }
    private fun prepareRecovery(context: Context, plain: ByteArray) {
        CloudAccountStore.validateData(plain.toString(Charsets.UTF_8))
        val source = File(context.applicationInfo.dataDir, "shared_prefs")
        val archive = File(context.noBackupFilesDir, "cloud-before-key-recovery-${System.currentTimeMillis()}")
        check(archive.mkdirs()) { "无法保存原数据，已停止恢复" }
        source.listFiles().orEmpty().filter { entry ->
            entry.name.startsWith("cloud_account") || CloudAccountStore.providers.any { entry.name == "$it.xml" || entry.name == "$it.xml.bak" }
        }.forEach { entry ->
            val bytes = entry.readBytes()
            java.io.FileOutputStream(File(archive, entry.name)).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
        }
        CredentialCrypto.prepareForRecovery()
    }
    private fun write(file: AtomicFile, data: ByteArray) {
        require(data.size <= MAX_BYTES) { "本地恢复文件过大，上一份副本已保留" }
        val stream = file.startWrite()
        try {
            stream.write(data)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
