package com.fluxplayer.app.core.data.backup

import android.util.Log
import com.fluxplayer.app.core.datastore.datasource.BackupWebDavDataSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 仅在用户启用后自动上传，并按远程文件名识别尚未检查过的备份。 */
@Singleton
class AutoBackupHelper @Inject constructor(
    private val backupManager: BackupManager,
    private val backupWebDavDataSource: BackupWebDavDataSource,
) {
    private var lastAutoBackupAtMs = 0L
    private val backupMutex = Mutex()

    suspend fun performAutoBackup(force: Boolean = false) {
        backupMutex.withLock {
            val config = backupWebDavDataSource.config.first()
            if (!config.isConfigured || config.autoBackupSyncMode != "remote") return
            // 归档一律加密：没设备份密码就不上传，避免把凭证以明文推到 WebDAV。
            // 不静默降级为明文——那正是这次要堵的窟窿。
            if (config.backupPassphrase.isBlank()) {
                Log.w(TAG, "未设置备份密码，跳过自动备份（请在备份设置中配置）")
                return
            }
            val now = System.currentTimeMillis()
            if (!force && now - lastAutoBackupAtMs < MIN_AUTO_BACKUP_INTERVAL_MS) return
            lastAutoBackupAtMs = now
            backupManager.uploadToCloud(config).onFailure { Log.e(TAG, "WebDAV 自动备份失败", it) }
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
