package com.fluxplayer.app.settings.screens.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxplayer.app.core.data.backup.BackupArchive
import com.fluxplayer.app.core.data.backup.BackupDecryptException
import com.fluxplayer.app.core.data.backup.BackupManager
import com.fluxplayer.app.core.data.backup.BackupPassphraseRequiredException
import com.fluxplayer.app.core.data.webdav.WebDavClient
import com.fluxplayer.app.core.datastore.datasource.BackupWebDavDataSource
import com.fluxplayer.app.core.datastore.datasource.WebDavServersDataSource
import com.fluxplayer.app.core.model.BackupData
import com.fluxplayer.app.core.model.BackupWebDavConfig
import com.fluxplayer.app.core.model.WebDavResource
import com.fluxplayer.app.core.ui.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupManager: BackupManager,
    private val backupWebDavDataSource: BackupWebDavDataSource,
    private val webDavClient: WebDavClient,
    private val webDavServersDataSource: WebDavServersDataSource,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {
    private val state = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = state.asStateFlow()
    val config = backupWebDavDataSource.config.stateIn(viewModelScope, SharingStarted.Eagerly, BackupWebDavConfig())
    private var pendingArchive: BackupArchive? = null
    private var operation: Job? = null

    /** 所有页面操作串行执行，消息清理不会覆盖正在运行的状态。 */
    private fun execute(block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        state.update { it.copy(isLoading = true, successMessage = null, errorMessage = null, operationError = null) }
        operation = viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = appContext.getString(R.string.backup_operation_failed, error.message.orEmpty())
                state.update { it.copy(errorMessage = message, operationError = message) }
            } finally {
                state.update { it.copy(isLoading = false) }
            }
        }
    }

    fun saveConfig(draft: BackupWebDavConfig) {
        execute {
            require(draft.url.isBlank() || draft.isConfigured) { appContext.getString(R.string.backup_invalid_url) }
            val parts = draft.subfolder.trim('/').split('/')
            require(parts.none { it == "." || it == ".." || it.contains('\\') }) { appContext.getString(R.string.backup_invalid_folder) }
            backupWebDavDataSource.updateOrThrow { current ->
                draft.copy(
                    url = draft.url.trimEnd('/'),
                    hasPendingNewBackup = false,
                    lastSeenRemoteBackup = if (draft.url == current.url && draft.subfolder == current.subfolder) current.lastSeenRemoteBackup else "",
                )
            }
            state.update { it.copy(connectionMessage = null, remoteFiles = emptyList()) }
        }
    }

    fun updateConfigField(transform: (BackupWebDavConfig) -> BackupWebDavConfig) {
        execute { backupWebDavDataSource.updateOrThrow(transform) }
    }

    fun testConnection() {
        execute {
            val current = backupWebDavDataSource.config.first()
            require(current.isConfigured) { appContext.getString(R.string.backup_invalid_url) }
            check(webDavClient.testConnection(current.url, current.username, current.password).getOrThrow()) {
                appContext.getString(R.string.backup_connection_failed)
            }
            state.update { it.copy(connectionMessage = appContext.getString(R.string.backup_connection_success)) }
        }
    }

    /** 只读取供账号编辑器选择，不直接修改已保存的账号。 */
    fun loadServers() {
        execute {
            state.update { it.copy(servers = webDavServersDataSource.webDavServers.first().servers) }
        }
    }

    fun listRemoteFiles() {
        execute {
            state.update { it.copy(remoteFiles = emptyList()) }
            val current = backupWebDavDataSource.config.first()
            val files = backupManager.listRemoteBackupFiles(current).getOrThrow()
            state.update { it.copy(remoteFiles = files) }
            if (current.hasPendingNewBackup) backupWebDavDataSource.updateOrThrow { it.copy(hasPendingNewBackup = false) }
        }
    }

    fun createBackup(uri: Uri) {
        execute {
            val passphrase = currentPassphrase()
                ?: throw IllegalStateException(appContext.getString(R.string.backup_passphrase_required))
            try {
                backupManager.exportToUri(uri, passphrase)
            } finally {
                passphrase.fill('\u0000')
            }
            state.update { it.copy(successMessage = appContext.getString(R.string.backup_export_success)) }
        }
    }

    fun uploadToCloud() {
        execute {
            val current = backupWebDavDataSource.config.first()
            require(current.backupPassphrase.isNotBlank()) { appContext.getString(R.string.backup_passphrase_required) }
            val name = backupManager.uploadToCloud(current).getOrThrow()
            state.update { it.copy(successMessage = appContext.getString(R.string.backup_upload_success, name)) }
        }
    }

    fun restoreBackup(uri: Uri) {
        execute { runImport(PassphraseTarget.LocalFile(uri), currentPassphrase(), isRetry = false) }
    }

    fun restoreFromRemoteFile(path: String) {
        execute { runImport(PassphraseTarget.RemoteFile(path), currentPassphrase(), isRetry = false) }
    }

    /** 用户在导入密码框中提交密码后重试；密码错误时保留弹窗并就地提示。 */
    fun submitPassphrase(passphrase: String) {
        val target = state.value.pendingPassphrase ?: return
        if (passphrase.isBlank()) {
            state.update { it.copy(passphraseError = appContext.getString(R.string.backup_passphrase_required)) }
            return
        }
        execute {
            val chars = passphrase.toCharArray()
            try {
                runImport(target, chars, isRetry = true)
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    fun cancelPassphrase() {
        if (state.value.isLoading) return
        state.update { it.copy(pendingPassphrase = null, passphraseError = null) }
    }

    fun savePassphrase(value: String) {
        execute { backupWebDavDataSource.updateOrThrow { it.copy(backupPassphrase = value) } }
    }

    /** 未设置密码就点了导出/上传：直接提示，不启动文件选择器。 */
    fun reportMissingPassphrase() {
        state.update { it.copy(errorMessage = appContext.getString(R.string.backup_passphrase_required)) }
    }

    /**
     * 读取并解析备份。加密归档需要密码：
     * - 缺密码 → 弹密码框（[BackupPassphraseRequiredException]）
     * - 密码不对 → 弹密码框并显示错误（[BackupDecryptException]）
     * - 未加密的旧备份 → 直接解析
     */
    private suspend fun runImport(target: PassphraseTarget, passphrase: CharArray?, isRetry: Boolean) {
        val result = when (target) {
            is PassphraseTarget.LocalFile -> backupManager.importFromUri(target.uri, passphrase)
            is PassphraseTarget.RemoteFile ->
                backupManager.downloadFromCloud(backupWebDavDataSource.config.first(), target.path, passphrase)
        }
        result.fold(
            onSuccess = { archive ->
                state.update { it.copy(pendingPassphrase = null, passphraseError = null) }
                prepareRestore(archive)
            },
            onFailure = { error ->
                when (error) {
                    is BackupPassphraseRequiredException ->
                        state.update { it.copy(pendingPassphrase = target, passphraseError = null) }
                    // 首次用配置里的密码就失败，不报错（可能来自另一台设备）；只有用户自己输入的才算"密码错误"
                    is BackupDecryptException ->
                        state.update {
                            it.copy(
                                pendingPassphrase = target,
                                passphraseError = if (isRetry) appContext.getString(R.string.backup_passphrase_wrong) else null,
                            )
                        }
                    else -> throw error
                }
            },
        )
    }

    /** 配置里保存的备份密码；未设置返回 null。调用方负责用完清零。 */
    private fun currentPassphrase(): CharArray? =
        config.value.backupPassphrase.takeIf { it.isNotBlank() }?.toCharArray()

    private fun prepareRestore(archive: BackupArchive) {
        pendingArchive = archive
        state.update {
            it.copy(
                pendingBackup = archive.data,
                hasOpenListFiles = archive.openListFiles.isNotEmpty(),
                ignoredItems = config.value.restoreIgnoreList.toSet(),
            )
        }
    }

    fun toggleRestoreItem(key: String) {
        if (state.value.isLoading) return
        state.update { it.copy(ignoredItems = if (key in it.ignoredItems) it.ignoredItems - key else it.ignoredItems + key) }
    }

    fun cancelRestore() {
        if (state.value.isLoading) return
        pendingArchive = null
        state.update { it.copy(pendingBackup = null, hasOpenListFiles = false) }
    }

    fun confirmRestore() {
        val archive = pendingArchive ?: return
        val ignored = state.value.ignoredItems
        execute {
            try {
                backupManager.restoreFromBackup(archive, ignored)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = appContext.getString(R.string.backup_restore_failed_partial, error.message.orEmpty())
                state.update { it.copy(errorMessage = message, operationError = message) }
                return@execute
            }
            pendingArchive = null
            state.update { it.copy(pendingBackup = null, restoreCompleted = true) }
        }
    }

    fun clearMessages() {
        state.update { it.copy(successMessage = null, errorMessage = null) }
    }

    fun clearRestoreCompleted() {
        state.update { it.copy(restoreCompleted = false) }
    }

    fun clearPendingNewBackup() {
        updateConfigField { it.copy(hasPendingNewBackup = false) }
    }

    fun restartApp() {
        val component = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)?.component ?: return
        appContext.startActivity(Intent.makeRestartActivityTask(component))
        Runtime.getRuntime().exit(0)
    }
}

data class BackupUiState(
    val isLoading: Boolean = false,
    val successMessage: String? = null,
    val errorMessage: String? = null,
    val operationError: String? = null,
    val connectionMessage: String? = null,
    val remoteFiles: List<WebDavResource> = emptyList(),
    val servers: List<com.fluxplayer.app.core.model.WebDavServer> = emptyList(),
    val pendingBackup: BackupData? = null,
    val hasOpenListFiles: Boolean = false,
    val ignoredItems: Set<String> = emptySet(),
    val restoreCompleted: Boolean = false,
    /** 非空表示正在等用户输入导入密码；[passphraseError] 为就地错误提示。 */
    val pendingPassphrase: PassphraseTarget? = null,
    val passphraseError: String? = null,
)

/** 需要密码才能继续的导入目标。 */
sealed interface PassphraseTarget {
    data class LocalFile(val uri: Uri) : PassphraseTarget
    data class RemoteFile(val path: String) : PassphraseTarget
}
