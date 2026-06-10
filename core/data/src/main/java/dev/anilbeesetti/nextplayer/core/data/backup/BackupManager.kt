package dev.anilbeesetti.nextplayer.core.data.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.anilbeesetti.nextplayer.core.data.repository.PreferencesRepository
import dev.anilbeesetti.nextplayer.core.data.repository.WebDavRepository
import dev.anilbeesetti.nextplayer.core.datastore.datasource.WebDavServersDataSource
import dev.anilbeesetti.nextplayer.core.model.BackupData
import dev.anilbeesetti.nextplayer.core.model.OpenListBackupConfig
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

        return BackupData(
            appPreferences = appPrefs,
            playerPreferences = playerPrefs,
            webDavServers = webDavServers,
            openListConfig = openListConfig,
        )
    }

    /** 从备份数据包恢复 */
    suspend fun restoreFromBackup(backup: BackupData) {
        // 1. 恢复设置
        preferencesRepository.updateApplicationPreferences { backup.appPreferences }
        preferencesRepository.updatePlayerPreferences { backup.playerPreferences }

        // 2. 恢复 WebDAV 服务器
        webDavServersDataSource.update { backup.webDavServers }

        // 3. 恢复 OpenList 配置
        backup.openListConfig?.let { config ->
            restoreOpenListConfig(config)
        }
    }

    /** 将备份数据导出到文件 */
    suspend fun exportToUri(backup: BackupData, uri: Uri) {
        val jsonString = json.encodeToString(BackupData.serializer(), backup)
        context.contentResolver.openOutputStream(uri)?.use { os ->
            os.write(jsonString.toByteArray(Charsets.UTF_8))
        }
    }

    /** 从文件导入备份数据 */
    suspend fun importFromUri(uri: Uri): Result<BackupData> = runCatching {
        val jsonString = context.contentResolver.openInputStream(uri)?.use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw IllegalStateException("无法打开备份文件")
        json.decodeFromString(BackupData.serializer(), jsonString)
    }

    private fun readOpenListConfig(): OpenListBackupConfig? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean(PREF_AUTO_START, false)
        val password = prefs.getString(PREF_PASSWORD, null)
        // 只有在至少一项有值时才有意义
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

    companion object {
        private const val PREFS_NAME = "openlist_prefs"
        private const val PREF_PASSWORD = "admin_password"
        private const val PREF_AUTO_START = "openlist_auto_start"
    }
}
