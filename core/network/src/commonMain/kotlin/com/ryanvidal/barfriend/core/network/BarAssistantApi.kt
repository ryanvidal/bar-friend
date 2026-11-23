package com.ryanvidal.barfriend.core.network

import com.ryanvidal.barfriend.core.network.dto.ApiErrorDto
import com.ryanvidal.barfriend.core.network.dto.BarDto
import com.ryanvidal.barfriend.core.network.dto.CocktailDto
import com.ryanvidal.barfriend.core.network.dto.DataEnvelope
import com.ryanvidal.barfriend.core.network.dto.LoginRequestDto
import com.ryanvidal.barfriend.core.network.dto.PaginatedEnvelope
import com.ryanvidal.barfriend.core.network.dto.ServerVersionDto
import com.ryanvidal.barfriend.core.network.dto.TokenDto
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

interface BarAssistantApi {
    suspend fun getServerVersion(serverUrl: String): ServerVersionDto
    suspend fun login(serverUrl: String, email: String, password: String): TokenDto
    suspend fun logout()
    suspend fun getBars(): List<BarDto>
    suspend fun getCocktails(
        page: Int = 1,
        perPage: Int = 25,
        query: String? = null,
    ): PaginatedEnvelope<CocktailDto>
    suspend fun getCocktail(idOrSlug: String): CocktailDto
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class KtorBarAssistantApi(
    private val httpClient: HttpClient,
    private val sessionProvider: NetworkSessionProvider,
) : BarAssistantApi {

    override suspend fun getServerVersion(serverUrl: String): ServerVersionDto {
        val base = ServerUrlNormalizer.apiBaseUrl(serverUrl)
        val response = httpClient.get("$base/server/version")
        return response.decodeSuccess<DataEnvelope<ServerVersionDto>>().data
    }

    override suspend fun login(serverUrl: String, email: String, password: String): TokenDto {
        val base = ServerUrlNormalizer.apiBaseUrl(serverUrl)
        val response = httpClient.post("$base/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequestDto(email = email, password = password))
        }
        return response.decodeSuccess<DataEnvelope<TokenDto>>().data
    }

    override suspend fun logout() {
        val base = requireApiBaseUrl()
        val response = httpClient.post("$base/auth/logout")
        if (!response.status.isSuccess()) {
            throw response.toApiException()
        }
    }

    override suspend fun getBars(): List<BarDto> {
        val base = requireApiBaseUrl()
        val response = httpClient.get("$base/bars")
        return response.decodeSuccess<PaginatedEnvelope<BarDto>>().data
    }

    override suspend fun getCocktails(
        page: Int,
        perPage: Int,
        query: String?,
    ): PaginatedEnvelope<CocktailDto> {
        val base = requireApiBaseUrl()
        val response = httpClient.get("$base/cocktails") {
            parameter("page", page)
            parameter("per_page", perPage)
            parameter("include", "glass,method,images,tags,utensils,ingredients.ingredient")
            if (!query.isNullOrBlank()) {
                parameter("filter[name]", query.trim())
            }
        }
        return response.decodeSuccess()
    }

    override suspend fun getCocktail(idOrSlug: String): CocktailDto {
        val base = requireApiBaseUrl()
        val response = httpClient.get("$base/cocktails/$idOrSlug")
        return response.decodeSuccess<DataEnvelope<CocktailDto>>().data
    }

    private suspend fun requireApiBaseUrl(): String {
        val serverUrl = sessionProvider.currentServerUrl()
            ?: throw BarAssistantApiException(0, "No Bar Assistant server URL configured.")
        return ServerUrlNormalizer.apiBaseUrl(serverUrl)
    }

    private suspend inline fun <reified T> HttpResponse.decodeSuccess(): T {
        if (status.isSuccess()) {
            return body()
        }
        throw toApiException()
    }

    private suspend fun HttpResponse.toApiException(): BarAssistantApiException {
        val rawBody = runCatching { bodyAsText() }.getOrNull().orEmpty()
        val parsed = runCatching {
            BarAssistantJson.decodeFromString<ApiErrorDto>(rawBody)
        }.getOrNull()
        val message = parsed?.message?.takeIf { it.isNotBlank() }
            ?: "Request failed with HTTP ${status.value}"
        return BarAssistantApiException(statusCode = status.value, message = message)
    }
}

@ContributesTo(AppScope::class)
@BindingContainer
object NetworkBindings {
    @SingleIn(AppScope::class)
    @Provides
    fun provideHttpClient(sessionProvider: NetworkSessionProvider): HttpClient =
        createBarAssistantHttpClient(
            engine = createPlatformHttpClientEngine(),
            sessionProvider = sessionProvider,
        )
}
