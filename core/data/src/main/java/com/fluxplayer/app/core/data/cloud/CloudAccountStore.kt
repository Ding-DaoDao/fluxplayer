package com.fluxplayer.app.core.data.cloud

import android.content.Context
import android.content.SharedPreferences
import com.fluxplayer.app.core.common.CloudAccountIdentity
import com.fluxplayer.app.core.data.security.CloudSessionFence
import com.fluxplayer.app.core.data.security.CredentialCrypto
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** 每个账号使用独立配置文件；首次账号沿用旧文件，升级不搬动原始凭证。 */
object CloudAccountStore {
    private val secretFields = setOf("authorization", "refresh_token", "device_id", "signature", "device_private_key", "user_id", "cookies", "token", "passport", "password", "username", "cookie", "phoneNumber", "userDomainId", "accessToken", "sessionKey", "sessionSecret", "refreshToken", "tv_access_token", "tv_refresh_token", "previous_token", "previous_authorization")
    private val pendingLogin = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun consumeLogin(provider: String): Boolean = pendingLogin.remove(provider)

    @Synchronized fun beginLogin(context: Context, provider: String, id: String) {
        pendingLogin.add(provider)
        CloudAccountIdentity.runAs(provider, id) { CloudSessionFence.advance(provider) }
        select(context, provider, id)
    }
    val recoveryRequired = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val revisions = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Long>>(emptyMap())
    val providers = setOf("alipan", "pan123", "quark", "uc", "yun139", "cloud189")
    data class Account(val id: String, val name: String, val active: Boolean, val readable: Boolean, val configured: Boolean)
    private fun validateState(state: JSONObject): JSONObject {
        val rows = state.getJSONArray("accounts")
        require(rows.length() in 1..100)
        val ids = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            row.getString("name")
            if (row.has("storage")) require(row.getString("storage").matches(Regex("[a-f0-9-]{36}")))
            row.getString("id").also { require(it == "legacy" || it.matches(Regex("[a-f0-9-]{36}"))) }
        }
        require(ids.distinct().size == ids.size && state.getString("active") in ids)
        return state
    }
    private fun registry(context: Context) = context.applicationContext.getSharedPreferences("cloud_accounts", Context.MODE_PRIVATE)
    private fun read(context: Context, provider: String): JSONObject {
        require(provider in providers)
        val prefs = registry(context)
        val raw = prefs.getString(provider, null)
        if (raw != null) {
            return try {
                validateState(JSONObject(raw))
            } catch (error: Exception) {
                throw IllegalStateException("账号列表无法读取，请从账号管理恢复本地副本或导入备份", error)
            }
        }
        val state = JSONObject().put("active", "legacy").put("accounts", JSONArray().put(JSONObject().put("id", "legacy").put("name", "默认账号")))
        check(prefs.edit().putString(provider, state.toString()).commit()) { "初始化账号列表失败" }
        CloudAccountIdentity.setActive(provider, "legacy")
        return state
    }
    private fun write(context: Context, provider: String, state: JSONObject, sessionChanged: Boolean = true) {
        validateState(state)
        val prefs = registry(context)
        val editor = prefs.edit()
        prefs.getString(provider, null)?.takeIf { runCatching { validateState(JSONObject(it)) }.isSuccess }
            ?.let { editor.putString("previous_$provider", it) }
        check(editor.putString(provider, state.toString()).commit()) { "保存账号列表失败，请重试" }
        CloudAccountIdentity.setActive(provider, state.getString("active"))
        recoveryRequired.value = recoveryRequired.value - provider
        if (sessionChanged) publish(provider)
        CloudAccountRecovery.schedule(context)
    }
    private fun rawAccountPreferences(context: Context, provider: String, storage: String): SharedPreferences {
        require(provider in providers && (storage == "legacy" || storage.matches(Regex("[a-f0-9-]{36}"))))
        val prefs = context.applicationContext.getSharedPreferences(if (storage == "legacy") provider else "cloud_account_${provider}_$storage", Context.MODE_PRIVATE)
        CloudAccountRecovery.watch(context, prefs)
        return prefs
    }
    fun accountPreferences(context: Context, provider: String, id: String): SharedPreferences {
        require(provider in providers && (id == "legacy" || id.matches(Regex("[a-f0-9-]{36}"))))
        val rows = read(context, provider).getJSONArray("accounts")
        val row = (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getString("id") == id }
        return rawAccountPreferences(context, provider, row?.optString("storage", id) ?: id)
    }

    @Synchronized fun contains(context: Context, provider: String, id: String): Boolean {
        if (provider !in providers) return false
        val rows = read(context, provider).getJSONArray("accounts")
        return (0 until rows.length()).any { rows.getJSONObject(it).getString("id") == id }
    }

    @Synchronized fun preferences(context: Context, provider: String): SharedPreferences {
        if (provider !in providers) return context.getSharedPreferences(provider, Context.MODE_PRIVATE)
        val bound = CloudAccountIdentity.bound(provider)
        val id = try {
            val state = read(context, provider)
            if (bound != null) {
                check(contains(context, provider, bound)) { "文件所属账号已移除，请重新添加" }
                bound
            } else {
                state.getString("active")
            }
        } catch (error: Exception) {
            if (bound != null) throw error
            recoveryRequired.value = recoveryRequired.value + provider
            return context.getSharedPreferences("cloud_locked_$provider", Context.MODE_PRIVATE)
        }
        recoveryRequired.value = recoveryRequired.value - provider
        if (bound == null) CloudAccountIdentity.setActive(provider, id)
        return accountPreferences(context, provider, id)
    }

    @Synchronized fun accounts(context: Context, provider: String): List<Account> {
        val state = read(context, provider)
        CloudAccountIdentity.setActive(provider, state.getString("active"))
        val rows = state.getJSONArray("accounts")
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val id = row.getString("id")
            val readable = accountPreferences(context, provider, id).all.all { (key, value) ->
                value !is String || !value.startsWith("enc:v1:") || CredentialCrypto.decode(value, "$provider.$key") != null
            }
            Account(id, row.getString("name"), id == state.getString("active"), readable, accountPreferences(context, provider, id).all.keys.any { it in secretFields })
        }
    }
    private fun publish(provider: String) {
        revisions.value = revisions.value + (provider to ((revisions.value[provider] ?: 0) + 1))
    }

    @Synchronized internal fun notifyCredentialsRestored() {
        providers.forEach {
            CloudSessionFence.advance(it)
            publish(it)
        }
    }

    @Synchronized fun add(context: Context, provider: String, name: String): String {
        val state = read(context, provider)
        val id = UUID.randomUUID().toString()
        state.getJSONArray("accounts").put(JSONObject().put("id", id).put("name", name.trim().ifBlank { "新账号" }))
        state.put("active", id)
        write(context, provider, state)
        return id
    }

    @Synchronized fun select(context: Context, provider: String, id: String) {
        val state = read(context, provider)
        check((0 until state.getJSONArray("accounts").length()).any { state.getJSONArray("accounts").getJSONObject(it).getString("id") == id }) { "账号不存在" }
        write(context, provider, state.put("active", id))
    }

    @Synchronized fun rename(context: Context, provider: String, id: String, name: String) {
        require(name.isNotBlank()) { "请输入账号名称" }
        val state = read(context, provider)
        val rows = state.getJSONArray("accounts")
        (0 until rows.length()).map { rows.getJSONObject(it) }.first { it.getString("id") == id }.put("name", name.trim())
        write(context, provider, state, sessionChanged = false)
    }

    @Synchronized fun remove(context: Context, provider: String, id: String) {
        val state = read(context, provider)
        CloudAccountIdentity.runAs(provider, id) { CloudSessionFence.advance(provider) }
        val old = state.getJSONArray("accounts")
        val next = JSONArray()
        (0 until old.length()).map { old.getJSONObject(it) }.filter { it.getString("id") != id }.forEach { next.put(it) }
        if (next.length() == 0) next.put(JSONObject().put("id", UUID.randomUUID().toString()).put("name", "新账号"))
        val activeRemoved = state.getString("active") == id
        if (activeRemoved) state.put("active", next.getJSONObject(0).getString("id"))
        write(context, provider, state.put("accounts", next), sessionChanged = activeRemoved)
        // 保留被移除的加密文件供恢复使用，不再参与活动账号列表。
    }

    @Synchronized fun restorePrevious(context: Context, provider: String) {
        val previous = registry(context).getString("previous_$provider", null) ?: error("没有可恢复的本地副本")
        val state = validateState(JSONObject(previous))
        invalidateAll(context, provider)
        clearRuntimeAccounts(provider)
        write(context, provider, state)
        if (provider == "cloud189") com.fluxplayer.app.core.data.cloud189.C189AuthProvider.initialize(context)
        invalidateAll(context, provider)
    }

    /** 导出先完整解密，任一字段不可读便停止，避免用空值覆盖恢复副本。 */
    @Synchronized fun exportData(context: Context): String {
        val result = JSONObject().put("version", 1)
        val platforms = JSONArray()
        providers.forEach { provider ->
            val state = read(context, provider)
            val rows = state.getJSONArray("accounts")
            val profiles = JSONArray()
            (0 until rows.length()).forEach { index ->
                val row = rows.getJSONObject(index)
                val values = JSONArray()
                accountPreferences(context, provider, row.getString("id")).all.forEach { (key, value) ->
                    val secret = value is String && (value.startsWith("enc:v1:") || key in secretFields)
                    val plain = if (secret) CredentialCrypto.decode(value as String, "$provider.$key") ?: error("${row.getString("name")}的凭证无法读取，请先恢复") else value
                    val type = when (plain) {
                        is String -> "string"
                        is Long -> "long"
                        is Int -> "int"
                        is Boolean -> "boolean"
                        is Float -> "float"
                        is Set<*> -> "set"
                        else -> error("不支持的账号字段")
                    }
                    values.put(JSONObject().put("key", key).put("type", type).put("secret", secret).put("value", if (plain is Set<*>) JSONArray(plain.toList()) else plain))
                }
                profiles.put(JSONObject().put("id", row.getString("id")).put("values", values))
            }
            platforms.put(JSONObject().put("provider", provider).put("state", state).put("profiles", profiles))
        }
        return result.put("platforms", platforms).toString()
    }

    private data class ImportField(val value: Any, val secret: Boolean)
    private data class ImportPlatform(val provider: String, val state: JSONObject, val profiles: Map<String, Map<String, ImportField>>)

    /** 密钥修复之前也调用同一套校验，不创建账号配置、不写入密钥。 */
    fun validateData(data: String) {
        parseImport(data)
    }

    private fun parseImport(data: String): List<ImportPlatform> {
        val root = JSONObject(data)
        require(root.getInt("version") == 1) { "不支持的账号备份版本" }
        val platforms = root.getJSONArray("platforms")
        require(platforms.length() == providers.size) { "账号备份不完整" }
        val seen = mutableSetOf<String>()
        return (0 until platforms.length()).map { index ->
            val platform = platforms.getJSONObject(index)
            val provider = platform.getString("provider")
            require(provider in providers && seen.add(provider)) { "备份网盘重复或无效" }
            val state = validateState(platform.getJSONObject("state"))
            val rows = state.getJSONArray("accounts")
            val profiles = platform.getJSONArray("profiles")
            require(profiles.length() == rows.length()) { "账号数量无效" }
            val valuesByAccount = linkedMapOf<String, Map<String, ImportField>>()
            (0 until profiles.length()).forEach { number ->
                val profile = profiles.getJSONObject(number)
                val id = profile.getString("id")
                require(id == rows.getJSONObject(number).getString("id")) { "账号资料归属不一致" }
                val fields = profile.getJSONArray("values")
                require(fields.length() <= 200)
                val values = linkedMapOf<String, ImportField>()
                (0 until fields.length()).forEach { fieldIndex ->
                    val field = fields.getJSONObject(fieldIndex)
                    val key = field.getString("key")
                    require(key !in values && key.length in 1..200)
                    val value: Any = when (field.getString("type")) {
                        "string" -> field.getString("value")
                        "long" -> field.getLong("value")
                        "int" -> field.getInt("value")
                        "boolean" -> field.getBoolean("value")
                        "float" -> field.getDouble("value").toFloat().also { require(it.isFinite()) }
                        "set" -> field.getJSONArray("value").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
                        else -> error("账号字段类型无效")
                    }
                    val secret = field.getBoolean("secret") || key in secretFields
                    require(!secret || value is String) { "秘密字段必须是文本" }
                    values[key] = ImportField(value, secret)
                }
                valuesByAccount[id] = values
            }
            ImportPlatform(provider, state, valuesByAccount)
        }
    }

    /** 凭证先写入全新文件，最后用一次原子配置提交发布六家指针，崩溃时只会看到完整旧版或新版。 */
    @Synchronized fun importData(context: Context, data: String) {
        val plans = parseImport(data)
        val targetRegistry = registry(context).all.filterValues { it != null }.mapValues { it.value!! }.toMutableMap()
        val pending = linkedMapOf<SharedPreferences, Map<String, Any>>()
        plans.forEach { plan ->
            val rows = plan.state.getJSONArray("accounts")
            plan.profiles.entries.forEachIndexed { index, (id, fields) ->
                val storage = UUID.randomUUID().toString()
                check(rows.getJSONObject(index).getString("id") == id)
                rows.getJSONObject(index).put("storage", storage)
                pending[rawAccountPreferences(context, plan.provider, storage)] = fields.mapValues { (key, field) ->
                    if (field.secret) CredentialCrypto.encode(field.value as String, "${plan.provider}.$key") else field.value
                }
            }
            targetRegistry[plan.provider]?.takeIf { runCatching { validateState(JSONObject(it as String)) }.isSuccess }?.let { previous -> targetRegistry["previous_${plan.provider}"] = previous }
            targetRegistry[plan.provider] = plan.state.toString()
        }
        val oldRegistry = registry(context).all.filterValues { it != null }.mapValues { it.value!! }
        val archive = java.io.File(context.noBackupFilesDir, "cloud-registry-before-recovery-${System.currentTimeMillis()}.json")
        java.io.FileOutputStream(archive).use { stream ->
            stream.write(JSONObject(oldRegistry).toString().toByteArray())
            stream.fd.sync()
        }
        // 尚未发布的新文件不会被任何读取入口选中，失败或进程退出时仍沿用旧指针。
        pending.forEach { (prefs, values) -> writeValues(prefs, values) }
        invalidateAll(context)
        try {
            writeValues(registry(context), targetRegistry)
        } catch (error: Exception) {
            runCatching { writeValues(registry(context), oldRegistry) }
            throw IllegalStateException("发布恢复数据失败，原账号文件已保留，请重试", error)
        } finally {
            clearRuntimeAccounts()
            providers.forEach { provider ->
                runCatching { CloudAccountIdentity.setActive(provider, read(context, provider).getString("active")) }
            }
            invalidateAll(context)
            providers.forEach { publish(it) }
        }
        recoveryRequired.value = emptySet()
        com.fluxplayer.app.core.data.cloud189.C189AuthProvider.initialize(context)
        CloudAccountRecovery.schedule(context)
    }

    private fun clearRuntimeAccounts(provider: String? = null) {
        if (provider == null || provider == "alipan") com.fluxplayer.app.core.data.aliyun.AliyunAuthProvider.clearAll()
        if (provider == null || provider == "pan123") com.fluxplayer.app.core.data.pan123.Pan123AuthProvider.clearAll()
        if (provider == null || provider == "yun139") com.fluxplayer.app.core.data.yun139.Yun139AuthProvider.clearAll()
        if (provider == null || provider == "cloud189") com.fluxplayer.app.core.data.cloud189.C189AuthProvider.clearAll()
        com.fluxplayer.app.core.data.quark.clearAllQuarkAccounts(provider)
        if (provider == null) com.fluxplayer.app.core.data.GlobalCookieJar.clearAll() else com.fluxplayer.app.core.data.GlobalCookieJar.clearProvider(provider)
    }
    private fun invalidateAll(context: Context, onlyProvider: String? = null) {
        providers.filter { onlyProvider == null || it == onlyProvider }.forEach { provider ->
            runCatching {
                val rows = read(context, provider).getJSONArray("accounts")
                (0 until rows.length()).forEach { index ->
                    CloudAccountIdentity.runAs(provider, rows.getJSONObject(index).getString("id")) { CloudSessionFence.advance(provider) }
                }
            }
        }
    }
    private fun writeValues(prefs: SharedPreferences, values: Map<String, Any>) {
        val editor = prefs.edit().clear()
        values.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.map { it as String }.toSet())
            }
        }
        check(editor.commit()) { "写入账号配置失败" }
    }
}

fun Context.cloudPreferences(name: String, mode: Int = Context.MODE_PRIVATE): SharedPreferences =
    if (name in CloudAccountStore.providers) CloudAccountStore.preferences(this, name) else getSharedPreferences(name, mode)
