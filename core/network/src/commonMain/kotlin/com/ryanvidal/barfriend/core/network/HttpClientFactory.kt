package com.ryanvidal.barfriend.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json

expect fun createPlatformHttpClientEngine(): HttpClientEngine

class ImageHttpClient(val client: HttpClient)

internal val UnauthenticatedRequestKey = AttributeKey<Boolean>("BarFriendUnauthenticatedRequest")

internal data class ExplicitAuthContext(
    val serverUrl: String,
    val token: String,
)

internal val ExplicitAuthContextKey = AttributeKey<ExplicitAuthContext>("BarFriendExplicitAuth")

val BarAssistantJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
}

val BarAssistantWriteJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
    isLenient = true
}

fun createBarAssistantHttpClient(
    engine: HttpClientEngine,
    sessionProvider: NetworkSessionProvider,
): HttpClient {
    val client = HttpClient(engine) {
        install(ContentNegotiation) {
            json(BarAssistantJson)
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
        }
    }

    client.plugin(HttpSend).intercept { request ->
        if (!request.headers.contains(HttpHeaders.Accept)) {
            request.headers.append(HttpHeaders.Accept, "application/json")
        }

        val requestUrl = runCatching { request.url.build() }.getOrNull()
        when {
            request.attributes.contains(UnauthenticatedRequestKey) -> {
                request.headers.remove(HttpHeaders.Authorization)
                request.headers.remove(HEADER_BAR_ID)
                execute(request)
            }

            request.attributes.contains(ExplicitAuthContextKey) -> {
                val explicit = request.attributes[ExplicitAuthContextKey]
                if (requestUrl != null && matchesServerOrigin(requestUrl, explicit.serverUrl)) {
                    if (explicit.token.isNotBlank()) {
                        request.headers[HttpHeaders.Authorization] = "Bearer ${explicit.token}"
                    } else {
                        request.headers.remove(HttpHeaders.Authorization)
                    }
                } else {
                    request.headers.remove(HttpHeaders.Authorization)
                }
                request.headers.remove(HEADER_BAR_ID)
                execute(request)
            }

            else -> {
                val serverUrl = sessionProvider.currentServerUrl()
                val currentToken = sessionProvider.currentToken()?.takeIf { it.isNotBlank() }
                val sentToken: String? = if (
                    requestUrl != null &&
                    serverUrl != null &&
                    matchesServerOrigin(requestUrl, serverUrl)
                ) {
                    if (currentToken != null) {
                        if (!request.headers.contains(HttpHeaders.Authorization)) {
                            request.headers.append(HttpHeaders.Authorization, "Bearer $currentToken")
                        }
                    } else {
                        request.headers.remove(HttpHeaders.Authorization)
                    }
                    if (!request.headers.contains(HEADER_BAR_ID)) {
                        sessionProvider.currentBarId()?.let { barId ->
                            request.headers.append(HEADER_BAR_ID, barId.toString())
                        }
                    }
                    currentToken
                } else {
                    request.headers.remove(HttpHeaders.Authorization)
                    request.headers.remove(HEADER_BAR_ID)
                    null
                }

                val call = execute(request)
                if (call.response.status.value == 401 && sentToken != null) {
                    sessionProvider.onUnauthorized(sentToken)
                }
                call
            }
        }
    }

    return client
}

fun createImageHttpClient(
    engine: HttpClientEngine,
    sessionProvider: NetworkSessionProvider,
): HttpClient {
    val client = HttpClient(engine) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
        }
    }

    client.plugin(HttpSend).intercept { request ->
        val serverUrl = sessionProvider.currentServerUrl()
        val requestUrl = runCatching { request.url.build() }.getOrNull()
        if (serverUrl != null && requestUrl != null && matchesServerOrigin(requestUrl, serverUrl)) {
            if (!request.headers.contains(HttpHeaders.Authorization)) {
                sessionProvider.currentToken()?.takeIf { it.isNotBlank() }?.let { token ->
                    request.headers.append(HttpHeaders.Authorization, "Bearer $token")
                }
            }
        } else {
            request.headers.remove(HttpHeaders.Authorization)
        }
        execute(request)
    }

    return client
}

private fun matchesServerOrigin(requestUrl: Url, serverUrl: String): Boolean {
    val parsedServer = runCatching { Url(ServerUrlNormalizer.normalize(serverUrl)) }.getOrNull()
        ?: return false
    if (requestUrl.host.isBlank() || parsedServer.host.isBlank()) {
        return false
    }
    val userMatches = (requestUrl.user ?: "") == (parsedServer.user ?: "") &&
        (requestUrl.password ?: "") == (parsedServer.password ?: "")
    if (!userMatches) {
        return false
    }
    return requestUrl.protocol.name.equals(parsedServer.protocol.name, ignoreCase = true) &&
        requestUrl.host.equals(parsedServer.host, ignoreCase = true) &&
        requestUrl.port == parsedServer.port
}

const val HEADER_BAR_ID = "Bar-Assistant-Bar-Id"
