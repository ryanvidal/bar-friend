package com.ryanvidal.barfriend.core.data

import com.ryanvidal.barfriend.core.network.NetworkSessionProvider
import com.ryanvidal.barfriend.core.network.ServerUrlNormalizer
import com.ryanvidal.barfriend.core.platform.SecureTokenStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SessionState {
    data object Loading : SessionState
    data class Unauthenticated(val lastServerUrl: String? = null) : SessionState
    data class Authenticated(
        val serverUrl: String,
        val activeBarId: Int?,
        val activeBarName: String?,
    ) : SessionState
}

interface SessionRepository : NetworkSessionProvider {
    val sessionState: StateFlow<SessionState>
    suspend fun saveSession(serverUrl: String, token: String, barId: Int?, barName: String?)
    suspend fun clearSession()
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<SessionRepository>())
@ContributesBinding(AppScope::class, binding = binding<NetworkSessionProvider>())
@Inject
class DefaultSessionRepository(
    private val tokenStore: SecureTokenStore,
    private val appPreferences: AppPreferences,
) : SessionRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Loading)
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    init {
        scope.launch {
            restoreSession()
        }
    }

    private suspend fun restoreSession() {
        val token = tokenStore.getToken()
        val settings = appPreferences.currentSettings()
        val serverUrl = settings.serverUrl
        _sessionState.value = if (!token.isNullOrBlank() && !serverUrl.isNullOrBlank()) {
            SessionState.Authenticated(
                serverUrl = serverUrl,
                activeBarId = settings.activeBarId,
                activeBarName = settings.activeBarName,
            )
        } else {
            SessionState.Unauthenticated(lastServerUrl = serverUrl)
        }
    }

    override suspend fun saveSession(
        serverUrl: String,
        token: String,
        barId: Int?,
        barName: String?,
    ) {
        val normalized = ServerUrlNormalizer.normalize(serverUrl)
        tokenStore.saveToken(token)
        appPreferences.setServerConnection(normalized, barId, barName)
        _sessionState.value = SessionState.Authenticated(
            serverUrl = normalized,
            activeBarId = barId,
            activeBarName = barName,
        )
    }

    override suspend fun clearSession() {
        val lastServer = appPreferences.currentSettings().serverUrl
        tokenStore.clearToken()
        appPreferences.clearSessionMetadata()
        _sessionState.value = SessionState.Unauthenticated(lastServerUrl = lastServer)
    }

    override suspend fun currentServerUrl(): String? = appPreferences.currentSettings().serverUrl

    override suspend fun currentToken(): String? = tokenStore.getToken()

    override suspend fun currentBarId(): Int? = appPreferences.currentSettings().activeBarId

    override suspend fun onUnauthorized() {
        clearSession()
    }
}
