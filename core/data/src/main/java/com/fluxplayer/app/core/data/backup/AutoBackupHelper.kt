package com.fluxplayer.app.core.data.backup

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.fluxplayer.app.core.datastore.datasource.BackupWebDavDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 仅在用户启用后自动备份，并按远程文件名识别尚未检查过的备份。 */
@Singleton
class AutoBackupHelper @Inject constructor(
    private val backupManager: BackupManager,
    private val backupWebDavDataSource: BackupWebDavDataSource,
    @ApplicationContext private val context: Context,
) {
    private var lastAutoBackupAtMs = 0L
    private val backupMutex = Mutex()

    suspend fun performAutoBackup(force: Boolean = false) {
        backupMutex.withLock {
            val config = backupWebDavDataSource.config.first()
            if (config.autoBackupSyncMode == "off") return
            if (config.autoBackupSyncMode == "remote" && !config.isConfigured) return
            if (config.autoBackupSyncMode == "local" && config.localDirectoryUri.isBlank()) return
            val now = System.currentTimeMillis()
            if (!force && now - lastAutoBackupAtMs < MIN_AUTO_BACKUP_INTERVAL_MS) return
            lastAutoBackupAtMs = now
            if (config.autoBackupSyncMode == "local") {
                backupResult { backupManager.exportToDirectory(android.net.Uri.parse(config.localDirectoryUri), config.generateBackupFileName()) }
                    .onSuccess { showSuccess(it.successMessage("本地自动备份成功")) }
                    .onFailure { Log.e(TAG, "本地自动备份失败", it) }
            } else {
                backupManager.uploadToCloud(config)
                    .onSuccess { showSuccess(it.successMessage("在线自动备份成功：${it.fileName}")) }
                    .onFailure { Log.e(TAG, "WebDAV 自动备份失败", it) }
            }
        }
    }

    private suspend fun showSuccess(message: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    suspend fun checkNewBackupAvailable(): Boolean {
        val config = backupWebDavDataSource.config.first()
        if (!config.isConfigured || !config.autoCheckNewBackup) return false
        return backupResult {
            backupManager.listRemoteBackupFiles(config).getOrThrow().let { files ->
                val latest = files.firstOrNull()?.name ?: return@let false
                if (latest == config.lastSeenRemoteBackup) return@let false
                backupWebDavDataSource.updateOrThrow {
                    it.copy(lastSeenRemoteBackup = latest, hasPendingNewBackup = true)
                }
                true
            }
        }.getOrElse {
            Log.e(TAG, "检查 WebDAV 备份失败", it)
            false
        }
    }

    companion object {
        private const val TAG = "AutoBackupHelper"
        private const val MIN_AUTO_BACKUP_INTERVAL_MS = 5 * 60 * 1000L
    }
}
