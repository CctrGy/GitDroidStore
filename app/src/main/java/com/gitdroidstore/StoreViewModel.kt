package com.gitdroidstore

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gitdroidstore.model.LogEntry
import com.gitdroidstore.model.StoreApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UiState(
    val apps: List<StoreApp> = emptyList(), val logs: List<LogEntry> = emptyList(),
    val loading: Boolean = false, val message: String? = null
)

class StoreViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GitDroidApplication
    private val repo = app.repository
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    val settings get() = app.settings
    val installer get() = repo.installer

    init { viewModelScope.launch { _state.value = _state.value.copy(apps = repo.cachedApps(), logs = repo.logs()) } }

    fun refresh() = launchBusy {
        _state.value = _state.value.copy(apps = repo.refresh(), message = "Catálogo actualizado")
    }

    fun install(storeApp: StoreApp) = launchBusy {
        repo.prepareAndInstall(storeApp)
        _state.value = _state.value.copy(message = "APK verificado; instalación enviada al sistema")
    }

    fun reloadLogs() = viewModelScope.launch { _state.value = _state.value.copy(logs = repo.logs()) }
    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    private fun launchBusy(block: suspend () -> Unit) = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true, message = null)
        try { block() } catch (e: Exception) { _state.value = _state.value.copy(message = e.message ?: "Error inesperado") }
        finally { _state.value = _state.value.copy(loading = false, logs = repo.logs()) }
    }
}
