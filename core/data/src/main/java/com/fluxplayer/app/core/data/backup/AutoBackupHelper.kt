package com.fluxplayer.app.core.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import com.fluxplayer.app.core.datastore.datasource.BackupWebDavDataSource
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * 自动备份与检查助手，负责：
 * - 应用退到后台时根据 autoBackupSyncMode 自动执行备份
 * - 应用启动时检查 WebDAV 上是否有新备份
 */
@Singleton
class AutoBackupHelper @Inject constructor(
    private val backupManager: BackupManager,
    private val backupWebDavDataSource: BackupWebDavDataSource,
    @ApplicationContext private val context: Context,
) {

    /**
     * 根据 autoBackupSyncMode 执行自动备份（静默执行，静默记录日志）
     */
    suspend fun performAutoBackup() {
        val config = backupWebDavDataSource.config.first()
        if (!config.isConfigured) return

        val syncMode = config.autoBackupSyncMode
        Log.d(TAG, "执行自动备份，模式: $syncMode")

        val backup = backupManager.createBackup()

        when (syncMode) {
            "both" -> {
                localBackup(backup, config)
                cloudBackup(config)
            }
            "local" -> localBackup(backup, config)
            "remote" -> cloudBackup(config)
        }
    }

    private suspend fun localBackup(backup: com.fluxplayer.app.core.model.BackupData, config: com.fluxplayer.app.core.model.BackupWebDavConfig) {
        if (config.backupPath.isBlank()) {
            Log.d(TAG, "未设置本地备份路径，跳过本地备份")
            return
        }
        try {
            val fileName = config.generateBackupFileName()
            val file = File(config.backupPath, fileName)
            file.parentFile?.mkdirs()
            backupManager.exportToUri(backup, Uri.fromFile(file))
            backupManager.cleanupLocalBackups(config)
            Log.d(TAG, "本地自动备份完成: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "本地自动备份失败", e)
        }
    }

    private suspend fun cloudBackup(config: com.fluxplayer.app.core.model.BackupWebDavConfig) {
        if (config.url.isBlank()) return
        try {
            backupManager.uploadToCloud(config)
                .onSuccess { Log.d(TAG, "云端自动备份完成") }
                .onFailure { e -> Log.e(TAG, "云端自动备份失败", e) }
        } catch (e: Exception) {
            Log.e(TAG, "云端自动备份异常", e)
        }
    }

    /**
     * 检查 WebDAV 服务器上是否有新备份文件。
     * @return true 表示可能存在新备份（服务器上有文件且与本地记录不同）
     */
    suspend fun checkNewBackupAvailable(): Boolean {
        val config = backupWebDavDataSource.config.first()
        if (!config.isConfigured || !config.autoCheckNewBackup) return false

        return try {
            val result = backupManager.listRemoteBackupFiles(config)
            result.fold(
                onSuccess = { files ->
                    val hasFiles = files.isNotEmpty()
                    if (hasFiles) {
                        Log.d(TAG, "检测到 ${files.size} 个远程备份文件")
                        backupWebDavDataSource.update { it.copy(hasPendingNewBackup = true) }
                    }
                    hasFiles
                },
                onFailure = {
                    Log.e(TAG, "检查远程备份失败", it)
                    false
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "检查远程备份异常", e)
            false
        }
    }

    companion object {
        private const val TAG = "AutoBackupHelper"
    }
}
