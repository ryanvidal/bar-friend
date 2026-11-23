package com.ryanvidal.barfriend.core.network

import com.ryanvidal.barfriend.core.network.dto.CocktailIngredientSubstituteWriteDto
import com.ryanvidal.barfriend.core.network.dto.CocktailIngredientWriteDto
import com.ryanvidal.barfriend.core.network.dto.CocktailWriteRequestDto
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.toByteArray
import io.ktor.utils.io.writer
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    @Test
    fun `createCocktail follows Location header to fetch created cocktail`() = runTest {
        val requests = mutableListOf<Pair<HttpMethod, String>>()
        val engine = MockEngine { request ->
            requests += request.method to request.url.encodedPath
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/api/cocktails" -> respond(
                    content = "",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.Location, "/api/cocktails/negroni-1"),
                )
                request.method == HttpMethod.Get && request.url.encodedPath == "/api/cocktails/negroni-1" -> respond(
                    content = """{"data":{"id":99,"slug":"negroni-1","name":"Negroni","instructions":"Stir with ice."}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("Not found", HttpStatusCode.NotFound)
            }
        }
        val sessionProvider = FakeSessionProvider(
            serverUrl = "https://bar.example.com",
            token = "1|token",
            barId = 1,
        )
        val api = KtorBarAssistantApi(createBarAssistantHttpClient(engine, sessionProvider), sessionProvider)

        val created = api.createCocktail(sampleWriteRequest())
        assertEquals(99, created.id)
        assertEquals("negroni-1", created.slug)
        assertEquals(
            listOf(
                HttpMethod.Post to "/api/cocktails",
                HttpMethod.Get to "/api/cocktails/negroni-1",
            ),
            requests,
        )
    }

    @Test
    fun `updateCocktail sends cocktail_method_id and fetches updated cocktail`() = runTest {
        var putBody = ""
        val requests = mutableListOf<Pair<HttpMethod, String>>()
        val engine = MockEngine { request ->
            requests += request.method to request.url.encodedPath
            when {
                request.method == HttpMethod.Put && request.url.encodedPath == "/api/cocktails/42" -> {
                    putBody = request.body.readAsText()
                    respond(content = "", status = HttpStatusCode.NoContent)
                }
                request.method == HttpMethod.Get && request.url.encodedPath == "/api/cocktails/42" -> respond(
                    content = """{"data":{"id":42,"slug":"negroni-42","name":"Negroni","instructions":"Stir."}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("Not found", HttpStatusCode.NotFound)
            }
        }
        val sessionProvider = FakeSessionProvider(
            serverUrl = "https://bar.example.com",
            token = "1|token",
            barId = 1,
        )
        val api = KtorBarAssistantApi(createBarAssistantHttpClient(engine, sessionProvider), sessionProvider)

        val updated = api.updateCocktail(42, sampleWriteRequest(methodId = 3))
        assertEquals(42, updated.id)
        assertTrue(putBody.contains("\"cocktail_method_id\":3"), "Expected cocktail_method_id in JSON: $putBody")
        assertEquals(
            listOf(
                HttpMethod.Put to "/api/cocktails/42",
                HttpMethod.Get to "/api/cocktails/42",
            ),
            requests,
        )
    }

    @Test
    fun `toggleFavorite and deleteCocktail hit expected endpoints`() = runTest {
        val requests = mutableListOf<Pair<HttpMethod, String>>()
        val engine = MockEngine { request ->
            requests += request.method to request.url.encodedPath
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/api/cocktails/12/toggle-favorite" ->
                    respond(
                        content = """{"data":{"id":12,"is_favorited":true}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                request.method == HttpMethod.Delete && request.url.encodedPath == "/api/cocktails/12" ->
                    respond(content = "", status = HttpStatusCode.NoContent)
                else -> respond("Not found", HttpStatusCode.NotFound)
            }
        }
        val sessionProvider = FakeSessionProvider(
            serverUrl = "https://bar.example.com",
            token = "1|token",
            barId = 1,
        )
        val api = KtorBarAssistantApi(createBarAssistantHttpClient(engine, sessionProvider), sessionProvider)

        assertTrue(api.toggleFavorite(12))
        api.deleteCocktail(12)
        assertEquals(
            listOf(
                HttpMethod.Post to "/api/cocktails/12/toggle-favorite",
                HttpMethod.Delete to "/api/cocktails/12",
            ),
            requests,
        )
    }

    @Test
    fun `uploadImage sends 1-based multipart fields and decodes response`() = runTest {
        var multipartBody = ""
        val engine = MockEngine { request ->
            multipartBody = request.body.readAsText()
            respond(
                content = """{"data":[{"id":55,"url":"https://bar.example.com/uploads/55.png","thumb_url":"https://bar.example.com/uploads/55_thumb.png","sort":2,"copyright":"Ryan"}]}""",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val sessionProvider = FakeSessionProvider(
            serverUrl = "https://bar.example.com",
            token = "1|token",
            barId = 1,
        )
        val api = KtorBarAssistantApi(createBarAssistantHttpClient(engine, sessionProvider), sessionProvider)

        val uploaded = api.uploadImage(
            bytes = byteArrayOf(1, 2, 3, 4),
            fileName = "negroni.png",
            mimeType = "image/png",
            sort = 2,
            copyright = "Ryan",
        )
        assertEquals(55, uploaded.id)
        assertEquals(2, uploaded.sort)
        assertTrue(multipartBody.contains("name=\"images[1][image]\""), "Missing image field in:\n$multipartBody")
        assertTrue(multipartBody.contains("filename=\"negroni.png\""), "Missing filename in:\n$multipartBody")
        assertTrue(multipartBody.contains("name=\"images[1][sort]\""), "Missing sort field in:\n$multipartBody")
        assertTrue(multipartBody.contains("name=\"images[1][copyright]\""), "Missing copyright field in:\n$multipartBody")
        assertTrue(multipartBody.contains("image/png"), "Missing content type in:\n$multipartBody")
    }

    @Test
    fun `scrapeCocktail posts source url and parses schema and scraper meta`() = runTest {
        var requestBody = ""
        val engine = MockEngine { request ->
            requestBody = request.body.readAsText()
            respond(
                content = """
                    {
                      "data": {
                        "schema_version": "2",
                        "schema": {
                          "name": "Daiquiri",
                          "instructions": "Shake with ice and strain.",
                          "glass": "Coupe",
                          "method": "Shake",
                          "tags": ["Classic", "Rum"],
                          "images": [{"uri": "https://recipes.example.com/daiquiri.jpg", "copyright": "Photo"}],
                          "ingredients": [{"name": "White Rum", "amount": 2.0, "units": "oz"}]
                        },
                        "scraper_meta": [{"ingredient_name": "White Rum", "source": "2 oz white rum"}]
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val sessionProvider = FakeSessionProvider(
            serverUrl = "https://bar.example.com",
            token = "1|token",
            barId = 1,
        )
        val api = KtorBarAssistantApi(createBarAssistantHttpClient(engine, sessionProvider), sessionProvider)

        val scraped = api.scrapeCocktail("https://recipes.example.com/daiquiri")
        assertTrue(requestBody.contains("\"source\":\"https://recipes.example.com/daiquiri\""))
        assertEquals("Daiquiri", scraped.schema.name)
        assertEquals("Coupe", scraped.schema.glass)
        assertEquals(1, scraped.schema.ingredients.size)
        assertEquals("2 oz white rum", scraped.scraperMeta.single().source)
    }

    @Test
    fun `ImageHttpClient attaches bearer token only for matching origin and ignores 401`() = runTest {
        var unauthorizedCalled = false
        val seenAuthHeaders = mutableListOf<Pair<String, String?>>()
        val seenAcceptHeaders = mutableListOf<String?>()
        val engine = MockEngine { request ->
            seenAuthHeaders += request.url.host to request.headers[HttpHeaders.Authorization]
            seenAcceptHeaders += request.headers[HttpHeaders.Accept]
            when (request.url.host) {
                "bar.example.com" -> respond(
                    content = "unauthorized",
                    status = HttpStatusCode.Unauthorized,
                )
                else -> respond(
                    content = "ok",
                    status = HttpStatusCode.OK,
                )
            }
        }
        val sessionProvider = FakeSessionProvider(
            serverUrl = "https://bar.example.com:8443",
            token = "1|secret-token",
            barId = 1,
            onUnauthorizedAction = { unauthorizedCalled = true },
        )
        val imageClient = ImageHttpClient(createImageHttpClient(engine, sessionProvider))

        val sameOriginResponse = imageClient.client.get("https://bar.example.com:8443/uploads/1.jpg")
        assertEquals(401, sameOriginResponse.status.value)
        assertFalse(unauthorizedCalled)

        imageClient.client.get("https://bar.example.com/uploads/1.jpg")
        imageClient.client.get("http://bar.example.com:8443/uploads/1.jpg")
        imageClient.client.get("https://cdn.external.org/photo.jpg")

        assertEquals(
            listOf(
                "bar.example.com" to "Bearer 1|secret-token",
                "bar.example.com" to null,
                "bar.example.com" to null,
                "cdn.external.org" to null,
            ),
            seenAuthHeaders,
        )
        seenAcceptHeaders.forEach { accept ->
            assertTrue(accept == null || accept == "*/*")
        }
    }

    private fun sampleWriteRequest(methodId: Int? = 1): CocktailWriteRequestDto =
        CocktailWriteRequestDto(
            name = "Negroni",
            instructions = "Stir with ice.",
            description = "Classic bitter aperitif",
            source = null,
            garnish = "Orange peel",
            glassId = 2,
            methodId = methodId,
            tags = listOf("Classic", "Bitter"),
            utensils = listOf(4),
            images = listOf(10),
            ingredients = listOf(
                CocktailIngredientWriteDto(
                    ingredientId = 1,
                    amount = 30.0,
                    amountMax = null,
                    units = "ml",
                    sort = 1,
                    optional = false,
                    isSpecified = false,
                    note = null,
                    substitutes = listOf(
                        CocktailIngredientSubstituteWriteDto(
                            ingredientId = 2,
                            amount = null,
                            amountMax = null,
                            units = null,
                        ),
                    ),
                ),
            ),
        )

    private suspend fun OutgoingContent.readAsText(): String = when (this) {
        is OutgoingContent.ByteArrayContent -> bytes().decodeToString()
        is OutgoingContent.WriteChannelContent -> coroutineScope {
            writer {
                writeTo(channel)
            }.channel.toByteArray().decodeToString()
        }
        else -> ""
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
