package com.ryanvidal.barfriend.core.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.time.Clock

data class SyncStatus(
    val isSyncing: Boolean = false,
    val lastSyncedEpochMillis: Long? = null,
    val errorMessage: String? = null,
)

interface BarSyncCoordinator {
    val syncStatus: StateFlow<SyncStatus>

    suspend fun syncAll(force: Boolean = false): Result<Unit>
    suspend fun syncAllSilent(force: Boolean = false): Result<Unit> = syncAll(force = force)
    fun scheduleBackgroundSync(originEpoch: Long) = Unit
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultBarSyncCoordinator(
    private val remoteDataSource: BarRemoteDataSource,
    private val localDataSource: BarLocalDataSource,
    private val sessionRepository: SessionRepository,
    private val mutationCoordinator: BarMutationCoordinator,
) : BarSyncCoordinator {

    private val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncMutex = Mutex()
    private val _syncStatus = MutableStateFlow(SyncStatus())
    override val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private var activeAutoSyncJob: Job? = null
    private var lastObservedSessionKey: Pair<String, Int?>? = null

    @Volatile
    internal var lastBackgroundSyncJob: Job? = null

    @Volatile
    private var lastSyncedSession: Pair<String, Int>? = null

    init {
        coordinatorScope.launch {
            sessionRepository.sessionState.collect { state ->
                val observedKey = (state as? SessionState.Authenticated)?.let { it.serverUrl to it.activeBarId }
                if (observedKey != lastObservedSessionKey) {
                    lastObservedSessionKey = observedKey
                    lastBackgroundSyncJob?.cancel()
                }
                when (state) {
                    is SessionState.Loading -> Unit
                    is SessionState.Unauthenticated -> {
                        lastBackgroundSyncJob?.cancel()
                        activeAutoSyncJob?.cancel()
                        activeAutoSyncJob = null
                        lastSyncedSession = null
                        _syncStatus.value = SyncStatus()
                    }
                    is SessionState.Authenticated -> {
                        val barId = state.activeBarId ?: return@collect
                        val sessionKey = state.serverUrl to barId
                        if (lastSyncedSession != sessionKey) {
                            activeAutoSyncJob?.cancel()
                            activeAutoSyncJob = coordinatorScope.launch {
                                syncAll(force = false)
                            }
                        }
                    }
                }
            }
        }
    }

    override suspend fun syncAll(force: Boolean): Result<Unit> = syncInternal(force = force, silent = false)

    override suspend fun syncAllSilent(force: Boolean): Result<Unit> = syncInternal(force = force, silent = true)

    override fun scheduleBackgroundSync(originEpoch: Long) {
        lastBackgroundSyncJob?.cancel()
        lastBackgroundSyncJob = coordinatorScope.launch {
            if (sessionRepository.currentSessionEpoch() != originEpoch) return@launch
            runSuspendCatching { syncAllSilent(force = true) }
        }
    }

    private suspend fun syncInternal(force: Boolean, silent: Boolean): Result<Unit> = syncMutex.withLock {
        val barId = sessionRepository.currentActiveBarId()
        val serverUrl = sessionRepository.currentServerUrl()
        if (barId == null || serverUrl.isNullOrBlank()) {
            publishInteractiveSyncStatus(
                silent = silent,
                status = SyncStatus(
                    isSyncing = false,
                    lastSyncedEpochMillis = null,
                    errorMessage = "No active bar selected.",
                ),
            )
            return@withLock Result.failure(IllegalStateException("No active bar selected."))
        }

        val sessionKey = serverUrl to barId
        if (!force && lastSyncedSession == sessionKey) {
            return@withLock Result.success(Unit)
        }

        val previousTimestamp = _syncStatus.value.lastSyncedEpochMillis
            ?: localDataSource.getSyncTimestamp(barId)
        try {
            publishInteractiveSyncStatus(
                silent = silent,
                status = SyncStatus(
                    isSyncing = true,
                    lastSyncedEpochMillis = previousTimestamp,
                    errorMessage = null,
                ),
            )

            for (attempt in 1..MAX_SYNC_ATTEMPTS) {
                val startGen = mutationCoordinator.withMutationLock { mutationCoordinator.currentGenerationLocked() }
                val payload = remoteDataSource.fetchBarSyncPayload(barId)

                if (!sessionRepository.isSessionStillActive(serverUrl, barId)) {
                    if (sessionRepository.sessionState.value is SessionState.Unauthenticated) {
                        _syncStatus.value = SyncStatus()
                    }
                    return@withLock Result.success(Unit)
                }

                val wroteSnapshot = mutationCoordinator.withMutationLock {
                    if (!sessionRepository.isSessionStillActive(serverUrl, barId) ||
                        mutationCoordinator.currentGenerationLocked() != startGen
                    ) {
                        false
                    } else {
                        val now = Clock.System.now().toEpochMilliseconds()
                        localDataSource.replaceBarSnapshot(
                            barId = barId,
                            serverUrl = serverUrl,
                            payload = payload,
                            syncedAtEpochMillis = now,
                        )
                        lastSyncedSession = sessionKey
                        _syncStatus.value = SyncStatus(
                            isSyncing = false,
                            lastSyncedEpochMillis = now,
                            errorMessage = null,
                        )
                        true
                    }
                }

                if (wroteSnapshot) {
                    return@withLock Result.success(Unit)
                }
                if (!sessionRepository.isSessionStillActive(serverUrl, barId)) {
                    if (sessionRepository.sessionState.value is SessionState.Unauthenticated) {
                        _syncStatus.value = SyncStatus()
                    }
                    return@withLock Result.success(Unit)
                }
            }

            // All retry attempts were superseded by concurrent mutations; keep existing status if silent
            // because no snapshot was written.
            publishInteractiveSyncStatus(
                silent = silent,
                status = SyncStatus(
                    isSyncing = false,
                    lastSyncedEpochMillis = previousTimestamp,
                    errorMessage = null,
                ),
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            if (sessionRepository.isSessionStillActive(serverUrl, barId)) {
                publishInteractiveSyncStatus(
                    silent = silent,
                    status = SyncStatus(
                        isSyncing = false,
                        lastSyncedEpochMillis = previousTimestamp,
                        errorMessage = null,
                    ),
                )
            } else if (sessionRepository.sessionState.value is SessionState.Unauthenticated) {
                _syncStatus.value = SyncStatus()
            }
            throw e
        } catch (e: Throwable) {
            if (sessionRepository.isSessionStillActive(serverUrl, barId)) {
                publishInteractiveSyncStatus(
                    silent = silent,
                    status = SyncStatus(
                        isSyncing = false,
                        lastSyncedEpochMillis = previousTimestamp,
                        errorMessage = e.message?.takeIf { it.isNotBlank() } ?: "Sync failed.",
                    ),
                )
            }
            Result.failure(e)
        }
    }

    private fun publishInteractiveSyncStatus(silent: Boolean, status: SyncStatus) {
        if (!silent) {
            _syncStatus.value = status
        }
    }

    private companion object {
        const val MAX_SYNC_ATTEMPTS = 2
    }
}
