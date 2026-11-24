package com.ryanvidal.barfriend.core.data

import com.ryanvidal.barfriend.core.network.BarAssistantApi
import com.ryanvidal.barfriend.core.network.BarAssistantApiException
import com.ryanvidal.barfriend.core.network.dto.PaginatedEnvelope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

internal suspend fun resolveUserIdAndToken(
    api: BarAssistantApi,
    sessionRepository: SessionRepository,
): Pair<String?, Int> {
    val token = sessionRepository.currentToken()
    val cached = sessionRepository.currentUserId()
    if (cached != null) return token to cached
    val fetched = api.getProfile().id
    sessionRepository.setCurrentUserId(fetched, expectedToken = token)
    return token to fetched
}

internal suspend fun <T> executeWith403UserIdRetry(
    api: BarAssistantApi,
    sessionRepository: SessionRepository,
    initialUserId: Int,
    token: String?,
    block: suspend (userId: Int) -> T,
): T = try {
    block(initialUserId)
} catch (e: BarAssistantApiException) {
    if (e.statusCode != 403) throw e
    sessionRepository.setCurrentUserId(null, expectedToken = token)
    val refreshedUserId = api.getProfile().id
    sessionRepository.setCurrentUserId(refreshedUserId, expectedToken = token)
    block(refreshedUserId)
}

internal suspend fun <T> withResolvedUserIdAnd403Retry(
    api: BarAssistantApi,
    sessionRepository: SessionRepository,
    block: suspend (userId: Int) -> T,
): T {
    val (token, userId) = resolveUserIdAndToken(api, sessionRepository)
    return executeWith403UserIdRetry(api, sessionRepository, userId, token, block)
}

internal suspend fun <T> fetchAllPages(
    fetchPage: suspend (page: Int) -> PaginatedEnvelope<T>,
): List<T> {
    val items = mutableListOf<T>()
    var page = 1
    while (true) {
        val envelope = fetchPage(page)
        items += envelope.data
        val lastPage = envelope.meta?.lastPage ?: page
        if (page >= lastPage || envelope.data.isEmpty()) {
            break
        }
        page += 1
    }
    return items
}

internal fun SessionRepository.activeBarIdFlow(): Flow<Int?> =
    sessionState
        .map { state ->
            when (state) {
                is SessionState.Authenticated -> state.activeBarId
                is SessionState.Loading, is SessionState.Unauthenticated -> null
            }
        }
        .distinctUntilChanged()

internal suspend fun SessionRepository.currentActiveBarId(): Int? =
    (sessionState.value as? SessionState.Authenticated)?.activeBarId
        ?: currentBarId()

internal suspend fun SessionRepository.requireActiveBarId(): Int =
    currentActiveBarId() ?: throw IllegalStateException("No active bar selected.")

internal suspend fun SessionRepository.currentServerUrl(): String? =
    (sessionState.value as? SessionState.Authenticated)?.serverUrl
        ?: currentServerUrl()

internal fun SessionRepository.isSessionStillActive(serverUrl: String, barId: Int): Boolean {
    val auth = sessionState.value as? SessionState.Authenticated ?: return false
    return auth.serverUrl == serverUrl && auth.activeBarId == barId
}

internal suspend fun SessionRepository.isSessionEpochValid(
    expectedEpoch: Long,
    expectedBarId: Int,
): Boolean = currentSessionEpoch() == expectedEpoch && currentActiveBarId() == expectedBarId

internal suspend fun SessionRepository.ensureSessionEpochValid(
    expectedEpoch: Long,
    expectedBarId: Int,
) {
    if (!isSessionEpochValid(expectedEpoch, expectedBarId)) {
        throw IllegalStateException("Active session changed during inventory update.")
    }
}
