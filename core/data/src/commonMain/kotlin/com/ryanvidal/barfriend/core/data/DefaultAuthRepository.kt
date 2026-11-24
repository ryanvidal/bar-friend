package com.ryanvidal.barfriend.core.data

import com.ryanvidal.barfriend.core.model.Bar
import com.ryanvidal.barfriend.core.model.ServerInfo
import com.ryanvidal.barfriend.core.network.BarAssistantApi
import com.ryanvidal.barfriend.core.network.BarAssistantApiException
import com.ryanvidal.barfriend.core.network.ServerCompatibility
import com.ryanvidal.barfriend.core.network.ServerUrlNormalizer
import com.ryanvidal.barfriend.core.network.ServerVersionValidator
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultAuthRepository(
    private val api: BarAssistantApi,
    private val sessionRepository: SessionRepository,
) : AuthRepository {

    override suspend fun checkServer(serverUrl: String): ServerCheckResult {
        if (serverUrl.isBlank()) {
            return ServerCheckResult.Error("Please enter a Bar Assistant server URL.")
        }
        return try {
            val normalized = ServerUrlNormalizer.normalize(serverUrl)
            val dto = api.getServerVersion(normalized)
            when (ServerVersionValidator.validate(dto.version)) {
                is ServerCompatibility.Unsupported -> ServerCheckResult.UnsupportedVersion(
                    actual = dto.version,
                    minimum = ServerCompatibility.MINIMUM_VERSION,
                )

                is ServerCompatibility.Supported,
                is ServerCompatibility.Unknown,
                -> {
                    val serverInfo = ServerInfo(
                        version = dto.version,
                        latestVersion = dto.latestVersion,
                        isPasswordLoginEnabled = dto.isPasswordLoginEnabled,
                        isAiEnabled = dto.isAiEnabled,
                        isAiImageEnabled = dto.isAiImageEnabled,
                    )
                    if (!dto.isPasswordLoginEnabled) {
                        ServerCheckResult.PasswordLoginDisabled(serverInfo)
                    } else {
                        ServerCheckResult.Compatible(serverInfo)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: BarAssistantApiException) {
            ServerCheckResult.Error(e.message.ifBlank { "Server returned HTTP ${e.statusCode}." })
        } catch (e: Throwable) {
            ServerCheckResult.Error(
                e.message?.takeIf { it.isNotBlank() } ?: "Could not connect to Bar Assistant server.",
            )
        }
    }

    override suspend fun signIn(
        serverUrl: String,
        email: String,
        password: String,
    ): Result<Bar> {
        if (serverUrl.isBlank()) {
            return Result.failure(IllegalArgumentException("Server URL is required."))
        }
        if (email.isBlank()) {
            return Result.failure(IllegalArgumentException("Email is required."))
        }
        if (password.isBlank()) {
            return Result.failure(IllegalArgumentException("Password is required."))
        }

        val normalized = try {
            ServerUrlNormalizer.normalize(serverUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Result.failure(e)
        }

        when (val check = checkServer(normalized)) {
            is ServerCheckResult.Compatible -> Unit
            is ServerCheckResult.UnsupportedVersion -> return Result.failure(
                IllegalStateException(
                    "Bar Assistant ${check.actual} is not supported. Minimum required version is v${check.minimum}.",
                ),
            )

            is ServerCheckResult.PasswordLoginDisabled -> return Result.failure(
                IllegalStateException("Password login is disabled on this server."),
            )

            is ServerCheckResult.Error -> return Result.failure(IllegalStateException(check.message))
        }

        val tokenDto = try {
            api.login(normalized, email.trim(), password)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Result.failure(e)
        }

        val bars = try {
            fetchAllPages { page ->
                api.getBars(
                    serverUrl = normalized,
                    token = tokenDto.token,
                    page = page,
                    perPage = 100,
                )
            }.distinctBy { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return Result.failure(e)
        }

        if (bars.isEmpty()) {
            return Result.failure(IllegalStateException("No bars were found for this account."))
        }

        val chosen = bars.firstOrNull { it.status.equals("active", ignoreCase = true) } ?: bars.first()
        sessionRepository.saveSession(
            serverUrl = normalized,
            token = tokenDto.token,
            barId = chosen.id,
            barName = chosen.name,
        )

        return Result.success(
            Bar(
                id = chosen.id,
                name = chosen.name,
                subtitle = chosen.subtitle,
                description = chosen.description,
                slug = chosen.slug,
                isActive = chosen.status.equals("active", ignoreCase = true),
            ),
        )
    }

    override suspend fun signOut(): Result<Unit> = try {
        withTimeoutOrNull(LOGOUT_TIMEOUT_MILLIS) {
            runCatching { api.logout() }
                .onFailure { if (it is CancellationException) throw it }
        }
        withContext(NonCancellable) {
            sessionRepository.clearSession()
        }
        Result.success(Unit)
    } catch (e: CancellationException) {
        withContext(NonCancellable) {
            sessionRepository.clearSession()
        }
        throw e
    } catch (e: Throwable) {
        withContext(NonCancellable) {
            sessionRepository.clearSession()
        }
        Result.failure(e)
    }

    private companion object {
        const val LOGOUT_TIMEOUT_MILLIS = 3_000L
    }
}
