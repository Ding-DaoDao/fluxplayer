package dev.anilbeesetti.nextplayer.settings.screens.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.anilbeesetti.nextplayer.core.data.backup.BackupManager
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupManager: BackupManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /** 创建备份并写入指定 URI */
    fun createBackup(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = BackupUiState(isLoading = true)
            try {
                val backup = backupManager.createBackup()
                backupManager.exportToUri(backup, uri)
                _uiState.value = BackupUiState(
                    successMessage = "备份成功",
                )
            } catch (e: Exception) {
                _uiState.value = BackupUiState(
                    errorMessage = "备份失败: ${e.message}",
                )
            }
        }
    }

    /** 从指定 URI 恢复备份 */
    fun restoreBackup(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = BackupUiState(isLoading = true)
            try {
                val result = backupManager.importFromUri(uri)
                result.fold(
                    onSuccess = { backup ->
                        backupManager.restoreFromBackup(backup)
                        _uiState.value = BackupUiState(
                            successMessage = "恢复成功，建议重启应用使设置生效",
                        )
                    },
                    onFailure = { e ->
                        _uiState.value = BackupUiState(
                            errorMessage = "恢复失败: ${e.message}",
                        )
                    },
                )
            } catch (e: Exception) {
                _uiState.value = BackupUiState(
                    errorMessage = "恢复失败: ${e.message}",
                )
            }
        }
    }

    fun clearMessages() {
        _uiState.value = BackupUiState()
    }
}

data class BackupUiState(
    val isLoading: Boolean = false,
    val successMessage: String? = null,
    val errorMessage: String? = null,
)
