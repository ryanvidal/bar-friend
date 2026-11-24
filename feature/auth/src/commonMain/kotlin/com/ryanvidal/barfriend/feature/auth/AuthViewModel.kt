package com.ryanvidal.barfriend.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ryanvidal.barfriend.core.data.AuthRepository
import com.ryanvidal.barfriend.core.data.ServerCheckResult
import com.ryanvidal.barfriend.core.network.BarAssistantApiException
import com.ryanvidal.barfriend.core.network.ServerUrlNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    val showLocalNetworkHintAfterFailure: Boolean = false,
)

class AuthViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()
    private var checkServerJob: Job? = null

    fun onServerUrlChanged(value: String) {
        if (_uiState.value.serverUrl == value) return
        checkServerJob?.cancel()
        checkServerJob = null
        _uiState.update { current ->
            if (current.serverUrl == value) {
                current
            } else {
                current.copy(
                    serverUrl = value,
                    isCheckingServer = false,
                    serverStatus = null,
                    errorMessage = null,
                    showLocalNetworkHintAfterFailure = false,
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

        checkServerJob?.cancel()
        checkServerJob = null
        _uiState.update {
            it.copy(
                isCheckingServer = true,
                serverStatus = null,
                errorMessage = null,
                showLocalNetworkHintAfterFailure = false,
            )
        }

        val job = viewModelScope.launch {
            try {
                val (result, isNetworkFailure) = try {
                    val checkResult = authRepository.checkServer(targetUrl)
                    val networkFailure = checkResult is ServerCheckResult.Error &&
                        !isHttpStatusMessage(checkResult.message)
                    checkResult to networkFailure
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    val message = e.message?.takeIf { it.isNotBlank() } ?: "Could not verify server."
                    val networkFailure = e !is BarAssistantApiException && !isHttpStatusMessage(message)
                    ServerCheckResult.Error(message) to networkFailure
                }

                val showLocalNetworkHint = isNetworkFailure &&
                    !ServerUrlNormalizer.isLocalHostUrl(targetUrl)
                _uiState.update { state ->
                    if (state.serverUrl.trim() == targetUrl) {
                        state.copy(
                            isCheckingServer = false,
                            serverStatus = result,
                            showLocalNetworkHintAfterFailure = showLocalNetworkHint,
                        )
                    } else {
                        state.copy(isCheckingServer = false)
                    }
                }
            } finally {
                if (checkServerJob === coroutineContext[Job]) {
                    checkServerJob = null
                }
            }
        }
        if (!job.isCompleted) {
            checkServerJob = job
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

        checkServerJob?.cancel()
        checkServerJob = null
        _uiState.update {
            it.copy(
                isCheckingServer = false,
                isSigningIn = true,
                errorMessage = null,
                showLocalNetworkHintAfterFailure = false,
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
                            password = "",
                            passwordVisible = false,
                            isSigningIn = false,
                            serverStatus = null,
                            errorMessage = null,
                            showLocalNetworkHintAfterFailure = false,
                        )
                    }
                },
                onFailure = { error ->
                    val message = error.message?.takeIf { msg -> msg.isNotBlank() } ?: "Sign in failed."
                    val showLocalNetworkHint = !ServerUrlNormalizer.isLocalHostUrl(serverUrl) &&
                        isNetworkOrTimeoutFailure(error, message)
                    _uiState.update { state ->
                        val suppressDuplicate = isDuplicateServerStatusError(state.serverStatus, message)
                        state.copy(
                            isSigningIn = false,
                            errorMessage = if (suppressDuplicate) null else message,
                            showLocalNetworkHintAfterFailure = showLocalNetworkHint,
                        )
                    }
                },
            )
        }
    }

    private fun isNetworkOrTimeoutFailure(
        error: Throwable,
        message: String,
    ): Boolean {
        if (error is BarAssistantApiException) return false
        if (isHttpStatusMessage(message)) return false
        if (isNonNetworkAuthMessage(message)) return false
        return true
    }

    private fun isDuplicateServerStatusError(
        serverStatus: ServerCheckResult?,
        message: String,
    ): Boolean = when (serverStatus) {
        is ServerCheckResult.UnsupportedVersion ->
            message.contains("not supported", ignoreCase = true) ||
                message.contains("unsupported", ignoreCase = true)

        is ServerCheckResult.PasswordLoginDisabled ->
            message.contains("password login is disabled", ignoreCase = true)

        is ServerCheckResult.Error -> message == serverStatus.message
        is ServerCheckResult.Compatible,
        null,
        -> false
    }

    private fun isNonNetworkAuthMessage(message: String): Boolean =
        message.contains("not supported", ignoreCase = true) ||
            message.contains("unsupported", ignoreCase = true) ||
            message.contains("password login is disabled", ignoreCase = true) ||
            message.equals("No bars were found for this account.", ignoreCase = true) ||
            message.equals("Server URL is required.", ignoreCase = true) ||
            message.equals("Email is required.", ignoreCase = true) ||
            message.equals("Password is required.", ignoreCase = true)

    private fun isHttpStatusMessage(message: String): Boolean =
        HTTP_STATUS_REGEX.containsMatchIn(message)

    private companion object {
        val HTTP_STATUS_REGEX = Regex("""\bHTTP\s+\d{3}\b""", RegexOption.IGNORE_CASE)
    }
}
