package com.fluxplayer.app.core.data.backup

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.fluxplayer.app.core.datastore.datasource.BackupWebDavDataSource
import com.fluxplayer.app.core.model.BackupWebDavConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 仅在用户启用后自动备份，间隔与触发时机由备份设置决定。 */
@Singleton
class AutoBackupHelper @Inject constructor(
    private val backupManager: BackupManager,
    private val backupWebDavDataSource: BackupWebDavDataSource,
    @ApplicationContext private val context: Context,
) {
    private val backupMutex = Mutex()

    /**
     * @param onLaunch 由应用启动触发，只在触发时机包含启动时执行；
     *                 为 false 时表示退到后台，只在触发时机包含退后台时执行。
     */
    suspend fun performAutoBackup(onLaunch: Boolean = false) {
        backupMutex.withLock {
            val config = backupWebDavDataSource.config.first()
            if (!config.autoBackupEnabled) return
            if (!BackupWebDavConfig.shouldRunOnTrigger(config.autoBackupTrigger, onLaunch)) return
            // 两个目标各自校验可用性：只跑已配置好的那个，未配置的不阻塞另一个。
            val runLocal = config.autoBackupToLocal && config.localDirectoryUri.isNotBlank()
            val runRemote = config.autoBackupToRemote && config.isConfigured
            if (!runLocal && !runRemote) return
            val now = System.currentTimeMillis()
            if (now - config.lastAutoBackupAt < config.effectiveAutoBackupIntervalMs()) return
            // 先记录时刻，失败的备份同样受间隔约束，避免故障时反复重试拖住后台任务。
            backupWebDavDataSource.updateOrThrow { it.copy(lastAutoBackupAt = now) }
            val keep = config.effectiveAutoBackupRetentionCount
            if (runLocal) {
                val directory = android.net.Uri.parse(config.localDirectoryUri)
                val result = backupResult { backupManager.exportToDirectory(directory, config.generateBackupFileName()) }
                result.onSuccess { showSuccess(it.successMessage("本地自动备份成功")) }
                    .onFailure { Log.e(TAG, "本地自动备份失败", it) }
                // 只在备份成功后再清理，且清理失败不影响备份结果，仅留日志。
                result.getOrNull()?.let { exported ->
                    backupManager.pruneLocalBackups(directory, keep, exported.fileName)
                        .onFailure { Log.w(TAG, "清理本地旧备份失败", it) }
                }
            }
            if (runRemote) {
                val result = backupManager.uploadToCloud(config)
                result.onSuccess { showSuccess(it.successMessage("在线自动备份成功：${it.fileName}")) }
                    .onFailure { Log.e(TAG, "WebDAV 自动备份失败", it) }
                result.getOrNull()?.let { exported ->
                    backupManager.pruneRemoteBackups(config, keep, exported.fileName)
                        .onFailure { Log.w(TAG, "清理远端旧备份失败", it) }
                }
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
    }
}
