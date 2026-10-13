package com.fluxplayer.app.settings.screens.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fluxplayer.app.core.data.backup.BackupArchive
import com.fluxplayer.app.core.data.backup.BackupManager
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
            backupManager.exportToUri(uri)
            state.update { it.copy(successMessage = appContext.getString(R.string.backup_export_success)) }
        }
    }

    fun uploadToCloud() {
        execute {
            val current = backupWebDavDataSource.config.first()
            val name = backupManager.uploadToCloud(current).getOrThrow()
            state.update { it.copy(successMessage = appContext.getString(R.string.backup_upload_success, name)) }
        }
    }

    fun restoreBackup(uri: Uri) {
        execute { prepareRestore(backupManager.importFromUri(uri).getOrThrow()) }
    }

    fun restoreFromRemoteFile(path: String) {
        execute {
            val current = backupWebDavDataSource.config.first()
            prepareRestore(backupManager.downloadFromCloud(current, path).getOrThrow())
        }
    }

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
)
