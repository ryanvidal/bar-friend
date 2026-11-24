package com.ryanvidal.barfriend.feature.auth

import com.ryanvidal.barfriend.core.data.AuthRepository
import com.ryanvidal.barfriend.core.data.ServerCheckResult
import com.ryanvidal.barfriend.core.model.Bar
import com.ryanvidal.barfriend.core.model.ServerInfo
import com.ryanvidal.barfriend.core.network.BarAssistantApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `checkServer emits Compatible status when server meets minimum version`() = runTest(testDispatcher) {
        val serverInfo = ServerInfo(
            version = "6.2.0",
            latestVersion = "6.2.0",
            isPasswordLoginEnabled = true,
            isAiEnabled = false,
            isAiImageEnabled = false,
        )
        val repository = FakeAuthRepository(
            checkResult = ServerCheckResult.Compatible(serverInfo),
        )
        val viewModel = AuthViewModel(repository)

        viewModel.onServerUrlChanged("  https://bar.example.com  ")
        viewModel.checkServer()

        assertTrue(viewModel.uiState.value.isCheckingServer)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isCheckingServer)
        val status = assertIs<ServerCheckResult.Compatible>(state.serverStatus)
        assertEquals("6.2.0", status.serverInfo.version)
        assertNull(state.errorMessage)
        assertEquals(listOf("https://bar.example.com"), repository.checkedUrls)
    }

    @Test
    fun `checkServer emits UnsupportedVersion for Bar Assistant v5`() = runTest(testDispatcher) {
        val repository = FakeAuthRepository(
            checkResult = ServerCheckResult.UnsupportedVersion(actual = "5.4.1", minimum = "6.0.0"),
        )
        val viewModel = AuthViewModel(repository)

        viewModel.onServerUrlChanged("https://oldbar.example.com")
        viewModel.checkServer()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isCheckingServer)
        val status = assertIs<ServerCheckResult.UnsupportedVersion>(state.serverStatus)
        assertEquals("5.4.1", status.actual)
        assertEquals("6.0.0", status.minimum)
    }

    @Test
    fun `checkServer emits PasswordLoginDisabled when password auth is turned off`() = runTest(testDispatcher) {
        val serverInfo = ServerInfo(
            version = "6.1.0",
            latestVersion = null,
            isPasswordLoginEnabled = false,
            isAiEnabled = true,
            isAiImageEnabled = false,
        )
        val repository = FakeAuthRepository(
            checkResult = ServerCheckResult.PasswordLoginDisabled(serverInfo),
        )
        val viewModel = AuthViewModel(repository)

        viewModel.onServerUrlChanged("https://sso.example.com")
        viewModel.checkServer()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isCheckingServer)
        val status = assertIs<ServerCheckResult.PasswordLoginDisabled>(state.serverStatus)
        assertFalse(status.serverInfo.isPasswordLoginEnabled)
    }

    @Test
    fun `signIn succeeds with entered credentials and clears loading and error`() = runTest(testDispatcher) {
        val repository = FakeAuthRepository(
            signInResult = Result.success(
                Bar(
                    id = 1,
                    name = "Home Lounge",
                    subtitle = null,
                    description = null,
                    slug = "home-lounge",
                    isActive = true,
                ),
            ),
        )
        val viewModel = AuthViewModel(repository)

        viewModel.onServerUrlChanged("https://bar.example.com")
        viewModel.onEmailChanged("  ryan@example.com ")
        viewModel.onPasswordChanged("secret-pass")
        viewModel.signIn()

        assertTrue(viewModel.uiState.value.isSigningIn)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSigningIn)
        assertNull(state.errorMessage)
        assertEquals(
            listOf(SignInCall("https://bar.example.com", "ryan@example.com", "secret-pass")),
            repository.signInCalls,
        )
    }

    @Test
    fun `signIn failure surfaces errorMessage and clears loading`() = runTest(testDispatcher) {
        val repository = FakeAuthRepository(
            signInResult = Result.failure(IllegalStateException("Invalid email or password.")),
        )
        val viewModel = AuthViewModel(repository)

        viewModel.onServerUrlChanged("https://bar.example.com")
        viewModel.onEmailChanged("ryan@example.com")
        viewModel.onPasswordChanged("wrong-pass")
        viewModel.signIn()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSigningIn)
        assertEquals("Invalid email or password.", state.errorMessage)
    }

    @Test
    fun `signIn validates non-blank inputs before calling repository`() = runTest(testDispatcher) {
        val repository = FakeAuthRepository()
        val viewModel = AuthViewModel(repository)

        viewModel.onServerUrlChanged("https://bar.example.com")
        viewModel.onEmailChanged("")
        viewModel.onPasswordChanged("secret")
        viewModel.signIn()
        advanceUntilIdle()

        assertEquals("Email is required.", viewModel.uiState.value.errorMessage)
        assertTrue(repository.signInCalls.isEmpty())
    }

    @Test
    fun `editing serverUrl clears previous serverStatus`() = runTest(testDispatcher) {
        val repository = FakeAuthRepository(
            checkResult = ServerCheckResult.Compatible(
                ServerInfo("6.0.0", null, isPasswordLoginEnabled = true, isAiEnabled = false, isAiImageEnabled = false),
            ),
        )
        val viewModel = AuthViewModel(repository)

        viewModel.onServerUrlChanged("https://bar.example.com")
        viewModel.checkServer()
        advanceUntilIdle()
        assertIs<ServerCheckResult.Compatible>(viewModel.uiState.value.serverStatus)

        viewModel.onServerUrlChanged("https://other.example.com")
        assertNull(viewModel.uiState.value.serverStatus)
    }

    @Test
    fun `editing serverUrl cancels in-flight checkServer and clears loading`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        var checkCancelled = false
        val repository = FakeAuthRepository(
            onCheckServer = {
                try {
                    gate.await()
                    ServerCheckResult.Compatible(
                        ServerInfo("6.2.0", null, isPasswordLoginEnabled = true, isAiEnabled = false, isAiImageEnabled = false),
                    )
                } catch (e: CancellationException) {
                    checkCancelled = true
                    throw e
                }
            },
        )
        val viewModel = AuthViewModel(repository)

        viewModel.onServerUrlChanged("https://bar.example.com")
        viewModel.checkServer()
        runCurrent()
        assertTrue(viewModel.uiState.value.isCheckingServer)

        viewModel.onServerUrlChanged("https://other.example.com")
        val stateAfterEdit = viewModel.uiState.value
        assertFalse(stateAfterEdit.isCheckingServer)
        assertNull(stateAfterEdit.serverStatus)
        assertNull(stateAfterEdit.errorMessage)
        assertFalse(stateAfterEdit.showLocalNetworkHintAfterFailure)

        runCurrent()
        assertTrue(checkCancelled)
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.serverStatus)
    }

    @Test
    fun `signIn success clears password, passwordVisible, serverStatus, and local network hint`() = runTest(testDispatcher) {
        val repository = FakeAuthRepository(
            checkResult = ServerCheckResult.Compatible(
                ServerInfo("6.2.0", null, isPasswordLoginEnabled = true, isAiEnabled = false, isAiImageEnabled = false),
            ),
            signInResult = Result.success(
                Bar(
                    id = 1,
                    name = "Home Lounge",
                    subtitle = null,
                    description = null,
                    slug = "home-lounge",
                    isActive = true,
                ),
            ),
        )
        val viewModel = AuthViewModel(repository)

        viewModel.onServerUrlChanged("https://bar.example.com")
        viewModel.checkServer()
        advanceUntilIdle()
        viewModel.onEmailChanged("ryan@example.com")
        viewModel.onPasswordChanged("secret-pass")
        viewModel.onTogglePasswordVisibility()
        assertTrue(viewModel.uiState.value.passwordVisible)

        viewModel.signIn()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("", state.password)
        assertFalse(state.passwordVisible)
        assertNull(state.serverStatus)
        assertNull(state.errorMessage)
        assertFalse(state.showLocalNetworkHintAfterFailure)
    }

    @Test
    fun `domain network or timeout failure sets showLocalNetworkHintAfterFailure`() = runTest(testDispatcher) {
        val repository = FakeAuthRepository(
            checkResult = ServerCheckResult.Error("Connection timed out"),
        )
        val viewModel = AuthViewModel(repository)

        viewModel.onServerUrlChanged("https://bar.example.com")
        viewModel.checkServer()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showLocalNetworkHintAfterFailure)

        viewModel.onServerUrlChanged("http://192.168.1.50")
        assertFalse(viewModel.uiState.value.showLocalNetworkHintAfterFailure)
        viewModel.checkServer()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.showLocalNetworkHintAfterFailure)
    }

    @Test
    fun `signIn network failure on domain sets hint while HTTP API failure does not`() = runTest(testDispatcher) {
        val repository = FakeAuthRepository(
            signInResult = Result.failure(IllegalStateException("Could not connect to Bar Assistant server.")),
        )
        val viewModel = AuthViewModel(repository)

        viewModel.onServerUrlChanged("https://bar.example.com")
        viewModel.onEmailChanged("ryan@example.com")
        viewModel.onPasswordChanged("secret-pass")
        viewModel.signIn()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showLocalNetworkHintAfterFailure)

        repository.signInResult = Result.failure(BarAssistantApiException(401, "Invalid credentials"))
        viewModel.signIn()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.showLocalNetworkHintAfterFailure)
    }

    @Test
    fun `signIn interrupting checkServer cancels check and clears isCheckingServer`() = runTest(testDispatcher) {
        val gate = CompletableDeferred<Unit>()
        var checkCancelled = false
        val repository = FakeAuthRepository(
            onCheckServer = {
                try {
                    gate.await()
                    ServerCheckResult.Compatible(
                        ServerInfo("6.2.0", null, isPasswordLoginEnabled = true, isAiEnabled = false, isAiImageEnabled = false),
                    )
                } catch (e: CancellationException) {
                    checkCancelled = true
                    throw e
                }
            },
            signInResult = Result.success(
                Bar(
                    id = 1,
                    name = "Home Lounge",
                    subtitle = null,
                    description = null,
                    slug = "home-lounge",
                    isActive = true,
                ),
            ),
        )
        val viewModel = AuthViewModel(repository)

        viewModel.onServerUrlChanged("https://bar.example.com")
        viewModel.onEmailChanged("ryan@example.com")
        viewModel.onPasswordChanged("secret-pass")
        viewModel.checkServer()
        runCurrent()
        assertTrue(viewModel.uiState.value.isCheckingServer)

        viewModel.signIn()
        assertFalse(viewModel.uiState.value.isCheckingServer)
        assertTrue(viewModel.uiState.value.isSigningIn)

        advanceUntilIdle()
        assertTrue(checkCancelled)
        assertFalse(viewModel.uiState.value.isCheckingServer)
        assertFalse(viewModel.uiState.value.isSigningIn)
    }

    @Test
    fun `signIn with matching UnsupportedVersion serverStatus suppresses duplicate errorMessage`() = runTest(testDispatcher) {
        val repository = FakeAuthRepository(
            checkResult = ServerCheckResult.UnsupportedVersion(actual = "5.4.1", minimum = "6.0.0"),
            signInResult = Result.failure(
                IllegalStateException("Bar Assistant 5.4.1 is not supported. Minimum required version is v6.0.0."),
            ),
        )
        val viewModel = AuthViewModel(repository)

        viewModel.onServerUrlChanged("https://legacy.example.com")
        viewModel.checkServer()
        advanceUntilIdle()
        assertIs<ServerCheckResult.UnsupportedVersion>(viewModel.uiState.value.serverStatus)

        viewModel.onEmailChanged("ryan@example.com")
        viewModel.onPasswordChanged("secret-pass")
        viewModel.signIn()
        advanceUntilIdle()

        assertIs<ServerCheckResult.UnsupportedVersion>(viewModel.uiState.value.serverStatus)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    private data class SignInCall(
        val serverUrl: String,
        val email: String,
        val password: String,
    )

    private class FakeAuthRepository(
        var checkResult: ServerCheckResult = ServerCheckResult.Error("Unconfigured"),
        var signInResult: Result<Bar> = Result.failure(IllegalStateException("Unconfigured")),
        var onCheckServer: (suspend (String) -> ServerCheckResult)? = null,
    ) : AuthRepository {
        val checkedUrls = mutableListOf<String>()
        val signInCalls = mutableListOf<SignInCall>()

        override suspend fun checkServer(serverUrl: String): ServerCheckResult {
            checkedUrls += serverUrl
            return onCheckServer?.invoke(serverUrl) ?: checkResult
        }

        override suspend fun signIn(serverUrl: String, email: String, password: String): Result<Bar> {
            signInCalls += SignInCall(serverUrl, email, password)
            return signInResult
        }

        override suspend fun signOut(): Result<Unit> = Result.success(Unit)
    }
}
