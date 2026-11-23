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
import kotlinx.serialization.json.Json

expect fun createPlatformHttpClientEngine(): HttpClientEngine

class ImageHttpClient(val client: HttpClient)

val BarAssistantJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
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
        if (!request.headers.contains(HttpHeaders.Authorization)) {
            sessionProvider.currentToken()?.takeIf { it.isNotBlank() }?.let { token ->
                request.headers.append(HttpHeaders.Authorization, "Bearer $token")
            }
        }
        if (!request.headers.contains(HEADER_BAR_ID)) {
            sessionProvider.currentBarId()?.let { barId ->
                request.headers.append(HEADER_BAR_ID, barId.toString())
            }
        }

        val call = execute(request)
        if (call.response.status.value == 401 && sessionProvider.currentToken() != null) {
            sessionProvider.onUnauthorized()
        }
        call
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
        if (
            serverUrl != null &&
            requestUrl != null &&
            matchesServerOrigin(requestUrl, serverUrl) &&
            !request.headers.contains(HttpHeaders.Authorization)
        ) {
            sessionProvider.currentToken()?.takeIf { it.isNotBlank() }?.let { token ->
                request.headers.append(HttpHeaders.Authorization, "Bearer $token")
            }
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
    return requestUrl.protocol.name.equals(parsedServer.protocol.name, ignoreCase = true) &&
        requestUrl.host.equals(parsedServer.host, ignoreCase = true) &&
        requestUrl.port == parsedServer.port
}

const val HEADER_BAR_ID = "Bar-Assistant-Bar-Id"
