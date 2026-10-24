package com.fluxplayer.app.core.data.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.fluxplayer.app.core.common.CloudAccountIdentity
import com.fluxplayer.app.core.common.CloudPlaylistCache
import com.fluxplayer.app.core.data.cloud.CloudAccountRecovery
import com.fluxplayer.app.core.data.cloud.CloudAccountStore
import java.io.File
import java.util.UUID
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 使用独立测试配置与样例凭证验证多账号和密码恢复，禁止接触真实账号。 */
class CloudAccountRecoveryTest {
    private class IsolatedContext : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        private val prefix = "accounts-test-${UUID.randomUUID()}-"
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = super.getSharedPreferences(prefix + name, mode)
        override fun getNoBackupFilesDir(): File = File(super.getNoBackupFilesDir(), prefix).apply { mkdirs() }
    }
    private val context = IsolatedContext()
    private val password get() = "fixture-password-123".toCharArray()
    private fun prefs(provider: String) = CloudAccountStore.preferences(context, provider)
    private fun secret(provider: String, value: String) = prefs(provider).edit().putSecret(provider, "cookie", value).commit()

    @Test fun allSixProvidersKeepAccountsIndependentAndRestoreSelection() {
        CloudAccountStore.providers.forEach { provider ->
            secret(provider, "fixture-first-$provider")
            val second = CloudAccountStore.add(context, provider, "第二账号")
            secret(provider, "fixture-second-$provider")
            CloudAccountStore.select(context, provider, "legacy")
            assertEquals("fixture-first-$provider", prefs(provider).getSecret(provider, "cookie"))
            CloudAccountStore.select(context, provider, second)
            assertEquals("fixture-second-$provider", prefs(provider).getSecret(provider, "cookie"))
            assertEquals(second, CloudAccountIdentity.active(provider))
            assertEquals(2, CloudAccountStore.accounts(context, provider).size)
        }
    }

    @Test fun staleClientWritesOnlyItsOriginalAccountPreferences() {
        val first = prefs("uc")
        secret("uc", "fixture-first")
        val revision = CloudSessionFence.current("uc")
        CloudAccountStore.add(context, "uc", "第二账号")
        secret("uc", "fixture-second")
        assertFalse(CloudSessionFence.commit("uc", revision) { first.edit().putSecret("uc", "cookie", "late").commit() })
        assertEquals("fixture-second", prefs("uc").getSecret("uc", "cookie"))
    }

    @Test fun playlistCacheDoesNotReuseOtherAccountsUrl() {
        prefs("quark")
        CloudPlaylistCache.putResolvedUrl("quark", "same-id", "https://fixture.invalid/first")
        CloudAccountStore.add(context, "quark", "第二账号")
        assertNull(CloudPlaylistCache.getResolvedUrl("quark", "same-id"))
        CloudAccountStore.select(context, "quark", "legacy")
        assertEquals("https://fixture.invalid/first", CloudPlaylistCache.getResolvedUrl("quark", "same-id"))
    }

    @Test fun passwordBackupRoundTripsAccountsTvGrantAndFieldTypes() {
        secret("uc", "fixture-cookie")
        val second = CloudAccountStore.add(context, "uc", "电视账号")
        prefs("uc").edit().putSecret("uc", "tv_access_token", "fixture-tv").putSecret("uc", "tv_refresh_token", "fixture-refresh")
            .putString("tv_owner", "fixture-owner").putLong("tv_expires_at", 9876543210123).putBoolean("flag", true).commit()
        val backup = CloudAccountRecovery.export(context, password)
        assertFalse(backup.toString(Charsets.UTF_8).contains("fixture-tv"))
        val other = IsolatedContext()
        CloudAccountRecovery.restore(other, backup, password)
        val restored = CloudAccountStore.preferences(other, "uc")
        assertEquals(second, CloudAccountIdentity.active("uc"))
        assertEquals(2, CloudAccountStore.accounts(other, "uc").size)
        assertEquals("fixture-tv", restored.getSecret("uc", "tv_access_token"))
        assertTrue(restored.getString("tv_access_token", "")!!.startsWith("enc:v1:"))
        assertEquals(9876543210123, restored.getLong("tv_expires_at", 0))
        assertTrue(restored.getBoolean("flag", false))
    }

    @Test fun wrongPasswordDoesNotModifyCurrentAccounts() {
        secret("alipan", "fixture-original")
        val backup = CloudAccountRecovery.export(context, password)
        secret("alipan", "fixture-current")
        assertTrue(runCatching { CloudAccountRecovery.restore(context, backup, "wrong-password".toCharArray()) }.isFailure)
        assertEquals("fixture-current", prefs("alipan").getSecret("alipan", "cookie"))
    }

    @Test fun unreadableCredentialsCannotOverwriteGoodLocalRecovery() {
        secret("cloud189", "fixture-recoverable")
        CloudAccountRecovery.enable(context, password)
        prefs("cloud189").edit().putString("cookie", "enc:v1:invalid-ciphertext").commit()
        assertFalse(CloudAccountStore.accounts(context, "cloud189").first().readable)
        assertTrue(runCatching { CloudAccountRecovery.export(context, password) }.isFailure)
        CloudAccountRecovery.unlock(context, password)
        assertEquals("fixture-recoverable", prefs("cloud189").getSecret("cloud189", "cookie"))
    }

    @Test fun damagedAccountListHasRecoveryEntryAndPreviousSnapshot() {
        prefs("pan123")
        CloudAccountStore.add(context, "pan123", "新账号")
        context.getSharedPreferences("cloud_accounts", 0).edit().putString("pan123", "broken-json").commit()
        assertTrue(CloudAccountStore.preferences(context, "pan123").all.isEmpty())
        assertTrue("pan123" in CloudAccountStore.recoveryRequired.value)
        CloudAccountStore.restorePrevious(context, "pan123")
        assertEquals("legacy", CloudAccountStore.accounts(context, "pan123").single().id)
    }

    @Test fun invalidRestoreStructureLeavesAllOriginalAccountsUnchanged() {
        secret("yun139", "fixture-original")
        val data = JSONObject(CloudAccountStore.exportData(context))
        data.getJSONArray("platforms").getJSONObject(5).getJSONObject("state").put("active", "missing")
        assertTrue(runCatching { CloudAccountStore.importData(context, data.toString()) }.isFailure)
        assertEquals("fixture-original", prefs("yun139").getSecret("yun139", "cookie"))
    }

    @Test fun removedAccountCanBeRecoveredFromPreviousList() {
        secret("quark", "fixture-original")
        CloudAccountStore.remove(context, "quark", "legacy")
        assertFalse(CloudAccountStore.accounts(context, "quark").any { it.id == "legacy" })
        CloudAccountStore.restorePrevious(context, "quark")
        assertEquals("fixture-original", prefs("quark").getSecret("quark", "cookie"))
    }

    @Test fun lostDeviceKeyCanRecoverAllAccountsWithPassword() {
        secret("alipan", "fixture-before-key-loss")
        CloudAccountRecovery.enable(context, password)
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.deleteEntry(AndroidKeystoreCredentialCipher.KEY_ALIAS)
        assertFalse(CloudAccountStore.accounts(context, "alipan").first().readable)
        CloudAccountRecovery.unlock(context, password)
        assertEquals("fixture-before-key-loss", prefs("alipan").getSecret("alipan", "cookie"))
    }

    @Test fun downloadAccountMigrationPreservesExistingTasks() {
        val databaseName = "fixture-download-migration-${UUID.randomUUID()}.db"
        fun helper(version: Int) = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context).name(databaseName)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE download_tasks (id INTEGER PRIMARY KEY NOT NULL, fileName TEXT NOT NULL, provider TEXT NOT NULL)")
                        db.execSQL("INSERT INTO download_tasks VALUES (7, 'fixture.mp4', 'quark')")
                    }
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        com.fluxplayer.app.core.database.MediaDatabase.MIGRATION_13_14.migrate(db)
                    }
                }).build(),
        )
        helper(13).use { it.writableDatabase }
        helper(14).use { helper ->
            helper.writableDatabase.query("SELECT id, fileName, accountId FROM download_tasks").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(7, cursor.getInt(0))
                assertEquals("fixture.mp4", cursor.getString(1))
                assertEquals("legacy", cursor.getString(2))
            }
        }
        context.deleteDatabase(databaseName)
    }

    @Test fun qualityAndFallbackCachesAreIsolatedPerAccount() {
        val cache = com.fluxplayer.app.core.common.VideoQualityCache(context)
        CloudAccountStore.providers.forEach { provider ->
            prefs(provider)
            val url = "https://fixture.invalid/$provider/first"
            cache.cacheQualityOptions(provider, "same-file", "测试文件", listOf(com.fluxplayer.app.core.common.VideoQualityCache.QualityOption("原画", url)))
            if (provider == "pan123") com.fluxplayer.app.core.common.Pan123FallbackCache.put("same-file", url)
            CloudAccountStore.add(context, provider, "第二账号")
            assertNull(cache.getQualityOptions(provider, "same-file"))
            assertNull(cache.getQualityOptionsByUrl(provider, url))
            assertFalse(cache.hasQualityOptions(provider, "same-file"))
            if (provider == "pan123") assertNull(com.fluxplayer.app.core.common.Pan123FallbackCache.get("same-file"))
            CloudAccountStore.select(context, provider, "legacy")
            assertEquals(url, cache.getQualityOptions(provider, "same-file")!!.single().url)
            assertEquals(url, cache.getQualityOptionsByUrl(provider, url)!!.single().url)
            if (provider == "pan123") assertEquals(url, com.fluxplayer.app.core.common.Pan123FallbackCache.get("same-file"))
        }
    }

    @Test fun editingInactiveAccountDoesNotInvalidateCurrentSession() {
        prefs("quark")
        val second = CloudAccountStore.add(context, "quark", "第二账号")
        val revision = CloudSessionFence.current("quark")
        CloudAccountStore.rename(context, "quark", "legacy", "已改名")
        assertEquals(revision, CloudSessionFence.current("quark"))
        CloudAccountStore.remove(context, "quark", "legacy")
        assertEquals(revision, CloudSessionFence.current("quark"))
        assertEquals(second, CloudAccountStore.accounts(context, "quark").single().id)
    }

    @Test fun missingAccountNameCannotBeImportedOrPreparedForRecovery() {
        secret("alipan", "fixture-current")
        val json = JSONObject(CloudAccountStore.exportData(context))
        json.getJSONArray("platforms").getJSONObject(0).getJSONObject("state").getJSONArray("accounts").getJSONObject(0).remove("name")
        assertTrue(runCatching { CloudAccountStore.validateData(json.toString()) }.isFailure)
        assertTrue(runCatching { CloudAccountStore.importData(context, json.toString()) }.isFailure)
        assertEquals("fixture-current", prefs("alipan").getSecret("alipan", "cookie"))
    }

    @Test fun malformedSecretFieldDoesNotPartiallyRestoreOtherProviders() {
        secret("alipan", "fixture-current")
        val json = JSONObject(CloudAccountStore.exportData(context))
        val fields = json.getJSONArray("platforms").getJSONObject(5).getJSONArray("profiles").getJSONObject(0).getJSONArray("values")
        fields.put(JSONObject().put("key", "password").put("type", "long").put("value", 42).put("secret", false))
        assertTrue(runCatching { CloudAccountStore.validateData(json.toString()) }.isFailure)
        assertTrue(runCatching { CloudAccountStore.importData(context, json.toString()) }.isFailure)
        assertEquals("fixture-current", prefs("alipan").getSecret("alipan", "cookie"))
    }

    @Test fun interruptedAtomicFileCanRecoverFromBackupOnly() {
        secret("quark", "fixture-recoverable")
        CloudAccountRecovery.enable(context, password)
        val original = File(context.noBackupFilesDir, "cloud-accounts-recovery.json")
        assertTrue(original.renameTo(File(original.path + ".bak")))
        assertTrue(CloudAccountRecovery.available(context))
        CloudAccountRecovery.unlock(context, password)
        assertEquals("fixture-recoverable", prefs("quark").getSecret("quark", "cookie"))
    }

    @Test fun mobileNewClientAndFeaturesUseSelectedAccount() = kotlinx.coroutines.runBlocking {
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val transport = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"success\":true,\"data\":{\"items\":[]}}".toResponseBody()).build()
        }.build()
        val first = com.fluxplayer.app.core.data.yun139.Yun139ApiClient(transport).apply {
            configureSession(context)
            setToken("fixture-first", "13800000000", "first")
        }
        val firstFeatures = com.fluxplayer.app.core.data.cloud.CloudProviderFeatures.from(first)
        val selected = CloudAccountStore.add(context, "yun139", "第二账号")
        val second = com.fluxplayer.app.core.data.yun139.Yun139ApiClient(transport).apply {
            configureSession(context)
            setToken("fixture-second", "13900000000", "second")
        }
        val secondFeatures = com.fluxplayer.app.core.data.cloud.CloudProviderFeatures.from(second)
        assertTrue(runCatching { firstFeatures.listPersonalFiles("/") }.isFailure)
        assertEquals(0, requests.get())
        assertEquals(selected, secondFeatures.accountId)
        assertTrue(secondFeatures.listPersonalFiles("/").isEmpty())
        assertEquals(1, requests.get())
    }

    @Test fun pan123RenewalRejectsDifferentUidAndPreservesStoredToken() = kotlinx.coroutines.runBlocking {
        val type = com.fluxplayer.app.core.data.pan123.Pan123ApiClient::class.java
        val config = type.getDeclaredField("cachedConfig").apply { isAccessible = true }
        config.set(null, null)
        try {
            val store = prefs("pan123")
            store.edit().putSecret("pan123", "token", "fixture-old").putSecret("pan123", "passport", "13800000000")
                .putSecret("pan123", "password", "fixture-password").putSecret("pan123", "user_id", "100").commit()
            val transport = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                val response = when {
                    path.contains("getconfig") -> "{\"data\":{\"interfaceapi\":{\"login\":\"https://fixture.invalid/login\",\"getUserInfo\":\"https://fixture.invalid/user\"}}}"
                    path == "/login" -> "{\"message\":\"success\",\"data\":{\"token\":\"fixture-new\",\"refresh_token_expire_time\":9876543210}}"
                    path == "/user" -> "{\"data\":{\"UID\":200,\"Nickname\":\"fixture\"}}"
                    else -> "{\"code\":401,\"message\":\"expired\"}"
                }
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").body(response.toResponseBody()).build()
            }.build()
            val api = com.fluxplayer.app.core.data.pan123.Pan123ApiClient(transport).apply {
                configureSession(context)
                setToken("Bearer fixture-old")
            }
            assertTrue(runCatching { api.featureRequest("file/list/new", JSONObject()) }.isFailure)
            assertEquals("fixture-old", store.getSecret("pan123", "token"))
            assertEquals("100", store.getSecret("pan123", "user_id"))
            assertTrue(store.getBoolean("auto_login_blocked", false))
        } finally {
            config.set(null, null)
        }
    }

    @Test fun restoreRebindsTianyiMemoryToRestoredActiveAccount() {
        prefs("cloud189")
        com.fluxplayer.app.core.data.cloud189.C189AuthProvider.initialize(context)
        com.fluxplayer.app.core.data.cloud189.C189AuthProvider.setTokens("fixture-first", "first-key", "first-secret")
        val data = CloudAccountStore.exportData(context)
        CloudAccountStore.add(context, "cloud189", "第二账号")
        com.fluxplayer.app.core.data.cloud189.C189AuthProvider.initialize(context)
        com.fluxplayer.app.core.data.cloud189.C189AuthProvider.setTokens("fixture-second", "second-key", "second-secret")
        CloudAccountStore.importData(context, data)
        assertEquals("legacy", CloudAccountIdentity.active("cloud189"))
        assertEquals("fixture-first", com.fluxplayer.app.core.data.cloud189.C189AuthProvider.accessToken)
        assertEquals("first-key", com.fluxplayer.app.core.data.cloud189.C189AuthProvider.sessionKey)
    }

    @Test fun quarkAndUcCandidateCookiesCannotOverwriteCurrentSession() = kotlinx.coroutines.runBlocking {
        for (provider in listOf("quark", "uc")) {
            val store = prefs(provider)
            store.edit().putSecret(provider, "cookie", "__uid=first; __pus=original").commit()
            val existing = com.fluxplayer.app.core.data.quark.QuarkApiClient(provider = provider).apply {
                configureSession(context)
                setCookie("__uid=first; __pus=original")
            }
            val valid = java.util.concurrent.atomic.AtomicBoolean(false)
            val transport = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                    .header("Set-Cookie", "__puus=candidate-session; Path=/")
                    .body((if (valid.get()) "{\"code\":0,\"status\":200,\"data\":{\"list\":[]}}" else "{\"code\":31001,\"status\":401}").toResponseBody()).build()
            }.build()
            val candidate = com.fluxplayer.app.core.data.quark.QuarkApiClient(transport, provider).apply { configureSession(context) }
            assertTrue(candidate.validateCookie("__uid=second; __pus=candidate").isFailure)
            assertEquals("__uid=first; __pus=original", store.getSecret(provider, "cookie"))
            valid.set(true)
            val verified = candidate.validateCookie("__uid=second; __pus=candidate").getOrThrow()
            assertTrue(verified.contains("__puus=candidate-session"))
            assertEquals("__uid=first; __pus=original", existing.getCookie())
            assertEquals("__uid=first; __pus=original", com.fluxplayer.app.core.data.quark.quarkPlaybackAuth(provider).cookie)
            assertEquals("__uid=first; __pus=original", store.getSecret(provider, "cookie"))
        }
    }

    @Test fun pan123InvalidImportedTokenPreservesStoredCredentials() = kotlinx.coroutines.runBlocking {
        val config = com.fluxplayer.app.core.data.pan123.Pan123ApiClient::class.java.getDeclaredField("cachedConfig").apply { isAccessible = true }
        config.set(null, null)
        try {
            val store = prefs("pan123")
            store.edit().putSecret("pan123", "token", "fixture-original").putSecret("pan123", "password", "fixture-original-password").commit()
            com.fluxplayer.app.core.data.pan123.Pan123AuthProvider.authorization = "Bearer fixture-original"
            val transport = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                val response = if (chain.request().url.encodedPath.contains("getconfig")) "{\"data\":{\"interfaceapi\":{\"getUserInfo\":\"https://fixture.invalid/user\"}}}" else "{\"data\":{\"UID\":0}}"
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK").body(response.toResponseBody()).build()
            }.build()
            val candidate = com.fluxplayer.app.core.data.pan123.Pan123ApiClient(transport).apply { configureSession(context) }
            assertTrue(candidate.verifyImportedToken("Bearer fixture-invalid").isFailure)
            assertEquals("fixture-original", store.getSecret("pan123", "token"))
            assertEquals("fixture-original-password", store.getSecret("pan123", "password"))
            assertEquals("Bearer fixture-original", com.fluxplayer.app.core.data.pan123.Pan123AuthProvider.authorization)
        } finally {
            config.set(null, null)
        }
    }

    @Test fun backgroundScopeRenewsOriginalAccountWithoutChangingBrowserSelection() = kotlinx.coroutines.runBlocking {
        for (provider in CloudAccountStore.providers) {
            secret(provider, "fixture-first")
            val ticket = CloudSessionFence.current(provider)
            val second = CloudAccountStore.add(context, provider, "浏览账号")
            secret(provider, "fixture-second")
            com.fluxplayer.app.core.data.cloud.CloudAccountScope.withAccount(context, provider, "legacy") {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    assertEquals("legacy", CloudAccountIdentity.active(provider))
                    assertTrue(CloudSessionFence.commit(provider, ticket) { secret(provider, "fixture-renewed") })
                    com.fluxplayer.app.core.data.GlobalCookieJar.setCookie("fixture.139.com", "owner", "first")
                }
            }
            assertEquals(second, CloudAccountIdentity.active(provider))
            assertEquals("fixture-second", prefs(provider).getSecret(provider, "cookie"))
            assertEquals("fixture-renewed", CloudAccountStore.accountPreferences(context, provider, "legacy").getSecret(provider, "cookie"))
            CloudAccountStore.remove(context, provider, "legacy")
            assertTrue(runCatching { com.fluxplayer.app.core.data.cloud.CloudAccountScope.withAccount(context, provider, "legacy") {} }.isFailure)
        }
    }

    @Test fun restorePublishesFreshGenerationAndStalePreferencesCannotOverwriteIt() {
        CloudAccountStore.providers.forEach { secret(it, "fixture-backup-$it") }
        val data = CloudAccountStore.exportData(context)
        val oldFiles = CloudAccountStore.providers.associateWith { prefs(it) }
        CloudAccountStore.providers.forEach { secret(it, "fixture-current-$it") }
        CloudAccountStore.importData(context, data)
        CloudAccountStore.providers.forEach { provider ->
            oldFiles.getValue(provider).edit().putSecret(provider, "cookie", "fixture-late-old-writer").commit()
            assertEquals("fixture-backup-$provider", prefs(provider).getSecret(provider, "cookie"))
            assertTrue(prefs(provider) !== oldFiles.getValue(provider))
        }
        val otherTicket = CloudSessionFence.current("uc")
        com.fluxplayer.app.core.data.quark.quarkPlaybackAuth("uc").cookie = "fixture-uc-session"
        CloudAccountStore.restorePrevious(context, "quark")
        assertEquals(otherTicket, CloudSessionFence.current("uc"))
        assertEquals("fixture-uc-session", com.fluxplayer.app.core.data.quark.quarkPlaybackAuth("uc").cookie)
        assertEquals("fixture-late-old-writer", prefs("quark").getSecret("quark", "cookie"))
    }

    @Test fun failureWhileStagingLeavesAllSixPublishedAccountsUnchanged() {
        CloudAccountStore.providers.forEach { secret(it, "fixture-backup-$it") }
        val data = CloudAccountStore.exportData(context)
        CloudAccountStore.providers.forEach { secret(it, "fixture-current-$it") }
        var staged = 0
        val failing = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = context.noBackupFilesDir
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val prefs = context.getSharedPreferences(name, mode)
                if (!name.startsWith("cloud_account_")) return prefs
                return object : SharedPreferences by prefs {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = prefs.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun clear(): SharedPreferences.Editor {
                                editor.clear()
                                return this
                            }
                            override fun commit(): Boolean = if (++staged == 3) false else editor.commit()
                        }
                    }
                }
            }
        }
        assertTrue(runCatching { CloudAccountStore.importData(failing, data) }.isFailure)
        assertEquals(3, staged)
        CloudAccountStore.providers.forEach { assertEquals("fixture-current-$it", prefs(it).getSecret(it, "cookie")) }
    }

    @Test fun sixProvidersMemoryAndPlaybackHeadersRemainWithTheirOwner() = kotlinx.coroutines.runBlocking {
        val setters = mapOf<String, (String) -> Unit>(
            "alipan" to { com.fluxplayer.app.core.data.aliyun.AliyunAuthProvider.authorization = it },
            "pan123" to { com.fluxplayer.app.core.data.pan123.Pan123AuthProvider.authorization = it },
            "yun139" to { com.fluxplayer.app.core.data.yun139.Yun139AuthProvider.authorization = it },
            "cloud189" to { com.fluxplayer.app.core.data.cloud189.C189AuthProvider.accessToken = it },
            "quark" to { com.fluxplayer.app.core.data.quark.quarkPlaybackAuth("quark").cookie = it },
            "uc" to { com.fluxplayer.app.core.data.quark.quarkPlaybackAuth("uc").cookie = it },
        )
        val getters = mapOf<String, () -> String>(
            "alipan" to { com.fluxplayer.app.core.data.aliyun.AliyunAuthProvider.authorization },
            "pan123" to { com.fluxplayer.app.core.data.pan123.Pan123AuthProvider.authorization },
            "yun139" to { com.fluxplayer.app.core.data.yun139.Yun139AuthProvider.authorization },
            "cloud189" to { com.fluxplayer.app.core.data.cloud189.C189AuthProvider.accessToken },
            "quark" to { com.fluxplayer.app.core.data.quark.quarkPlaybackAuth("quark").cookie },
            "uc" to { com.fluxplayer.app.core.data.quark.quarkPlaybackAuth("uc").cookie },
        )
        for (provider in CloudAccountStore.providers) {
            prefs(provider)
            setters.getValue(provider)("fixture-first")
            com.fluxplayer.app.core.common.CloudPlayHeaders.registerFor(provider, "fixture-$provider.invalid") { mapOf("Owner" to getters.getValue(provider)()) }
            val selected = CloudAccountStore.add(context, provider, "第二账号")
            setters.getValue(provider)("fixture-second")
            com.fluxplayer.app.core.common.CloudPlayHeaders.registerFor(provider, "fixture-$provider.invalid") { mapOf("Owner" to getters.getValue(provider)()) }
            com.fluxplayer.app.core.data.cloud.CloudAccountScope.withAccount(context, provider, "legacy") {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    assertEquals("fixture-first", getters.getValue(provider)())
                    assertEquals("fixture-first", com.fluxplayer.app.core.common.CloudPlayHeaders.getHeaders("fixture-$provider.invalid")["Owner"])
                }
            }
            assertEquals(selected, CloudAccountIdentity.active(provider))
            assertEquals("fixture-second", getters.getValue(provider)())
        }
    }

    @Test fun copyingLoginInfoUsesSelectedAccountAndExcludesPasswordsAndDeviceSecrets() {
        for (provider in CloudAccountStore.providers) {
            prefs(provider).edit().putSecret(provider, "authorization", "fixture-first-token").putSecret(provider, "password", "fixture-password")
                .putSecret(provider, "device_private_key", "fixture-private-key").putSecret(provider, "sessionSecret", "fixture-session-secret").commit()
            val selected = CloudAccountStore.add(context, provider, "浏览账号")
            prefs(provider).edit().putSecret(provider, "authorization", "fixture-second-token").commit()
            val info = com.fluxplayer.app.core.data.cloud.CloudAccountLoginInfo.read(context, provider, "legacy")
            assertEquals("fixture-first-token", info.preferred!!.value)
            assertFalse(info.json.contains("fixture-password"))
            assertFalse(info.json.contains("fixture-private-key"))
            assertFalse(info.json.contains("fixture-session-secret"))
            assertEquals(selected, CloudAccountIdentity.active(provider))
        }
    }
}
