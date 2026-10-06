package com.fluxplayer.app.core.tingshu

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import voice.core.extension.engine.SourceHostBridge
import voice.core.extension.engine.SourceSetting

/** Encrypted state isolated by both package ID and source ID, retained on package updates. */
internal class JdrSourcePreferences(context: Context, namespace: String, private val fields: List<SourceSetting>) : SourceHostBridge {
    private val prefs = context.getSharedPreferences("jdr_state", Context.MODE_PRIVATE)
    private val prefix = digest(namespace) + "."

    fun values(): Map<String, String> {
        val saved = read("settings")?.let { Json.parseToJsonElement(it) as? JsonObject }
        return fields.filter { it.type != "button" }.associate { field ->
            val default = when (field.type) {
                "switch" -> field.default.ifBlank { "false" }
                "select" -> field.default.ifBlank { field.options.first() }
                else -> field.default
            }
            field.key to ((saved?.get(field.key) as? JsonPrimitive)?.content ?: default)
        }
    }

    fun save(values: Map<String, String>) {
        require(values.keys.all { key -> fields.any { it.key == key } }) { "未知的书源配置项" }
        val updated = this.values() + values
        fields.filter { it.type != "button" }.forEach { field ->
            val value = updated.getValue(field.key)
            require(value.length <= 16_384) { "配置内容过长" }
            if (field.type == "select") require(value in field.options) { "无效的配置选项" }
            if (field.type == "multiselect") require(value.split(',').filter { it.isNotBlank() }.all { it in field.options }) { "无效的多选项" }
            if (field.type == "switch") require(value in setOf("true", "false")) { "开关值无效" }
        }
        val previous = read("settings")?.let { Json.parseToJsonElement(it) as? JsonObject } ?: JsonObject(emptyMap())
        write("settings", JsonObject(previous + updated.mapValues { JsonPrimitive(it.value) }).toString())
    }

    override fun settings(): String {
        val values = values()
        return JsonObject(
            (read("settings")?.let { Json.parseToJsonElement(it) as? JsonObject } ?: JsonObject(emptyMap())) + fields.filter { it.type != "button" }.associate { field ->
                field.key to if (field.type == "switch") JsonPrimitive(values[field.key] == "true") else JsonPrimitive(values[field.key].orEmpty())
            },
        ).toString()
    }

    override fun putSetting(key: String, value: String?) {
        synchronized(writeLock) {
            val current = read("settings")?.let { Json.parseToJsonElement(it) as? JsonObject } ?: JsonObject(emptyMap())
            val updated = if (value == null) current - key else current + (key to JsonPrimitive(value))
            write("settings", JsonObject(updated).toString())
        }
    }

    override fun get(key: String): String? = read(stateKey(key))
    override fun set(key: String, value: String) = write(stateKey(key), value)
    override fun remove(key: String) {
        check(prefs.edit().remove(prefix + stateKey(key)).commit())
    }
    override fun clear() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix + "state.") || it.startsWith(prefix + "cache.") }.forEach(editor::remove)
        check(editor.commit())
    }

    fun clearCache() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix + "cache.") }.forEach(editor::remove)
        check(editor.commit())
    }

    /** 同时清理会话与配置凭证，保留听书目录及普通偏好。 */
    fun clearLogin() = synchronized(writeLock) {
        val retained = settingsAfterLogout(fields, values())
        clear()
        write("settings", JsonObject(retained.mapValues { JsonPrimitive(it.value) }).toString())
    }

    private fun stateKey(key: String): String = (if (key.startsWith("cache:")) "cache." else "state.") + digest(key)

    private fun read(key: String): String? {
        val stored = prefs.getString(prefix + key, null) ?: return null
        val bytes = Base64.decode(stored, Base64.DEFAULT)
        require(bytes.size > 12) { "书源状态损坏，请重新登录" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD((prefix + key).toByteArray())
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }

    private fun write(key: String, value: String) {
        require(value.toByteArray().size <= 256 * 1024) { "书源存储单项不能超过 256 KB" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        cipher.updateAAD((prefix + key).toByteArray())
        val encoded = Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
        synchronized(writeLock) {
            val used = prefs.all.filterKeys { it.startsWith(prefix) && it != prefix + key }.values.sumOf { (it as? String)?.length ?: 0 }
            require(used + encoded.length <= 3 * 1024 * 1024) { "书源状态存储已满，请清理书源缓存" }
            check(prefs.edit().putString(prefix + key, encoded).commit()) { "书源状态保存失败" }
        }
    }

    companion object {
        private const val KEY_ALIAS = "fluxplayer.jdr.state"
        private val writeLock = Any()

        @Synchronized
        private fun secretKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(
                    KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
                )
            }.generateKey()
        }
    }
}
