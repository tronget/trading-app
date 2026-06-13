package com.trading.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trading.app.data.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val loggedIn: Boolean = false,
)

class AuthViewModel(private val auth: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state.update { it.copy(loggedIn = auth.isLoggedIn()) }
        }
    }

    fun login(login: String, password: String) = submit { auth.login(login, password) }

    fun register(login: String, password: String) = submit { auth.register(login, password) }

    private fun submit(block: suspend () -> Unit) {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                block()
                _state.update { it.copy(loading = false, loggedIn = true) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Ошибка сети") }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            auth.logout()
            _state.update { it.copy(loggedIn = false) }
        }
    }
}
