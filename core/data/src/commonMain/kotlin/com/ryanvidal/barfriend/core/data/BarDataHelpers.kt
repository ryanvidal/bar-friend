package com.ryanvidal.barfriend.core.data

import com.ryanvidal.barfriend.core.network.BarAssistantApi
import com.ryanvidal.barfriend.core.network.BarAssistantApiException
import kotlinx.coroutines.CancellationException

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
