package com.fluxplayer.app.settings.screens.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.fluxplayer.app.core.data.backup.BackupManager
import com.fluxplayer.app.core.data.webdav.WebDavClient
import com.fluxplayer.app.core.datastore.datasource.BackupWebDavDataSource
import com.fluxplayer.app.core.datastore.datasource.WebDavServersDataSource
import com.fluxplayer.app.core.model.BackupWebDavConfig
import com.fluxplayer.app.core.model.WebDavResource
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupManager: BackupManager,
    private val backupWebDavDataSource: BackupWebDavDataSource,
    private val webDavClient: WebDavClient,
    private val webDavServersDataSource: WebDavServersDataSource,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    val config: StateFlow<BackupWebDavConfig> = backupWebDavDataSource.config
        .stateIn(viewModelScope, SharingStarted.Eagerly, BackupWebDavConfig())

    /** 服务器端备份文件列表 */
    private val _remoteFiles = MutableStateFlow<List<WebDavResource>>(emptyList())
    val remoteFiles: StateFlow<List<WebDavResource>> = _remoteFiles.asStateFlow()

    // ==================== WebDAV 配置操作 ====================

    /** 更新整个配置 */
    fun updateConfig(config: BackupWebDavConfig) {
        viewModelScope.launch {
            backupWebDavDataSource.update { config }
        }
    }

    /** 更新单个字段 */
    fun updateConfigField(transform: (BackupWebDavConfig) -> BackupWebDavConfig) {
        viewModelScope.launch {
            backupWebDavDataSource.update(transform)
        }
    }

    /** 从现有 WebDAV 服务器列表复制配置 */
    fun copyFromWebDavServer() {
        viewModelScope.launch {
            val servers = webDavServersDataSource.webDavServers.first()
            val active = servers.activeServers.firstOrNull()
            if (active != null) {
                backupWebDavDataSource.update { current ->
                    current.copy(
                        url = active.url,
                        username = active.username,
                        password = active.password,
                    )
                }
                _uiState.value = BackupUiState(successMessage = "已复制 WebDAV 服务器配置")
            } else {
                _uiState.value = BackupUiState(errorMessage = "暂无已配置的 WebDAV 服务器")
            }
        }
    }

    // ==================== 测试连接 ====================

    fun testConnection() {
        viewModelScope.launch {
            val c = config.first()
            if (c.url.isBlank()) {
                _uiState.value = BackupUiState(errorMessage = "请先填写服务器地址")
                return@launch
            }
            _uiState.value = BackupUiState(connectionTesting = true)
            try {
                val result = webDavClient.testConnection(c.url, c.username, c.password)
                result.fold(
                    onSuccess = {
                        _uiState.value = BackupUiState(
                            connectionSuccess = true,
                            connectionMessage = "连接成功",
                        )
                    },
                    onFailure = { e ->
                        _uiState.value = BackupUiState(
                            connectionSuccess = false,
                            connectionMessage = "连接失败: ${e.message}",
                        )
                    },
                )
            } catch (e: Exception) {
                _uiState.value = BackupUiState(
                    connectionSuccess = false,
                    connectionMessage = "连接失败: ${e.message}",
                )
            }
        }
    }

    // ==================== 远程文件操作 ====================

    /** 刷新服务器端备份文件列表 */
    fun listRemoteFiles() {
        viewModelScope.launch {
            val c = config.first()
            if (c.url.isBlank()) {
                _uiState.value = BackupUiState(errorMessage = "请先配置 WebDAV 服务器")
                return@launch
            }
            _uiState.value = BackupUiState(listLoading = true)
            try {
                val result = backupManager.listRemoteBackupFiles(c)
                result.fold(
                    onSuccess = { files ->
                        _remoteFiles.value = files
                        _uiState.value = BackupUiState(
                            listLoading = false,
                            lastListTime = System.currentTimeMillis(),
                        )
                    },
                    onFailure = { e ->
                        _remoteFiles.value = emptyList()
                        _uiState.value = BackupUiState(
                            listLoading = false,
                            errorMessage = "刷新文件列表失败: ${e.message}",
                        )
                    },
                )
            } catch (e: Exception) {
                _remoteFiles.value = emptyList()
                _uiState.value = BackupUiState(
                    listLoading = false,
                    errorMessage = "刷新文件列表失败: ${e.message}",
                )
            }
        }
    }

    /** 从服务器端选择文件并恢复 */
    fun restoreFromRemoteFile(path: String) {
        viewModelScope.launch {
            val c = config.first()
            _uiState.value = BackupUiState(isLoading = true)
            try {
                val result = backupManager.downloadFromCloud(c, path)
                result.fold(
                    onSuccess = { backup ->
                        val ignoreList = c.restoreIgnoreList.toSet()
                        backupManager.restoreFromBackup(backup, ignoreList)
                        _uiState.value = _uiState.value.copy(restoreCompleted = true, isLoading = false)
                    },
                    onFailure = { e ->
                        _uiState.value = BackupUiState(errorMessage = "恢复失败: ${e.message}")
                    },
                )
            } catch (e: Exception) {
                _uiState.value = BackupUiState(errorMessage = "恢复失败: ${e.message}")
            }
        }
    }

    // ==================== 备份操作 ====================

    /** 创建备份并导出到 URI（本地文件） */
    fun createBackup(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = BackupUiState(isLoading = true)
            try {
                val backup = backupManager.createBackup()
                backupManager.exportToUri(backup, uri)
                _uiState.value = BackupUiState(successMessage = "备份成功")
            } catch (e: Exception) {
                _uiState.value = BackupUiState(errorMessage = "备份失败: ${e.message}")
            }
        }
    }

    /** 从本地 URI 恢复备份 */
    fun restoreBackup(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = BackupUiState(isLoading = true)
            try {
                val result = backupManager.importFromUri(uri)
                result.fold(
                    onSuccess = { backup ->
                        val c = config.first()
                        backupManager.restoreFromBackup(backup, c.restoreIgnoreList.toSet())
                        _uiState.value = _uiState.value.copy(restoreCompleted = true, isLoading = false)
                    },
                    onFailure = { e ->
                        _uiState.value = BackupUiState(errorMessage = "恢复失败: ${e.message}")
                    },
                )
            } catch (e: Exception) {
                _uiState.value = BackupUiState(errorMessage = "恢复失败: ${e.message}")
            }
        }
    }

    /** 执行完整备份（本地 + WebDAV） */
    fun performFullBackup() {
        viewModelScope.launch {
            val c = config.first()
            _uiState.value = BackupUiState(isLoading = true)
            val errors = mutableListOf<String>()

            // 先本地备份
            try {
                val backup = backupManager.createBackup()
                if (c.backupPath.isNotBlank()) {
                    // 写入指定路径
                    val file = java.io.File(c.backupPath, "fluxplayer_backup.zip")
                    file.parentFile?.mkdirs()
                    backupManager.exportToUri(backup, Uri.fromFile(file))
                } else {
                    // 路径为空时提示
                    errors.add("未设置本地备份路径，已跳过本地备份")
                }
            } catch (e: Exception) {
                errors.add("本地备份失败: ${e.message}")
            }

            // WebDAV 备份
            if (c.url.isNotBlank()) {
                try {
                    val result = backupManager.uploadToCloud(c)
                    result.fold(
                        onSuccess = {
                            _uiState.value = BackupUiState(
                                successMessage = "本地与 WebDAV 备份完成",
                            )
                        },
                        onFailure = { e ->
                            errors.add("WebDAV 备份失败: ${e.message}")
                        },
                    )
                } catch (e: Exception) {
                    errors.add("WebDAV 备份失败: ${e.message}")
                }
            }

            if (errors.isNotEmpty()) {
                _uiState.value = BackupUiState(
                    errorMessage = errors.joinToString("\n"),
                )
            } else if (_uiState.value.successMessage == null) {
                _uiState.value = BackupUiState(successMessage = "备份成功")
            }
        }
    }

    /** 上传到 WebDAV 并验证 */
    fun uploadToCloud() {
        viewModelScope.launch {
            val c = config.first()
            if (c.url.isBlank()) {
                _uiState.value = BackupUiState(errorMessage = "请先配置 WebDAV 服务器")
                return@launch
            }
            _uiState.value = BackupUiState(isLoading = true)
            try {
                val result = backupManager.uploadToCloud(c)
                result.fold(
                    onSuccess = {
                        // 上传后验证文件是否存在
                        val path = c.remoteBackupPath()
                        val listResult = backupManager.listRemoteBackupFiles(c)
                        listResult.fold(
                            onSuccess = { files ->
                                val uploaded = files.any { it.path == path }
                                if (uploaded) {
                                    _remoteFiles.value = files
                                    _uiState.value = BackupUiState(
                                        successMessage = "上传到 WebDAV 成功并已验证",
                                    )
                                } else {
                                    _uiState.value = BackupUiState(
                                        successMessage = "上传报告成功，但服务器上未找到文件，请刷新列表确认",
                                    )
                                }
                            },
                            onFailure = {
                                _uiState.value = BackupUiState(
                                    successMessage = "上传到 WebDAV 成功（验证列表失败，请手动刷新）",
                                )
                            },
                        )
                    },
                    onFailure = { e ->
                        _uiState.value = BackupUiState(errorMessage = "上传失败: ${e.message}")
                    },
                )
            } catch (e: Exception) {
                _uiState.value = BackupUiState(errorMessage = "上传失败: ${e.message}")
            }
        }
    }

    // ==================== 工具方法 ====================

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(
            successMessage = null,
            errorMessage = null,
        )
    }

    fun clearConnectionState() {
        _uiState.value = _uiState.value.copy(
            connectionSuccess = null,
            connectionMessage = null,
            connectionTesting = false,
        )
    }

    fun clearRestoreCompleted() {
        _uiState.value = _uiState.value.copy(restoreCompleted = false)
    }

    /** 重启应用 */
    fun restartApp() {
        val intent = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
        val componentName = intent?.component
        val restartIntent = Intent.makeRestartActivityTask(componentName)
        appContext.startActivity(restartIntent)
        Runtime.getRuntime().exit(0)
    }
}

data class BackupUiState(
    val isLoading: Boolean = false,
    val successMessage: String? = null,
    val errorMessage: String? = null,
    val listLoading: Boolean = false,
    val lastListTime: Long = 0L,
    val connectionTesting: Boolean = false,
    val connectionSuccess: Boolean? = null,
    val connectionMessage: String? = null,
    val restoreCompleted: Boolean = false,
)
