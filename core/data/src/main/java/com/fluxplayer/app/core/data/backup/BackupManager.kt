package com.fluxplayer.app.core.data.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.fluxplayer.app.core.data.aliyun.AliyunAuthProvider
import com.fluxplayer.app.core.data.cloud.cloudPreferences
import com.fluxplayer.app.core.data.cloud189.C189AuthProvider
import com.fluxplayer.app.core.data.openlist.OpenListManagerProvider
import com.fluxplayer.app.core.data.openlist.OpenListServerState
import com.fluxplayer.app.core.data.repository.AudiobookProgressRepository
import com.fluxplayer.app.core.data.repository.WebDavRepository
import com.fluxplayer.app.core.data.security.getSecret
import com.fluxplayer.app.core.data.security.putSecret
import com.fluxplayer.app.core.data.yun139.Yun139AuthProvider
import com.fluxplayer.app.core.datastore.datasource.AppPreferencesDataSource
import com.fluxplayer.app.core.datastore.datasource.BackupWebDavDataSource
import com.fluxplayer.app.core.datastore.datasource.PlayerPreferencesDataSource
import com.fluxplayer.app.core.datastore.datasource.WebDavServersDataSource
import com.fluxplayer.app.core.model.AlipanBackupConfig
import com.fluxplayer.app.core.model.BackupData
import com.fluxplayer.app.core.model.BackupWebDavConfig
import com.fluxplayer.app.core.model.Cloud189BackupConfig
import com.fluxplayer.app.core.model.OpenListBackupConfig
import com.fluxplayer.app.core.model.Pan123BackupConfig
import com.fluxplayer.app.core.model.QuarkBackupConfig
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.core.model.Yun139BackupConfig
import com.fluxplayer.app.core.tingshu.TingshuRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class BackupManager @Inject constructor(
    private val appPreferencesDataSource: AppPreferencesDataSource,
    private val playerPreferencesDataSource: PlayerPreferencesDataSource,
    private val audiobookProgressRepository: AudiobookProgressRepository,
    private val webDavRepository: WebDavRepository,
    private val webDavServersDataSource: WebDavServersDataSource,
    private val backupWebDavDataSource: BackupWebDavDataSource,
    @ApplicationContext private val context: Context,
) {
    private val operationMutex = Mutex()

    private val security get() = context.getSharedPreferences("backup_security", Context.MODE_PRIVATE)
    fun hasBackupPassword(): Boolean = !security.getSecret("backup_security", "password").isNullOrBlank()
    fun setBackupPassword(password: CharArray) {
        require(password.size >= 8) { "备份密码至少 8 位" }
        check(security.edit().putSecret("backup_security", "password", String(password)).commit()) { "保存备份密码失败" }
    }
    private fun encodeArchive(archive: BackupArchive): ByteArray {
        val password = security.getSecret("backup_security", "password")?.toCharArray() ?: throw BackupPasswordRequired()
        return try {
            PasswordProtectedBackup.encrypt(BackupArchiveCodec.encode(archive), password)
        } finally {
            password.fill('\u0000')
        }
    }
    private fun decodeArchive(bytes: ByteArray, password: CharArray?): BackupArchive =
        ByteArrayInputStream(PasswordProtectedBackup.decrypt(bytes, password)).use { BackupArchiveCodec.decode(it) }

    /** 直接读取持久化设置，避免启动阶段的默认状态被误备份。 */
    private suspend fun createBackup(): BackupData = BackupData(
        appPreferences = audiobookProgressRepository.exportTo(appPreferencesDataSource.preferences.first()),
        playerPreferences = playerPreferencesDataSource.preferences.first(),
        webDavServers = webDavServersDataSource.webDavServers.first(),
        openListConfig = readOpenListConfig(),
        alipanConfig = readAlipanConfig(),
        cloud189Config = readCloud189Config(),
        pan123Config = readPan123Config(),
        quarkConfig = readQuarkConfig(),
        yun139Config = readYun139Config(),
        backupWebDavConfig = backupWebDavDataSource.config.first().copy(hasPendingNewBackup = false, lastSeenRemoteBackup = ""),
        onlineListeningLibrary = TingshuRepository.get(context).exportLibrary(),
        cloudAccounts = com.fluxplayer.app.core.data.cloud.CloudAccountStore.exportData(context),
    )

    private suspend fun createArchive(): BackupArchive {
        val dataDir = context.filesDir.resolve("openlist_data")
        val files = dataDir.listFiles().orEmpty().filter { it.isFile }
        if (files.isNotEmpty()) requireOpenListStopped()
        require(files.sumOf { it.length() } <= 24L * 1024 * 1024) { "OpenList 数据过大，无法导出备份" }
        val data = createBackup()
        val covers = com.fluxplayer.app.core.tingshu.ListeningCoverStore.export(context, data.onlineListeningLibrary, TingshuRepository.get(context))
        return BackupArchive(data, files.associate { it.name to it.readBytes() }, covers.files, covers.warnings)
    }

    private fun requireOpenListStopped() {
        val state = OpenListManagerProvider.get()?.state?.value
        check(state == null || state == OpenListServerState.Stopped || state is OpenListServerState.Error) {
            "请先停止 OpenList 本地服务，再备份或恢复其数据"
        }
    }

    /** 只有用户确认后才写入数据；忽略 OpenList 时也不会写入其数据文件。 */
    suspend fun restoreFromBackup(archive: BackupArchive, ignoreList: Set<String> = emptySet()) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val backup = archive.data
            BackupArchiveCodec.validate(backup)
            if ("openlist_config" !in ignoreList && archive.openListFiles.isNotEmpty()) requireOpenListStopped()
            if ("app_preferences" !in ignoreList) {
                com.fluxplayer.app.core.tingshu.ListeningCoverStore.restore(context, archive.coverFiles)
                backup.onlineListeningLibrary?.let { TingshuRepository.get(context).restoreLibrary(it) }
                audiobookProgressRepository.restore(backup.appPreferences)
                appPreferencesDataSource.updateOrThrow { AudiobookProgressRepository.withoutLegacyProgress(backup.appPreferences) }
            }
            if ("player_preferences" !in ignoreList) {
                playerPreferencesDataSource.updateOrThrow { backup.playerPreferences }
            }
            if ("webdav_servers" !in ignoreList) webDavServersDataSource.updateOrThrow { backup.webDavServers }
            if ("openlist_config" !in ignoreList) {
                restoreOpenListFiles(archive.openListFiles)
                backup.openListConfig?.let(::restoreOpenListConfig)
            }
            if ("cloud_credentials" !in ignoreList) {
                if (backup.cloudAccounts != null) {
                    com.fluxplayer.app.core.data.cloud.CloudAccountStore.importData(context, requireNotNull(backup.cloudAccounts))
                } else {
                    backup.alipanConfig?.let(::restoreAlipanConfig)
                    backup.cloud189Config?.let(::restoreCloud189Config)
                    backup.pan123Config?.let(::restorePan123Config)
                    backup.quarkConfig?.let(::restoreQuarkConfig)
                    backup.yun139Config?.let(::restoreYun139Config)
                    com.fluxplayer.app.core.data.cloud.CloudAccountStore.notifyCredentialsRestored()
                }
            }
            if ("backup_settings" !in ignoreList) {
                backup.backupWebDavConfig?.let { restored ->
                    backupWebDavDataSource.updateOrThrow { current ->
                        restored.copy(
                            backupPath = "",
                            localDirectoryUri = current.localDirectoryUri,
                            autoBackupSyncMode = BackupWebDavConfig.AUTO_SYNC_MODE_OFF,
                            // 备份文件里的上次备份时刻属于来源设备，保留本机的值，避免恢复后被别人的时间挡住。
                            lastAutoBackupAt = current.lastAutoBackupAt,
                            hasPendingNewBackup = false,
                            lastSeenRemoteBackup = "",
                            deviceName = current.deviceName,
                        )
                    }
                }
            }
        }
    }

    private fun restoreOpenListFiles(files: Map<String, ByteArray>) {
        if (files.isEmpty()) return
        val directory = context.filesDir.resolve("openlist_data")
        check(directory.isDirectory || directory.mkdirs()) { "无法创建 OpenList 数据目录" }
        // 旧数据库的旁路日志不能应用到恢复后的数据库上。
        files.keys.filter { it.endsWith(".db") }.forEach { database ->
            listOf("$database-wal", "$database-shm").filter { it !in files }.forEach { name ->
                val sidecar = File(directory, name)
                check(!sidecar.exists() || sidecar.delete()) { "无法清理旧的 OpenList 数据库日志" }
            }
        }
        files.forEach { (name, bytes) ->
            BackupArchiveCodec.validateFileName(name)
            val target = File(directory, name)
            check(target.canonicalFile.parentFile == directory.canonicalFile) { "无效的 OpenList 文件路径" }
            val temporary = File.createTempFile("restore-", ".tmp", directory)
            try {
                temporary.outputStream().use { it.write(bytes) }
                check(temporary.renameTo(target)) { "写入 OpenList 数据失败" }
            } finally {
                temporary.delete()
            }
        }
    }

    /** 本地、远程和自动备份均使用同一个密码加密完整归档。 */
    suspend fun exportToDirectory(directory: Uri, fileName: String): BackupExportResult = withContext(Dispatchers.IO) {
        val parent = DocumentsContract.buildDocumentUriUsingTree(directory, DocumentsContract.getTreeDocumentId(directory))
        val target = checkNotNull(DocumentsContract.createDocument(context.contentResolver, parent, "application/zip", fileName)) { "无法在所选目录创建备份，请重新选择目录" }
        try {
            exportToUri(target).copy(fileName = fileName)
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, target) }
            throw error
        }
    }

    suspend fun exportToUri(uri: Uri) = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            val archive = createArchive()
            val bytes = encodeArchive(archive)
            val output = checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法写入备份文件" }
            output.use {
                it.write(bytes)
                it.flush()
            }
            BackupExportResult(coverWarnings = archive.coverWarnings)
        }
    }

    /** 自动识别加密备份，并兼容旧版 ZIP 和 JSON。 */
    suspend fun importFromUri(uri: Uri, password: CharArray? = null): Result<BackupArchive> = withContext(Dispatchers.IO) {
        backupResult {
            val input = checkNotNull(context.contentResolver.openInputStream(uri)) { "无法打开备份文件" }
            input.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 65 * 1024 * 1024 + 128) { "备份文件过大" }
                    output.write(buffer, 0, count)
                }
                decodeArchive(output.toByteArray(), password)
            }
        }
    }

    /** 新建子目录后上传，旧备份始终保留，返回本次实际创建的文件名。 */
    suspend fun uploadToCloud(config: BackupWebDavConfig): Result<BackupExportResult> = withContext(Dispatchers.IO) {
        backupResult {
            operationMutex.withLock {
                require(config.isConfigured) { "请先配置有效的 WebDAV 地址" }
                val archive = createArchive()
                val bytes = encodeArchive(archive)
                var folder = ""
                config.subfolder.trim('/').split('/').filter { it.isNotBlank() }.forEach { part ->
                    require(part != "." && part != ".." && !part.contains('\\')) { "无效的 WebDAV 子文件夹" }
                    folder += "/$part"
                    if (webDavRepository.listDirectory(config.url, "$folder/", config.basicAuthHeader).isFailure) {
                        webDavRepository.createFolder(config.url, folder, config.basicAuthHeader).getOrThrow()
                    }
                }
                val name = config.generateBackupFileName()
                webDavRepository.uploadFile(config.url, config.remoteBackupPath(name), config.basicAuthHeader, bytes).getOrThrow()
                backupWebDavDataSource.updateOrThrow {
                    it.copy(lastSeenRemoteBackup = name, hasPendingNewBackup = false)
                }
                BackupExportResult(name, archive.coverWarnings)
            }
        }
    }

    /** 从云端下载并解析普通备份。 */
    suspend fun downloadFromCloud(
        config: BackupWebDavConfig,
        path: String,
        password: CharArray? = null,
    ): Result<BackupArchive> = withContext(Dispatchers.IO) {
        backupResult {
            val bytes = webDavRepository.downloadFile(config.url, path, config.basicAuthHeader).getOrThrow()
            decodeArchive(bytes, password)
        }
    }

    suspend fun listRemoteBackupFiles(config: BackupWebDavConfig): Result<List<WebDavResource>> = withContext(Dispatchers.IO) {
        backupResult {
            require(config.isConfigured) { "请先配置有效的 WebDAV 地址" }
            webDavRepository.listDirectory(config.url, config.remoteBackupDir(), config.basicAuthHeader).getOrThrow()
                .filter { !it.isDirectory && isBackupFileName(it.name) }
                .sortedWith(
                    compareByDescending<WebDavResource> {
                        runCatching { ZonedDateTime.parse(it.lastModified, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrDefault(0)
                    }.thenByDescending { it.name },
                )
        }
    }

    // ==================== 旧备份清理 ====================

    /** 本地目录里的一份文档，只保留删除所需的最小信息。 */
    private data class LocalBackupDocument(val name: String, val uri: Uri)

    /** 列举本地备份目录下的直接子文件（不递归），需要已持久化的目录授权。 */
    private fun listLocalDocuments(directory: Uri): List<LocalBackupDocument> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(directory, DocumentsContract.getTreeDocumentId(directory))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        val documents = mutableListOf<LocalBackupDocument>()
        context.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                documents += LocalBackupDocument(name, DocumentsContract.buildDocumentUriUsingTree(directory, id))
            }
        }
        return documents
    }

    /**
     * 清理本地目录中的旧自动备份，只保留最近 [keep] 份。
     *
     * 三条安全底线：只删文件名严格匹配备份格式的文件（[isPrunableBackupFileName]）；
     * [keep] 非正数表示不清理；[except]（本次刚创建的文件）永不删除。
     * 单个文件删除失败只记日志，不影响其余文件，也不影响备份结果。返回实际删除的份数。
     */
    suspend fun pruneLocalBackups(directory: Uri, keep: Int, except: String): Result<Int> = withContext(Dispatchers.IO) {
        backupResult {
            if (keep <= 0) return@backupResult 0
            val stale = listLocalDocuments(directory)
                .filter { isPrunableBackupFileName(it.name) }
                .sortedByDescending { backupFileTimestampKey(it.name).orEmpty() }
                .drop(keep)
                .filter { it.name != except }
            stale.count { document ->
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, document.uri) }
                    .onFailure { Log.w(TAG, "删除本地旧备份失败：${document.name}", it) }
                    .isSuccess
            }
        }
    }

    /** 清理 WebDAV 上的旧自动备份，只保留最近 [keep] 份；规则与 [pruneLocalBackups] 相同。 */
    suspend fun pruneRemoteBackups(config: BackupWebDavConfig, keep: Int, except: String): Result<Int> = withContext(Dispatchers.IO) {
        backupResult {
            if (keep <= 0) return@backupResult 0
            require(config.isConfigured) { "请先配置有效的 WebDAV 地址" }
            val stale = webDavRepository.listDirectory(config.url, config.remoteBackupDir(), config.basicAuthHeader)
                .getOrThrow()
                .filter { !it.isDirectory && isPrunableBackupFileName(it.name) }
                .sortedByDescending { backupFileTimestampKey(it.name).orEmpty() }
                .drop(keep)
                .filter { it.name != except }
            stale.count { file ->
                webDavRepository.delete(config.url, config.remoteBackupPath(file.name), config.basicAuthHeader)
                    .onFailure { Log.w(TAG, "删除远端旧备份失败：${file.name}", it) }
                    .isSuccess
            }
        }
    }

    // ==================== OpenList 配置 ====================

    private fun readOpenListConfig(): OpenListBackupConfig? {
        val prefs = context.cloudPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean(PREF_AUTO_START, false)
        val password = prefs.getSecret(PREFS_NAME, PREF_PASSWORD)
        return if (prefs.contains(PREF_AUTO_START) || password != null) {
            OpenListBackupConfig(
                autoStart = autoStart,
                adminPassword = password,
            )
        } else {
            null
        }
    }

    private fun restoreOpenListConfig(config: OpenListBackupConfig) {
        val prefs = context.cloudPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        check(
            prefs.edit()
                .putBoolean(PREF_AUTO_START, config.autoStart)
                .putSecret(PREFS_NAME, PREF_PASSWORD, config.adminPassword)
                .commit(),
        ) { "保存 OpenList 配置失败" }
    }

    // ==================== 阿里云盘 ====================

    private fun readAlipanConfig(): AlipanBackupConfig? {
        val prefs = context.cloudPreferences(ALIPAN_PREFS, Context.MODE_PRIVATE)
        val auth = readCredential(prefs, ALIPAN_PREFS, "authorization")
        if (auth.isNullOrBlank()) return null
        // 读取 WebView Cookie（用于备份后恢复时自动续期）
        val cookies = try {
            android.webkit.CookieManager.getInstance().getCookie("https://www.alipan.com")
        } catch (_: Exception) {
            readCredential(prefs, ALIPAN_PREFS, "cookies")
        }
        return AlipanBackupConfig(
            authorization = auth,
            driveId = readCredential(prefs, ALIPAN_PREFS, "drive_id"),
            refreshToken = readCredential(prefs, ALIPAN_PREFS, "refresh_token"),
            deviceId = readCredential(prefs, ALIPAN_PREFS, "device_id"),
            signature = readCredential(prefs, ALIPAN_PREFS, "signature"),
            cookies = cookies?.takeIf { it.isNotBlank() } ?: readCredential(prefs, ALIPAN_PREFS, "cookies"),
            devicePrivateKey = readCredential(prefs, ALIPAN_PREFS, "device_private_key"),
            expiresAt = prefs.getLong("expires_at", 0),
            userId = readCredential(prefs, ALIPAN_PREFS, "user_id"),
            deviceSessionExpiresAt = prefs.getLong("device_session_expires_at", 0),
        )
    }

    private fun restoreAlipanConfig(config: AlipanBackupConfig) {
        saveCredentials(
            ALIPAN_PREFS,
            mapOf(
                "authorization" to config.authorization,
                "drive_id" to config.driveId,
                "refresh_token" to config.refreshToken,
                "device_id" to config.deviceId,
                "signature" to config.signature,
                "cookies" to config.cookies,
                "device_private_key" to config.devicePrivateKey,
                "expires_at" to config.expiresAt,
                "user_id" to config.userId,
                "device_session_expires_at" to config.deviceSessionExpiresAt,
                "previous_authorization" to null,
            ),
        )
        // 恢复 WebView Cookie，以便 token 过期时通过 WebView 会话自动续期获取全权限 token
        if (!config.cookies.isNullOrBlank()) {
            try {
                val cookieManager = android.webkit.CookieManager.getInstance()
                config.cookies.orEmpty().split(';').map(String::trim).filter { it.isNotBlank() }.forEach { cookieManager.setCookie("https://www.alipan.com", it) }
                cookieManager.flush()
            } catch (_: Exception) {}
        }
        // 同步内存中的 AuthProvider
        AliyunAuthProvider.authorization = config.authorization.orEmpty()
        AliyunAuthProvider.isActive = !config.authorization.isNullOrBlank()
    }

    // ==================== 天翼云盘 ====================

    private fun readCloud189Config(): Cloud189BackupConfig? {
        val prefs = context.cloudPreferences(CLOUD189_PREFS, Context.MODE_PRIVATE)
        val accessToken = readCredential(prefs, CLOUD189_PREFS, "accessToken")
        if (accessToken.isNullOrBlank() && readCredential(prefs, CLOUD189_PREFS, "refreshToken").isNullOrBlank()) return null
        return Cloud189BackupConfig(
            accessToken = accessToken,
            sessionKey = readCredential(prefs, CLOUD189_PREFS, "sessionKey"),
            sessionSecret = readCredential(prefs, CLOUD189_PREFS, "sessionSecret"),
            refreshToken = readCredential(prefs, CLOUD189_PREFS, "refreshToken"),
            expiresIn = prefs.getLong("expiresIn", 0),
            cookies = readCredential(prefs, CLOUD189_PREFS, "cookies"),
            username = readCredential(prefs, CLOUD189_PREFS, "username"),
            password = readCredential(prefs, CLOUD189_PREFS, "password"),
            autoLoginBlocked = prefs.getBoolean("auto_login_blocked", false),
        )
    }

    private fun restoreCloud189Config(config: Cloud189BackupConfig) {
        C189AuthProvider.clear()
        saveCredentials(
            CLOUD189_PREFS,
            mapOf(
                "accessToken" to config.accessToken,
                "sessionKey" to config.sessionKey,
                "sessionSecret" to config.sessionSecret,
                "refreshToken" to config.refreshToken,
                "expiresIn" to config.expiresIn,
                "cookies" to config.cookies,
                "username" to config.username,
                "password" to config.password,
                "auto_login_blocked" to config.autoLoginBlocked,
            ),
        )
        C189AuthProvider.familySessionKey = ""
        C189AuthProvider.familySessionSecret = ""
        com.fluxplayer.app.core.data.cloud189.C189ApiClient().restoreCookies(config.cookies.orEmpty())
        C189AuthProvider.setTokens(
            config.accessToken.orEmpty(),
            config.sessionKey.orEmpty(),
            config.sessionSecret.orEmpty(),
            config.refreshToken.orEmpty(),
            config.expiresIn,
        )
        C189AuthProvider.isActive = !config.accessToken.isNullOrBlank() || !config.refreshToken.isNullOrBlank()
    }

    // ==================== 123 云盘 ====================

    private fun readPan123Config(): Pan123BackupConfig? {
        val prefs = context.cloudPreferences(PAN123_PREFS, Context.MODE_PRIVATE)
        val token = readCredential(prefs, PAN123_PREFS, "token")
        if (token.isNullOrBlank()) return null
        return Pan123BackupConfig(
            passport = readCredential(prefs, PAN123_PREFS, "passport"),
            password = readCredential(prefs, PAN123_PREFS, "password"),
            token = token,
            userId = readCredential(prefs, PAN123_PREFS, "user_id"),
            loginUuid = prefs.getString("login_uuid", null),
            refreshTokenExpireTime = prefs.getLong("refresh_token_expire_time", 0),
            autoLoginBlocked = prefs.getBoolean("auto_login_blocked", false),
        )
    }

    private fun restorePan123Config(config: Pan123BackupConfig) {
        saveCredentials(PAN123_PREFS, mapOf("user_id" to config.userId, "passport" to config.passport, "password" to config.password, "token" to config.token, "login_uuid" to config.loginUuid, "refresh_token_expire_time" to config.refreshTokenExpireTime, "auto_login_blocked" to config.autoLoginBlocked, "previous_token" to null))
    }

    // ==================== 夸克网盘（含 UC） ====================

    private fun readQuarkConfig(): QuarkBackupConfig? {
        val quarkPrefs = context.cloudPreferences(QUARK_PREFS, Context.MODE_PRIVATE)
        val ucPrefs = context.cloudPreferences(UC_PREFS, Context.MODE_PRIVATE)
        val quarkCookie = readCredential(quarkPrefs, QUARK_PREFS, "cookie")
        val ucCookie = readCredential(ucPrefs, UC_PREFS, "cookie")
        if (quarkCookie.isNullOrBlank() && ucCookie.isNullOrBlank()) return null
        return QuarkBackupConfig(
            quarkCookie = quarkCookie, ucCookie = ucCookie,
            quarkRefreshedAt = quarkPrefs.getLong("cookie_refreshed_at", 0),
            ucRefreshedAt = ucPrefs.getLong("cookie_refreshed_at", 0),
            tvAccessToken = readCredential(ucPrefs, UC_PREFS, "tv_access_token"),
            tvRefreshToken = readCredential(ucPrefs, UC_PREFS, "tv_refresh_token"),
            tvOwner = ucPrefs.getString("tv_owner", null), tvDevice = ucPrefs.getString("tv_device", null),
            tvExpiresAt = ucPrefs.getLong("tv_expires_at", 0), tvGeneration = ucPrefs.getString("tv_generation", null),
        )
    }

    private fun restoreQuarkConfig(config: QuarkBackupConfig) {
        config.quarkCookie?.let { saveCredentials(QUARK_PREFS, mapOf("cookie" to it, "cookie_refreshed_at" to config.quarkRefreshedAt)) }
        config.ucCookie?.let { saveCredentials(UC_PREFS, mapOf("cookie" to it, "cookie_refreshed_at" to config.ucRefreshedAt, "tv_access_token" to config.tvAccessToken, "tv_refresh_token" to config.tvRefreshToken, "tv_owner" to config.tvOwner, "tv_device" to config.tvDevice, "tv_expires_at" to config.tvExpiresAt, "tv_generation" to config.tvGeneration)) }
    }

    // ==================== Yun139 ====================

    private fun readYun139Config(): Yun139BackupConfig? {
        val prefs = context.cloudPreferences(YUN139_PREFS, Context.MODE_PRIVATE)
        val auth = readCredential(prefs, YUN139_PREFS, "authorization")
        if (auth.isNullOrBlank()) return null
        return Yun139BackupConfig(
            authorization = auth,
            phoneNumber = readCredential(prefs, YUN139_PREFS, "phoneNumber"),
            userDomainId = readCredential(prefs, YUN139_PREFS, "userDomainId"),
            lastRefresh = prefs.getLong("lastRefresh", 0),
            cookies = readCredential(prefs, YUN139_PREFS, "cookies"),
        )
    }

    private fun restoreYun139Config(config: Yun139BackupConfig) {
        saveCredentials(
            YUN139_PREFS,
            mapOf(
                "authorization" to config.authorization,
                "phoneNumber" to config.phoneNumber,
                "userDomainId" to config.userDomainId,
                "lastRefresh" to config.lastRefresh,
                "cookies" to config.cookies,
            ),
        )
        Yun139AuthProvider.authorization = config.authorization.orEmpty()
        Yun139AuthProvider.cookie = config.cookies.orEmpty()
        Yun139AuthProvider.phoneNumber = config.phoneNumber.orEmpty()
        Yun139AuthProvider.userDomainId = config.userDomainId.orEmpty()
        Yun139AuthProvider.isActive = !config.authorization.isNullOrBlank()
    }

    /**
     * 读取某个凭证字段。
     *
     * 归档使用可恢复的凭证原值，不保存仅当前设备可解开的密文，
     * 所以这里要把落盘的密文解出来；恢复时再由 [saveCredentials] 重新加密写回。
     * 六家网盘统一解密读取；旧明文仍按兼容规则读取。
     */
    private fun readCredential(prefs: SharedPreferences, scope: String, key: String): String? =
        if (shouldEncrypt(scope, key)) prefs.getSecret(scope, key) else prefs.getString(key, null)

    /** 该字段是否走加密存储；读写两侧必须用同一判定，否则会互相读不到。 */
    private fun shouldEncrypt(scope: String, key: String): Boolean =
        scope in ENCRYPTED_SCOPES && key !in PLAINTEXT_KEYS

    /** 使用同步提交报告磁盘写入失败，并移除备份中缺失的旧账号字段。 */
    private fun saveCredentials(name: String, values: Map<String, Any?>) {
        com.fluxplayer.app.core.data.security.CloudSessionFence.advance(name)
        val editor = context.cloudPreferences(name, Context.MODE_PRIVATE).edit()
        values.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is String ->
                    if (shouldEncrypt(name, key)) {
                        editor.putSecret(name, key, value)
                    } else {
                        editor.putString(key, value)
                    }
                is Long -> editor.putLong(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
        }
        check(editor.commit()) { "保存账号配置失败" }
    }

    companion object {
        private const val TAG = "BackupManager"
        private const val PREFS_NAME = "openlist_prefs"
        private const val PREF_PASSWORD = "admin_password"
        private const val PREF_AUTO_START = "openlist_auto_start"

        private const val ALIPAN_PREFS = "alipan"
        private const val CLOUD189_PREFS = "cloud189"
        private const val PAN123_PREFS = "pan123"
        private const val QUARK_PREFS = "quark"
        private const val UC_PREFS = "uc"
        private const val YUN139_PREFS = "yun139"

        /**
         * 已迁移到 Android Keystore 加密存储的 prefs 文件（见 [CredentialCrypto]）。
         * 天翼读写和旧数据迁移已统一使用同一加密入口。
         */
        private val ENCRYPTED_SCOPES = setOf(
            ALIPAN_PREFS,
            CLOUD189_PREFS,
            PAN123_PREFS,
            QUARK_PREFS,
            UC_PREFS,
            YUN139_PREFS,
            PREFS_NAME,
        )

        /** 非机密字段：多处按明文读写，保持原样，避免与既有读法不一致。 */
        private val PLAINTEXT_KEYS = setOf("drive_id", "login_uuid", "tv_owner", "tv_device", "tv_generation")
    }
}
