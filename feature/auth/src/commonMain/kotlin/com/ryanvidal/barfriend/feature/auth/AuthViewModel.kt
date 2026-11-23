package com.ryanvidal.barfriend.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ryanvidal.barfriend.core.data.AuthRepository
import com.ryanvidal.barfriend.core.data.ServerCheckResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val serverUrl: String = "",
    val email: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val isCheckingServer: Boolean = false,
    val isSigningIn: Boolean = false,
    val serverStatus: ServerCheckResult? = null,
    val errorMessage: String? = null,
)

class AuthViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun onServerUrlChanged(value: String) {
        _uiState.update { current ->
            if (current.serverUrl == value) {
                current
            } else {
                current.copy(
                    serverUrl = value,
                    serverStatus = null,
                    errorMessage = null,
                )
            }
        }
    }

    fun onEmailChanged(value: String) {
        _uiState.update { current ->
            current.copy(
                email = value,
                errorMessage = null,
            )
        }
    }

    fun onPasswordChanged(value: String) {
        _uiState.update { current ->
            current.copy(
                password = value,
                errorMessage = null,
            )
        }
    }

    fun onTogglePasswordVisibility() {
        _uiState.update { current ->
            current.copy(passwordVisible = !current.passwordVisible)
        }
    }

    fun checkServer() {
        val current = _uiState.value
        if (current.isCheckingServer || current.isSigningIn) return

        val targetUrl = current.serverUrl.trim()
        if (targetUrl.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Enter a server URL to verify.") }
            return
        }

        _uiState.update {
            it.copy(
                isCheckingServer = true,
                serverStatus = null,
                errorMessage = null,
            )
        }

        viewModelScope.launch {
            val result = try {
                authRepository.checkServer(targetUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                ServerCheckResult.Error(
                    e.message?.takeIf { it.isNotBlank() } ?: "Could not verify server.",
                )
            }

            _uiState.update { state ->
                if (state.serverUrl.trim() == targetUrl) {
                    state.copy(
                        isCheckingServer = false,
                        serverStatus = result,
                    )
                } else {
                    state.copy(isCheckingServer = false)
                }
            }
        }
    }

    fun signIn() {
        val current = _uiState.value
        if (current.isSigningIn) return

        val serverUrl = current.serverUrl.trim()
        val email = current.email.trim()
        val password = current.password

        val validationError = when {
            serverUrl.isEmpty() -> "Server URL is required."
            email.isEmpty() -> "Email is required."
            password.isBlank() -> "Password is required."
            else -> null
        }
        if (validationError != null) {
            _uiState.update { it.copy(errorMessage = validationError) }
            return
        }

        _uiState.update {
            it.copy(
                isSigningIn = true,
                errorMessage = null,
            )
        }

        viewModelScope.launch {
            val result = try {
                authRepository.signIn(
                    serverUrl = serverUrl,
                    email = email,
                    password = password,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Result.failure(e)
            }

            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isSigningIn = false,
                            errorMessage = null,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isSigningIn = false,
                            errorMessage = error.message?.takeIf { msg -> msg.isNotBlank() } ?: "Sign in failed.",
                        )
                    }
                },
            )
        }
    }
}
