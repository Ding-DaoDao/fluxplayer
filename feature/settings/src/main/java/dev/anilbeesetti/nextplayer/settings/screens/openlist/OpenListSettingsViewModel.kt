package dev.anilbeesetti.nextplayer.settings.screens.openlist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.data.openlist.OpenListManagerProvider
import dev.anilbeesetti.nextplayer.core.data.openlist.OpenListService
import dev.anilbeesetti.nextplayer.core.data.openlist.OpenListServerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OpenListSettingsUiState(
    val serverState: OpenListServerState = OpenListServerState.Stopped,
    val password: String? = null,
    val isAutoStart: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class OpenListSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val manager = OpenListManagerProvider.get()
        ?: error("OpenListManager not initialized — call OpenListManagerProvider.init() in Application.onCreate()")
    private val _uiState = MutableStateFlow(OpenListSettingsUiState(
        password = manager.adminSetPassword.value ?: manager.initialPassword.value,
        isAutoStart = manager.getAutoStart(),
    ))
    val uiState: StateFlow<OpenListSettingsUiState> = _uiState.asStateFlow()

    init {
        // 监听服务器状态
        viewModelScope.launch {
            manager.state.collect { state ->
                _uiState.update { it.copy(serverState = state) }
            }
        }
        // 监听密码
        viewModelScope.launch {
            manager.adminSetPassword.collect { pwd ->
                _uiState.update { it.copy(password = pwd) }
            }
        }
        // 存储相关已移除
    }

    fun startService() {
        OpenListService.start(getApplication<Application>())
    }

    fun stopService() {
        manager.stop()
        OpenListService.stop(getApplication<Application>())
    }

    fun setAutoStart(enabled: Boolean) {
        manager.setAutoStart(enabled)
        _uiState.update { it.copy(isAutoStart = enabled) }
    }

    fun changePasswordViaCli(newPassword: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val ok = manager.setPasswordViaCli(newPassword)
            if (!ok) {
                _uiState.update { it.copy(error = "修改密码失败") }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun randomPasswordViaCli() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            val pwd = manager.randomPasswordViaCli()
            if (pwd == null) {
                _uiState.update { it.copy(error = "重置密码失败") }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
