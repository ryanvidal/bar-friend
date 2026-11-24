package com.ryanvidal.barfriend.core.data

import com.ryanvidal.barfriend.core.database.dao.SyncDao
import com.ryanvidal.barfriend.core.network.NetworkSessionProvider
import com.ryanvidal.barfriend.core.network.ServerUrlNormalizer
import com.ryanvidal.barfriend.core.platform.SecureTokenStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

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
    suspend fun saveSession(
        serverUrl: String,
        token: String,
        barId: Int?,
        barName: String?,
        userId: Int?,
    ) = saveSession(serverUrl, token, barId, barName)
    suspend fun clearSession()
    suspend fun currentUserId(): Int? = null
    suspend fun setActiveUserId(userId: Int) = Unit
    suspend fun setActiveUserId(userId: Int?, expectedToken: String?) {
        if (userId != null) {
            setActiveUserId(userId)
        }
    }
    fun currentSessionEpoch(): Long = 0L
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, binding = binding<SessionRepository>())
@ContributesBinding(AppScope::class, binding = binding<NetworkSessionProvider>())
@Inject
class DefaultSessionRepository(
    private val tokenStore: SecureTokenStore,
    private val appPreferences: AppPreferences,
    private val syncDao: SyncDao,
    private val imageCacheCleaner: Lazy<ImageCacheCleaner>,
) : SessionRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateMutex = Mutex()
    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Loading)
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    @Volatile
    private var initialized = false

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var cachedServerUrl: String? = null

    @Volatile
    private var cachedBarId: Int? = null

    @Volatile
    private var cachedUserId: Int? = null

    @Volatile
    private var sessionEpoch: Long = 0L

    init {
        scope.launch {
            restoreSession()
        }
    }

    private suspend fun ensureInitialized() {
        if (initialized) return
        restoreSession()
    }

    private suspend fun restoreSession() {
        stateMutex.withLock {
            if (!initialized) {
                restoreSessionLocked()
            }
        }
    }

    private suspend fun restoreSessionLocked() {
        try {
            val token = tokenStore.getToken()
            val settings = appPreferences.currentSettings()
            val serverUrl = settings.serverUrl?.takeIf { it.isNotBlank() }
            if (serverUrl == null && !token.isNullOrBlank()) {
                runCatching { tokenStore.clearToken() }
                    .onFailure { if (it is CancellationException) throw it }
            }
            if (!token.isNullOrBlank() && serverUrl != null) {
                cachedToken = token
                cachedServerUrl = serverUrl
                cachedBarId = settings.activeBarId
                cachedUserId = settings.activeUserId
                _sessionState.value = SessionState.Authenticated(
                    serverUrl = serverUrl,
                    activeBarId = settings.activeBarId,
                    activeBarName = settings.activeBarName,
                )
            } else {
                runCatching { appPreferences.setActiveUserId(null) }
                    .onFailure { if (it is CancellationException) throw it }
                cachedToken = null
                cachedServerUrl = serverUrl
                cachedBarId = null
                cachedUserId = null
                _sessionState.value = SessionState.Unauthenticated(lastServerUrl = serverUrl)
            }
            initialized = true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            cachedToken = null
            cachedServerUrl = null
            cachedBarId = null
            cachedUserId = null
            initialized = true
            _sessionState.value = SessionState.Unauthenticated(lastServerUrl = null)
        }
    }

    override suspend fun saveSession(
        serverUrl: String,
        token: String,
        barId: Int?,
        barName: String?,
    ) = saveSession(
        serverUrl = serverUrl,
        token = token,
        barId = barId,
        barName = barName,
        userId = null,
    )

    override suspend fun saveSession(
        serverUrl: String,
        token: String,
        barId: Int?,
        barName: String?,
        userId: Int?,
    ) {
        val normalized = ServerUrlNormalizer.normalize(serverUrl)
        stateMutex.withLock {
            val previousToken = cachedToken
                ?: runCatching { tokenStore.getToken() }.getOrNull()
            val previousServerUrl = cachedServerUrl
                ?: runCatching { appPreferences.currentSettings().serverUrl }.getOrNull()
            val previousBarId = cachedBarId
                ?: runCatching { appPreferences.currentSettings().activeBarId }.getOrNull()
            val serverChanged = previousServerUrl != null && !previousServerUrl.equals(normalized, ignoreCase = true)
            val barChanged = previousBarId != null && barId != null && previousBarId != barId
            val tokenChanged = previousToken != token
            val shouldResetCache = serverChanged || barChanged || tokenChanged
            if (shouldResetCache) {
                sessionEpoch += 1L
                cachedBarId = null
                cachedUserId = null
                withContext(NonCancellable) {
                    syncDao.clearAllData()
                    imageCacheCleaner.value.clear()
                    appPreferences.setActiveUserId(userId)
                }
            } else if (userId != null) {
                appPreferences.setActiveUserId(userId)
            }
            tokenStore.saveToken(token)
            appPreferences.setServerConnection(normalized, barId, barName)
            cachedToken = token
            cachedServerUrl = normalized
            cachedBarId = barId
            cachedUserId = if (shouldResetCache) userId else (userId ?: cachedUserId)
            initialized = true
            _sessionState.value = SessionState.Authenticated(
                serverUrl = normalized,
                activeBarId = barId,
                activeBarName = barName,
            )
        }
    }

    override suspend fun clearSession() {
        stateMutex.withLock {
            clearSessionLocked()
        }
    }

    private suspend fun clearSessionLocked() {
        withContext(NonCancellable) {
            sessionEpoch += 1L
            val lastServer = cachedServerUrl
                ?: runCatching { appPreferences.currentSettings().serverUrl }.getOrNull()
            cachedToken = null
            cachedServerUrl = lastServer
            cachedBarId = null
            cachedUserId = null
            initialized = true
            _sessionState.value = SessionState.Unauthenticated(lastServerUrl = lastServer)
            runCatching { tokenStore.clearToken() }
            runCatching { appPreferences.clearSessionMetadata() }
            runCatching { syncDao.clearAllData() }
            runCatching { imageCacheCleaner.value.clear() }
        }
    }

    override suspend fun currentServerUrl(): String? {
        ensureInitialized()
        return cachedServerUrl
    }

    override suspend fun currentToken(): String? {
        ensureInitialized()
        return cachedToken
    }

    override suspend fun currentBarId(): Int? {
        ensureInitialized()
        return cachedBarId
    }

    override suspend fun currentUserId(): Int? {
        ensureInitialized()
        return cachedUserId
    }

    override fun currentSessionEpoch(): Long = sessionEpoch

    override suspend fun setActiveUserId(userId: Int) = setActiveUserId(userId, expectedToken = null)

    override suspend fun setActiveUserId(userId: Int?, expectedToken: String?) {
        stateMutex.withLock {
            if (!initialized) {
                restoreSessionLocked()
            }
            if (_sessionState.value !is SessionState.Authenticated) return@withLock
            if (expectedToken != null && cachedToken != expectedToken) return@withLock
            appPreferences.setActiveUserId(userId)
            cachedUserId = userId
        }
    }

    override suspend fun onUnauthorized(failedToken: String) {
        if (failedToken.isBlank()) return
        stateMutex.withLock {
            if (!initialized) {
                restoreSessionLocked()
            }
            if (!cachedToken.isNullOrBlank() && cachedToken == failedToken) {
                clearSessionLocked()
            }
        }
    }
}
