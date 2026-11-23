package com.ryanvidal.barfriend.core.network

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KtorBarAssistantApiTest {
    @Test
    fun `parses server version and login token`() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/api/server/version") -> respond(
                    content = """{"data":{"version":"v6.12.1","is_password_login_enabled":true,"is_ai_enabled":false}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                request.url.encodedPath.endsWith("/api/auth/login") -> respond(
                    content = """{"data":{"token":"1|secret-sanctum-token"}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("Not found", HttpStatusCode.NotFound)
            }
        }
        val sessionProvider = FakeSessionProvider()
        val api = KtorBarAssistantApi(createBarAssistantHttpClient(engine, sessionProvider), sessionProvider)

        val version = api.getServerVersion("https://bar.example.com")
        assertEquals("v6.12.1", version.version)
        assertTrue(version.isPasswordLoginEnabled)

        val token = api.login("https://bar.example.com", "user@example.com", "secret")
        assertEquals("1|secret-sanctum-token", token.token)
    }

    @Test
    fun `injects bearer token and bar id headers on authenticated requests`() = runTest {
        var authHeader: String? = null
        var barHeader: String? = null
        val engine = MockEngine { request ->
            authHeader = request.headers[HttpHeaders.Authorization]
            barHeader = request.headers[HEADER_BAR_ID]
            respond(
                content = """{"data":[{"id":42,"name":"Home Lounge","slug":"home-lounge"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val sessionProvider = FakeSessionProvider(
            serverUrl = "https://bar.example.com",
            token = "1|token-abc",
            barId = 7,
        )
        val api = KtorBarAssistantApi(createBarAssistantHttpClient(engine, sessionProvider), sessionProvider)

        val bars = api.getBars()
        assertEquals("Bearer 1|token-abc", authHeader)
        assertEquals("7", barHeader)
        assertEquals(listOf(42), bars.map { it.id })
    }

    @Test
    fun `triggers unauthorized callback and throws ApiException on 401`() = runTest {
        var unauthorizedCalled = false
        val engine = MockEngine {
            respond(
                content = """{"type":"api_error","message":"Unauthenticated."}""",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val sessionProvider = FakeSessionProvider(
            serverUrl = "https://bar.example.com",
            token = "expired",
            barId = 1,
            onUnauthorizedAction = { unauthorizedCalled = true },
        )
        val api = KtorBarAssistantApi(createBarAssistantHttpClient(engine, sessionProvider), sessionProvider)

        val ex = assertFailsWith<BarAssistantApiException> {
            api.getBars()
        }
        assertEquals(401, ex.statusCode)
        assertEquals("Unauthenticated.", ex.message)
        assertTrue(unauthorizedCalled)
    }

    private class FakeSessionProvider(
        private val serverUrl: String? = null,
        private val token: String? = null,
        private val barId: Int? = null,
        private val onUnauthorizedAction: suspend () -> Unit = {},
    ) : NetworkSessionProvider {
        override suspend fun currentServerUrl(): String? = serverUrl
        override suspend fun currentToken(): String? = token
        override suspend fun currentBarId(): Int? = barId
        override suspend fun onUnauthorized() = onUnauthorizedAction()
    }
}
