package com.ryanvidal.barfriend.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

expect fun createPlatformHttpClientEngine(): HttpClientEngine

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

const val HEADER_BAR_ID = "Bar-Assistant-Bar-Id"
