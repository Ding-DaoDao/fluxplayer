package com.fluxplayer.app.core.data.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import com.fluxplayer.app.core.data.aliyun.AliyunAuthProvider
import com.fluxplayer.app.core.data.cloud189.C189AuthProvider
import com.fluxplayer.app.core.data.repository.PreferencesRepository
import com.fluxplayer.app.core.data.repository.WebDavRepository
import com.fluxplayer.app.core.data.yun139.Yun139AuthProvider
import com.fluxplayer.app.core.datastore.datasource.BackupWebDavDataSource
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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

@Singleton
class BackupManager @Inject constructor(
    private val preferencesRepository: PreferencesRepository,
    private val webDavRepository: WebDavRepository,
    private val webDavServersDataSource: WebDavServersDataSource,
    private val backupWebDavDataSource: BackupWebDavDataSource,
    @ApplicationContext private val context: Context,
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /** 创建备份数据包 */
    suspend fun createBackup(): BackupData {
        val appPrefs = preferencesRepository.applicationPreferences.value
        val playerPrefs = preferencesRepository.playerPreferences.value
        val webDavServers = webDavServersDataSource.webDavServers.first()
        val openListConfig = readOpenListConfig()
        val backupConfig = backupWebDavDataSource.config.first()

        return BackupData(
            appPreferences = appPrefs,
            playerPreferences = playerPrefs,
            webDavServers = webDavServers,
            openListConfig = openListConfig,
            alipanConfig = readAlipanConfig(),
            cloud189Config = readCloud189Config(),
            pan123Config = readPan123Config(),
            quarkConfig = readQuarkConfig(),
            yun139Config = readYun139Config(),
            backupWebDavConfig = backupConfig,
        )
    }

    /** 从备份数据包恢复，ignoreList 中的配置项将被跳过 */
    suspend fun restoreFromBackup(backup: BackupData, ignoreList: Set<String> = emptySet()) {
        // 1. 恢复设置
        if ("app_preferences" !in ignoreList) {
            preferencesRepository.updateApplicationPreferences { backup.appPreferences }
        }
        if ("player_preferences" !in ignoreList) {
            preferencesRepository.updatePlayerPreferences { backup.playerPreferences }
        }

        // 2. 恢复 WebDAV 服务器
        if ("webdav_servers" !in ignoreList) {
            webDavServersDataSource.update { backup.webDavServers }
        }

        // 3. 恢复 OpenList 配置
        if ("openlist_config" !in ignoreList) {
            backup.openListConfig?.let { config ->
                restoreOpenListConfig(config)
            }
        }

        // 4. 恢复云盘凭证
        if ("cloud_credentials" !in ignoreList) {
            backup.alipanConfig?.let { restoreAlipanConfig(it) }
            backup.cloud189Config?.let { restoreCloud189Config(it) }
            backup.pan123Config?.let { restorePan123Config(it) }
            backup.quarkConfig?.let { restoreQuarkConfig(it) }
            backup.yun139Config?.let { restoreYun139Config(it) }
        }

        // 5. 恢复备份设置（WebDAV 配置、路径等）
        if ("backup_settings" !in ignoreList) {
            backup.backupWebDavConfig?.let { config ->
                backupWebDavDataSource.update { config }
            }
        }
    }

    // ==================== 打包工具 ====================

    /** 将备份数据打包为 ZIP 字节数组 */
    private suspend fun zipBackupData(backup: BackupData): ByteArray {
        val jsonString = json.encodeToString(BackupData.serializer(), backup)
        return ByteArrayOutputStream().use { baos ->
            BufferedOutputStream(baos).use { bos ->
                ZipOutputStream(bos).use { zip ->
                    zip.putNextEntry(ZipEntry("backup.json"))
                    zip.write(jsonString.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    val dataDir = context.filesDir.resolve("openlist_data")
                    if (dataDir.exists()) {
                        dataDir.listFiles()?.forEach { file ->
                            if (file.isFile && file.length() > 0) {
                                zip.putNextEntry(ZipEntry("openlist_data/${file.name}"))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                    }
                }
            }
            baos.toByteArray()
        }
    }

    // ==================== WebDAV 云备份 ====================

    /** 上传备份到 WebDAV 服务器（使用配置中的子文件夹路径，文件名带时间戳） */
    suspend fun uploadToCloud(
        config: BackupWebDavConfig,
    ): Result<Unit> {
        val backup = createBackup()
        val zipBytes = zipBackupData(backup)
        val fileName = config.generateBackupFileName()
        val path = config.remoteBackupPath(fileName)
        return webDavRepository.uploadFile(
            baseUrl = config.url,
            path = path,
            authHeader = config.basicAuthHeader,
            data = zipBytes,
        ).also { result ->
            // 上传成功后，如果启用 keepOnlyLatestBackup，删除旧备份
            if (result.isSuccess && config.keepOnlyLatestBackup) {
                val listResult = listRemoteBackupFiles(config)
                listResult.onSuccess { files ->
                    files.filter { it.path != path }
                        .forEach { oldFile ->
                            webDavRepository.delete(
                                baseUrl = config.url,
                                path = oldFile.path,
                                authHeader = config.basicAuthHeader,
                            )
                        }
                }
            }
        }
    }

    /** 从 WebDAV 服务器下载并恢复备份（指定远程路径） */
    suspend fun downloadFromCloud(
        config: BackupWebDavConfig,
        path: String,
    ): Result<BackupData> {
        val result = webDavRepository.downloadFile(
            baseUrl = config.url,
            path = path,
            authHeader = config.basicAuthHeader,
        )
        return result.map { zipBytes ->
            BufferedInputStream(ByteArrayInputStream(zipBytes)).use { bis ->
                parseFromZip(bis)
            }
        }
    }

    /** 清理本地旧备份文件，仅保留最新的一个 */
    fun cleanupLocalBackups(config: BackupWebDavConfig) {
        if (!config.keepOnlyLatestBackup || config.backupPath.isBlank()) return
        val dir = File(config.backupPath)
        if (!dir.isDirectory) return
        val backupFiles = dir.listFiles { file ->
            file.isFile && file.name.startsWith("fluxplayer_backup") && file.name.endsWith(".zip")
        }?.sortedByDescending { it.lastModified() } ?: return
        if (backupFiles.size <= 1) return
        backupFiles.drop(1).forEach { it.delete() }
    }

    /** 列出 WebDAV 服务器上的备份文件（仅 .zip 文件） */
    suspend fun listRemoteBackupFiles(config: BackupWebDavConfig): Result<List<WebDavResource>> {
        val dir = config.remoteBackupDir()
        return webDavRepository.listDirectory(
            baseUrl = config.url,
            path = dir,
            authHeader = config.basicAuthHeader,
        ).map { resources ->
            resources.filter { !it.isDirectory && it.name.endsWith(".zip", ignoreCase = true) }
                .sortedByDescending { it.lastModified }
        }
    }

    // ==================== ZIP 导出/导入 ====================

    /** 将备份数据 + OpenList 数据打包为 ZIP 并写入 URI */
    suspend fun exportToUri(backup: BackupData, uri: Uri) {
        val zipBytes = zipBackupData(backup)
        context.contentResolver.openOutputStream(uri)?.use { os ->
            BufferedOutputStream(os).use { bos ->
                bos.write(zipBytes)
            }
        }
    }

    /** 从 ZIP 或旧 JSON 文件导入：解析 backup.json，提取 openlist_data 文件 */
    suspend fun importFromUri(uri: Uri): Result<BackupData> = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedInputStream(input).use { bis ->
                // 先尝试按 ZIP 格式解析
                try {
                    parseFromZip(bis)
                } catch (e: Exception) {
                    // ZIP 失败则回退到旧版 JSON 文件读取
                    val jsonBytes = bis.readBytes()
                    json.decodeFromString(
                        BackupData.serializer(),
                        String(jsonBytes, Charsets.UTF_8)
                    )
                }
            }
        } ?: throw IllegalStateException("无法打开备份文件")
    }

    private fun parseFromZip(bis: BufferedInputStream): BackupData {
        var backupData: BackupData? = null

        ZipInputStream(bis).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == "backup.json" -> {
                        val jsonBytes = ByteArrayOutputStream().use { baos ->
                            zip.copyTo(baos)
                            baos.toByteArray()
                        }
                        backupData = json.decodeFromString(
                            BackupData.serializer(),
                            String(jsonBytes, Charsets.UTF_8)
                        )
                    }
                    entry.name.startsWith("openlist_data/") -> {
                        val fileName = entry.name.removePrefix("openlist_data/").trim('/')
                        // Zip Slip 防护：拒绝路径分隔符与 ".."，并用 canonicalPath 兜底
                        if (fileName.isNotBlank() &&
                            !fileName.contains("..") &&
                            !fileName.contains('\\') &&
                            !fileName.startsWith('/')
                        ) {
                            val destDir = context.filesDir.resolve("openlist_data")
                                .apply { mkdirs() }
                            val destFile = File(destDir, fileName)
                            // 解析后路径必须仍位于目标目录内，防 "....//" 等变体绕过
                            val canonicalDest = destFile.canonicalPath
                            val canonicalDir = destDir.canonicalPath
                            if (canonicalDest == canonicalDir ||
                                canonicalDest.startsWith(canonicalDir + File.separator)
                            ) {
                                destFile.outputStream().use { zip.copyTo(it) }
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        return backupData ?: throw IllegalStateException("无法在备份文件中找到 backup.json")
    }

    // ==================== OpenList 配置 ====================

    private fun readOpenListConfig(): OpenListBackupConfig? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean(PREF_AUTO_START, false)
        val password = prefs.getString(PREF_PASSWORD, null)
        return if (password != null || autoStart) {
            OpenListBackupConfig(
                autoStart = autoStart,
                adminPassword = password,
            )
        } else null
    }

    private fun restoreOpenListConfig(config: OpenListBackupConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(PREF_AUTO_START, config.autoStart)
            .apply()
        if (config.adminPassword != null) {
            prefs.edit()
                .putString(PREF_PASSWORD, config.adminPassword)
                .apply()
        }
    }

    // ==================== 阿里云盘 ====================

    private fun readAlipanConfig(): AlipanBackupConfig? {
        val prefs = context.getSharedPreferences(ALIPAN_PREFS, Context.MODE_PRIVATE)
        val auth = prefs.getString("authorization", null)
        if (auth.isNullOrBlank()) return null
        // 读取 WebView Cookie（用于备份后恢复时自动续期）
        val cookies = try {
            android.webkit.CookieManager.getInstance().getCookie("https://www.alipan.com")
        } catch (_: Exception) {
            prefs.getString("cookies", null)
        }
        return AlipanBackupConfig(
            authorization = auth,
            driveId = prefs.getString("drive_id", null),
            refreshToken = prefs.getString("refresh_token", null),
            deviceId = prefs.getString("device_id", null),
            signature = prefs.getString("signature", null),
            cookies = cookies,
        )
    }

    private fun restoreAlipanConfig(config: AlipanBackupConfig) {
        val prefs = context.getSharedPreferences(ALIPAN_PREFS, Context.MODE_PRIVATE)
        prefs.edit().apply {
            config.authorization?.let { putString("authorization", it) }
            config.driveId?.let { putString("drive_id", it) }
            config.refreshToken?.let { putString("refresh_token", it) }
            config.deviceId?.let { putString("device_id", it) }
            config.signature?.let { putString("signature", it) }
            config.cookies?.let { putString("cookies", it) }
        }.apply()
        // 恢复 WebView Cookie，以便 token 过期时通过 WebView 会话自动续期获取全权限 token
        if (!config.cookies.isNullOrBlank()) {
            try {
                val cookieManager = android.webkit.CookieManager.getInstance()
                cookieManager.setCookie("https://www.alipan.com", config.cookies)
                cookieManager.flush()
            } catch (_: Exception) {}
        }
        // 同步内存中的 AuthProvider
        if (!config.authorization.isNullOrBlank()) {
            AliyunAuthProvider.authorization = config.authorization ?: ""
            AliyunAuthProvider.isActive = true
        }
    }

    // ==================== 天翼云盘 ====================

    private fun readCloud189Config(): Cloud189BackupConfig? {
        val prefs = context.getSharedPreferences(CLOUD189_PREFS, Context.MODE_PRIVATE)
        val accessToken = prefs.getString("accessToken", null)
        if (accessToken.isNullOrBlank()) return null
        return Cloud189BackupConfig(
            accessToken = accessToken,
            sessionKey = prefs.getString("sessionKey", null),
            sessionSecret = prefs.getString("sessionSecret", null),
            refreshToken = prefs.getString("refreshToken", null),
            expiresIn = prefs.getLong("expiresIn", 0),
            cookies = prefs.getString("cookies", null),
        )
    }

    private fun restoreCloud189Config(config: Cloud189BackupConfig) {
        val prefs = context.getSharedPreferences(CLOUD189_PREFS, Context.MODE_PRIVATE)
        prefs.edit().apply {
            config.accessToken?.let { putString("accessToken", it) }
            config.sessionKey?.let { putString("sessionKey", it) }
            config.sessionSecret?.let { putString("sessionSecret", it) }
            config.refreshToken?.let { putString("refreshToken", it) }
            putLong("expiresIn", config.expiresIn)
            config.cookies?.let { putString("cookies", it) }
        }.apply()
        if (!config.accessToken.isNullOrBlank()) {
            C189AuthProvider.accessToken = config.accessToken ?: ""
            C189AuthProvider.sessionKey = config.sessionKey ?: ""
            C189AuthProvider.sessionSecret = config.sessionSecret ?: ""
            C189AuthProvider.refreshToken = config.refreshToken ?: ""
            C189AuthProvider.expiresIn = config.expiresIn
            C189AuthProvider.isActive = true
        }
    }

    // ==================== 123 云盘 ====================

    private fun readPan123Config(): Pan123BackupConfig? {
        val prefs = context.getSharedPreferences(PAN123_PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString("token", null)
        if (token.isNullOrBlank()) return null
        return Pan123BackupConfig(
            passport = prefs.getString("passport", null),
            password = prefs.getString("password", null),
            token = token,
        )
    }

    private fun restorePan123Config(config: Pan123BackupConfig) {
        val prefs = context.getSharedPreferences(PAN123_PREFS, Context.MODE_PRIVATE)
        prefs.edit().apply {
            config.passport?.let { putString("passport", it) }
            config.password?.let { putString("password", it) }
            config.token?.let { putString("token", it) }
        }.apply()
    }

    // ==================== 夸克网盘（含 UC） ====================

    private fun readQuarkConfig(): QuarkBackupConfig? {
        val quarkPrefs = context.getSharedPreferences(QUARK_PREFS, Context.MODE_PRIVATE)
        val ucPrefs = context.getSharedPreferences(UC_PREFS, Context.MODE_PRIVATE)
        val quarkCookie = quarkPrefs.getString("cookie", null)
        val ucCookie = ucPrefs.getString("cookie", null)
        if (quarkCookie.isNullOrBlank() && ucCookie.isNullOrBlank()) return null
        return QuarkBackupConfig(quarkCookie = quarkCookie, ucCookie = ucCookie)
    }

    private fun restoreQuarkConfig(config: QuarkBackupConfig) {
        config.quarkCookie?.let { cookie ->
            context.getSharedPreferences(QUARK_PREFS, Context.MODE_PRIVATE)
                .edit().putString("cookie", cookie).apply()
        }
        config.ucCookie?.let { cookie ->
            context.getSharedPreferences(UC_PREFS, Context.MODE_PRIVATE)
                .edit().putString("cookie", cookie).apply()
        }
    }

    // ==================== Yun139 ====================

    private fun readYun139Config(): Yun139BackupConfig? {
        val prefs = context.getSharedPreferences(YUN139_PREFS, Context.MODE_PRIVATE)
        val auth = prefs.getString("authorization", null)
        if (auth.isNullOrBlank()) return null
        return Yun139BackupConfig(
            authorization = auth,
            phoneNumber = prefs.getString("phoneNumber", null),
            userDomainId = prefs.getString("userDomainId", null),
            lastRefresh = prefs.getLong("lastRefresh", 0),
        )
    }

    private fun restoreYun139Config(config: Yun139BackupConfig) {
        val prefs = context.getSharedPreferences(YUN139_PREFS, Context.MODE_PRIVATE)
        prefs.edit().apply {
            config.authorization?.let { putString("authorization", it) }
            config.phoneNumber?.let { putString("phoneNumber", it) }
            config.userDomainId?.let { putString("userDomainId", it) }
            putLong("lastRefresh", config.lastRefresh)
        }.apply()
        if (!config.authorization.isNullOrBlank()) {
            Yun139AuthProvider.authorization = config.authorization ?: ""
            Yun139AuthProvider.phoneNumber = config.phoneNumber ?: ""
            Yun139AuthProvider.userDomainId = config.userDomainId ?: ""
            Yun139AuthProvider.isActive = true
        }
    }

    companion object {
        private const val PREFS_NAME = "openlist_prefs"
        private const val PREF_PASSWORD = "admin_password"
        private const val PREF_AUTO_START = "openlist_auto_start"

        private const val ALIPAN_PREFS = "alipan"
        private const val CLOUD189_PREFS = "cloud189"
        private const val PAN123_PREFS = "pan123"
        private const val QUARK_PREFS = "quark"
        private const val UC_PREFS = "uc"
        private const val YUN139_PREFS = "yun139"
    }
}
