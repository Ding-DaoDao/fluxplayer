package com.fluxplayer.app.settings.screens.webdav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import com.fluxplayer.app.core.data.repository.WebDavRepository
import com.fluxplayer.app.core.model.WebDavServer
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WebDavSettingsUiState(
    val servers: List<WebDavServer> = emptyList(),
    val activeServerIds: Set<String> = emptySet(),
    val showAddDialog: Boolean = false,
    val showEditDialog: WebDavServer? = null,
    val showDeleteConfirmation: WebDavServer? = null,
    val testingServer: Boolean = false,
    val testResult: String? = null,
    val editingServer: WebDavServer = WebDavServer(name = "", url = "", username = "", password = ""),
)

@HiltViewModel
class WebDavSettingsViewModel @Inject constructor(
    private val webDavRepository: WebDavRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WebDavSettingsUiState())
    val uiState: StateFlow<WebDavSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            webDavRepository.servers.collect { servers ->
                _uiState.update {
                    it.copy(servers = servers)
                }
            }
        }
        viewModelScope.launch {
            webDavRepository.activeServers.collect { active ->
                _uiState.update {
                    it.copy(activeServerIds = active.map { it.id }.toSet())
                }
            }
        }
    }

    fun showAddDialog() {
        _uiState.update {
            it.copy(
                showAddDialog = true,
                editingServer = WebDavServer(name = "", url = "", username = "", password = ""),
                testResult = null,
            )
        }
    }

    fun showEditDialog(server: WebDavServer) {
        _uiState.update {
            it.copy(
                showEditDialog = server,
                editingServer = server,
                testResult = null,
            )
        }
    }

    fun dismissDialog() {
        _uiState.update {
            it.copy(
                showAddDialog = false,
                showEditDialog = null,
                showDeleteConfirmation = null,
                testResult = null,
            )
        }
    }

    fun updateEditingName(name: String) {
        _uiState.update {
            it.copy(editingServer = it.editingServer.copy(name = name))
        }
    }

    fun updateEditingUrl(url: String) {
        _uiState.update {
            it.copy(editingServer = it.editingServer.copy(url = url))
        }
    }

    fun updateEditingUsername(username: String) {
        _uiState.update {
            it.copy(editingServer = it.editingServer.copy(username = username))
        }
    }

    fun updateEditingPassword(password: String) {
        _uiState.update {
            it.copy(editingServer = it.editingServer.copy(password = password))
        }
    }

    fun saveServer() {
        val server = _uiState.value.editingServer
        if (server.name.isBlank() || server.url.isBlank()) return

        viewModelScope.launch {
            if (_uiState.value.showAddDialog) {
                webDavRepository.addServer(
                    server.copy(
                        id = java.util.UUID.randomUUID().toString(),
                    ),
                )
            } else {
                webDavRepository.updateServer(server)
            }
        }
        dismissDialog()
    }

    fun deleteServer(server: WebDavServer) {
        viewModelScope.launch {
            webDavRepository.deleteServer(server.id)
        }
        dismissDialog()
    }

    fun toggleServerActive(id: String) {
        viewModelScope.launch {
            webDavRepository.toggleActive(id)
        }
    }

    fun testConnection() {
        val server = _uiState.value.editingServer
        if (server.url.isBlank()) return

        _uiState.update { it.copy(testingServer = true, testResult = null) }

        viewModelScope.launch {
            val result = webDavRepository.testConnection(
                baseUrl = server.url,
                username = server.username,
                password = server.password,
            )
            _uiState.update {
                it.copy(
                    testingServer = false,
                    testResult = if (result.isSuccess) "ok" else result.exceptionOrNull()?.message ?: "失败",
                )
            }
        }
    }

    fun showDeleteConfirmation(server: WebDavServer) {
        _uiState.update { it.copy(showDeleteConfirmation = server) }
    }
}
