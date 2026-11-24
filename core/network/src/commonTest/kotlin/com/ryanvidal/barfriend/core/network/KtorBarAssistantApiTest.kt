package com.ryanvidal.barfriend.core.network

import com.ryanvidal.barfriend.core.network.dto.CocktailIngredientSubstituteWriteDto
import com.ryanvidal.barfriend.core.network.dto.CocktailIngredientWriteDto
import com.ryanvidal.barfriend.core.network.dto.CocktailWriteRequestDto
import com.ryanvidal.barfriend.core.network.dto.ShoppingListIngredientInputDto
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.ContentType
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KtorBarAssistantApiTest {
    @Test
    fun `parses server version and login token without leaking active session headers`() = runTest {
        var loginBody = ""
        val seenAuth = mutableListOf<String?>()
        val seenBar = mutableListOf<String?>()
        var unauthorizedCalled = false
        val engine = MockEngine { request ->
            seenAuth += request.headers[HttpHeaders.Authorization]
            seenBar += request.headers[HEADER_BAR_ID]
            when {
                request.url.encodedPath.endsWith("/api/server/version") -> respond(
                    content = """{"data":{"version":"v6.12.1","is_password_login_enabled":true,"is_ai_enabled":false}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                request.url.encodedPath.endsWith("/api/auth/login") -> {
                    loginBody = request.body.readAsText()
                    respond(
                        content = """{"data":{"token":"1|secret-sanctum-token"}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> respond("Not found", HttpStatusCode.NotFound)
            }
        }
        val sessionProvider = FakeSessionProvider(
            serverUrl = "https://other.example.com",
            token = "1|existing-session-token",
            barId = 9,
            onUnauthorizedAction = { unauthorizedCalled = true },
        )
        val api = KtorBarAssistantApi(createBarAssistantHttpClient(engine, sessionProvider), sessionProvider)

        val version = api.getServerVersion("https://bar.example.com")
        assertEquals("v6.12.1", version.version)
        assertTrue(version.isPasswordLoginEnabled)

        val token = api.login("https://bar.example.com", "user@example.com", "secret")
        assertEquals("1|secret-sanctum-token", token.token)
        assertTrue(loginBody.contains("\"token_name\":\"Bar Friend Mobile\""), "Expected token_name in: $loginBody")
        assertEquals(listOf<String?>(null, null), seenAuth)
        assertEquals(listOf<String?>(null, null), seenBar)
        assertFalse(unauthorizedCalled)
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

        val bars = api.getBars().data
        assertEquals("Bearer 1|token-abc", authHeader)
        assertEquals("7", barHeader)
        assertEquals(listOf(42), bars.map { it.id })
    }

    @Test
    fun `explicit auth getBars sends explicit token and omits session bar id`() = runTest {
        var authHeader: String? = null
        var barHeader: String? = null
        var unauthorizedCalled = false
        val engine = MockEngine { request ->
            authHeader = request.headers[HttpHeaders.Authorization]
            barHeader = request.headers[HEADER_BAR_ID]
            respond(
                content = """{"data":[{"id":5,"name":"Explicit Bar","slug":"explicit-bar"}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val sessionProvider = FakeSessionProvider(
            serverUrl = "https://old.example.com",
            token = "1|old-token",
            barId = 99,
            onUnauthorizedAction = { unauthorizedCalled = true },
        )
        val api = KtorBarAssistantApi(createBarAssistantHttpClient(engine, sessionProvider), sessionProvider)

        val bars = api.getBars(serverUrl = "https://new.example.com", token = "1|new-token").data
        assertEquals("Bearer 1|new-token", authHeader)
        assertNull(barHeader)
        assertEquals(listOf(5), bars.map { it.id })
        assertFalse(unauthorizedCalled)
    }

    @Test
    fun `triggers unauthorized callback with failed token and throws ApiException on 401`() = runTest {
        var capturedToken: String? = null
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
            onUnauthorizedAction = { failedToken -> capturedToken = failedToken },
        )
        val api = KtorBarAssistantApi(createBarAssistantHttpClient(engine, sessionProvider), sessionProvider)

        val ex = assertFailsWith<BarAssistantApiException> {
            api.getBars()
        }
        assertEquals(401, ex.statusCode)
        assertEquals("Unauthenticated.", ex.message)
        assertEquals("expired", capturedToken)
    }

    @Test
    fun `createCocktail parses Location with query params and ignores bare cocktails location`() = runTest {
        val requests = mutableListOf<Pair<HttpMethod, String>>()
        val engine = MockEngine { request ->
            requests += request.method to request.url.encodedPath
            when {
                request.method == HttpMethod.Post && request.url.encodedPath == "/api/cocktails" -> respond(
                    content = "",
                    status = HttpStatusCode.Created,
                    headers = headersOf(HttpHeaders.Location, "/api/cocktails/negroni-1?bar_id=1#top"),
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

        val badLocationEngine = MockEngine { request ->
            respond(
                content = "",
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.Location, "/api/cocktails/"),
            )
        }
        val badApi = KtorBarAssistantApi(createBarAssistantHttpClient(badLocationEngine, sessionProvider), sessionProvider)
        assertFailsWith<BarAssistantApiException> {
            badApi.createCocktail(sampleWriteRequest())
        }
    }

    @Test
    fun `updateCocktail sends cocktail_method_id and explicit nulls then fetches updated cocktail`() = runTest {
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
        assertTrue(putBody.contains("\"source\":null"), "Expected explicit null source in JSON: $putBody")
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

    @Test
    fun `strips authorization and bar headers on cross-origin redirects for api and image clients`() = runTest {
        val apiHops = mutableListOf<Triple<String, String?, String?>>()
        val apiEngine = MockEngine { request ->
            apiHops += Triple(
                request.url.host,
                request.headers[HttpHeaders.Authorization],
                request.headers[HEADER_BAR_ID],
            )
            when (request.url.host) {
                "bar.example.com" -> respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://cdn.external.org/redirected.json"),
                )
                else -> respond(
                    content = """{"data":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val imageHops = mutableListOf<Pair<String, String?>>()
        val imageEngine = MockEngine { request ->
            imageHops += request.url.host to request.headers[HttpHeaders.Authorization]
            when (request.url.host) {
                "bar.example.com" -> respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://cdn.external.org/photo.jpg"),
                )
                else -> respond(content = "ok", status = HttpStatusCode.OK)
            }
        }
        val sessionProvider = FakeSessionProvider(
            serverUrl = "https://bar.example.com",
            token = "1|secret-token",
            barId = 7,
        )

        val apiClient = createBarAssistantHttpClient(apiEngine, sessionProvider)
        apiClient.get("https://bar.example.com/api/bars")
        assertEquals(
            listOf(
                Triple("bar.example.com", "Bearer 1|secret-token", "7"),
                Triple("cdn.external.org", null, null),
            ),
            apiHops,
        )

        val imageClient = ImageHttpClient(createImageHttpClient(imageEngine, sessionProvider))
        imageClient.client.get("https://bar.example.com/uploads/1.jpg")
        assertEquals(
            listOf(
                "bar.example.com" to "Bearer 1|secret-token",
                "cdn.external.org" to null,
            ),
            imageHops,
        )
    }

    @Test
    fun `getCocktail encodes slug path segments safely`() = runTest {
        var seenEncodedPath = ""
        val engine = MockEngine { request ->
            seenEncodedPath = request.url.encodedPath
            respond(
                content = """{"data":{"id":7,"slug":"pisco sour","name":"Pisco Sour","instructions":"Shake."}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val sessionProvider = FakeSessionProvider(
            serverUrl = "https://bar.example.com/bar",
            token = "1|token",
            barId = 1,
        )
        val api = KtorBarAssistantApi(createBarAssistantHttpClient(engine, sessionProvider), sessionProvider)

        api.getCocktail("pisco sour")
        assertEquals("/bar/api/cocktails/pisco%20sour", seenEncodedPath)
    }

    @Test
    fun `getProfile, recommendations, and extra cocktails hit expected endpoints and attach bar header`() = runTest {
        val seenRequests = mutableListOf<Triple<HttpMethod, String, String?>>()
        val engine = MockEngine { request ->
            seenRequests += Triple(request.method, request.url.encodedPath, request.headers[HEADER_BAR_ID])
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/api/profile" -> respond(
                    content = """{"data":{"id":99,"name":"Ryan","email":"ryan@example.com"}}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                request.method == HttpMethod.Get &&
                    request.url.encodedPath == "/api/bars/7/inventory/ingredients/recommend" -> respond(
                    content = """{"data":[{"id":12,"slug":"chartreuse","name":"Green Chartreuse","potential_cocktails":8}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                request.method == HttpMethod.Get &&
                    request.url.encodedPath == "/api/bars/7/inventory/ingredients/12/extra" -> respond(
                    content = """{"data":[{"id":101,"slug":"last-word","name":"Last Word"}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("Not found", HttpStatusCode.NotFound)
            }
        }
        val sessionProvider = FakeSessionProvider(
            serverUrl = "https://bar.example.com",
            token = "1|token",
            barId = 7,
        )
        val api = KtorBarAssistantApi(createBarAssistantHttpClient(engine, sessionProvider), sessionProvider)

        val profile = api.getProfile()
        assertEquals(99, profile.id)
        assertEquals("Ryan", profile.name)
        assertEquals("ryan@example.com", profile.email)

        val recommended = api.getRecommendedBarIngredients(barId = 7)
        assertEquals(1, recommended.size)
        assertEquals(12, recommended.single().id)
        assertEquals(8, recommended.single().potentialCocktails)

        val extra = api.getExtraCocktailsForIngredient(barId = 7, ingredientId = 12)
        assertEquals(1, extra.size)
        assertEquals("Last Word", extra.single().name)

        assertEquals(
            listOf<Triple<HttpMethod, String, String?>>(
                Triple(HttpMethod.Get, "/api/profile", "7"),
                Triple(HttpMethod.Get, "/api/bars/7/inventory/ingredients/recommend", "7"),
                Triple(HttpMethod.Get, "/api/bars/7/inventory/ingredients/12/extra", "7"),
            ),
            seenRequests,
        )
    }

    @Test
    fun `bar inventory batch store and delete send int array payloads and handle 204`() = runTest {
        val requests = mutableListOf<Triple<String, String?, String>>()
        val engine = MockEngine { request ->
            val body = request.body.readAsText()
            assertEquals(ContentType.Application.Json, request.body.contentType?.withoutParameters())
            requests += Triple(request.url.encodedPath, request.headers[HEADER_BAR_ID], body)
            when (request.url.encodedPath) {
                "/api/bars/7/inventory/ingredients/batch-store",
                "/api/bars/7/inventory/ingredients/batch-delete",
                -> respond(content = "", status = HttpStatusCode.NoContent)
                else -> respond(
                    content = """{"message":"Unexpected path"}""",
                    status = HttpStatusCode.NotFound,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val sessionProvider = FakeSessionProvider(
            serverUrl = "https://bar.example.com",
            token = "1|token",
            barId = 7,
        )
        val api = KtorBarAssistantApi(createBarAssistantHttpClient(engine, sessionProvider), sessionProvider)

        api.addBarInventoryIngredients(barId = 7, ingredientIds = listOf(1, 2, 3))
        api.removeBarInventoryIngredients(barId = 7, ingredientIds = listOf(4, 5))

        assertEquals(
            listOf<Triple<String, String?, String>>(
                Triple("/api/bars/7/inventory/ingredients/batch-store", "7", """{"ingredients":[1,2,3]}"""),
                Triple("/api/bars/7/inventory/ingredients/batch-delete", "7", """{"ingredients":[4,5]}"""),
            ),
            requests,
        )
    }

    @Test
    fun `shopping list get, batch store, and batch delete use object arrays and handle 204 and errors`() = runTest {
        val seenRequests = mutableListOf<Triple<HttpMethod, String, String>>()
        val seenBarHeaders = mutableListOf<String?>()
        val engine = MockEngine { request ->
            seenBarHeaders += request.headers[HEADER_BAR_ID]
            val body = request.body.readAsText()
            seenRequests += Triple(request.method, request.url.encodedPath, body)
            when {
                request.method == HttpMethod.Get && request.url.encodedPath == "/api/members/42/shopping-list" -> respond(
                    content = """{"data":[{"ingredient":{"id":10,"slug":"gin","name":"London Dry Gin"},"quantity":2},{"ingredient":{"id":11,"slug":"campari","name":"Campari"},"quantity":null}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                request.method == HttpMethod.Post && request.url.encodedPath == "/api/members/42/shopping-list/batch-store" -> {
                    assertEquals(ContentType.Application.Json, request.body.contentType?.withoutParameters())
                    respond(content = "", status = HttpStatusCode.NoContent)
                }
                request.method == HttpMethod.Post && request.url.encodedPath == "/api/members/42/shopping-list/batch-delete" -> {
                    assertEquals(ContentType.Application.Json, request.body.contentType?.withoutParameters())
                    respond(content = "", status = HttpStatusCode.NoContent)
                }
                request.method == HttpMethod.Post && request.url.encodedPath == "/api/members/99/shopping-list/batch-delete" -> respond(
                    content = """{"message":"Forbidden"}""",
                    status = HttpStatusCode.Forbidden,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond("Not found", HttpStatusCode.NotFound)
            }
        }
        val sessionProvider = FakeSessionProvider(
            serverUrl = "https://bar.example.com",
            token = "1|token",
            barId = 7,
        )
        val api = KtorBarAssistantApi(createBarAssistantHttpClient(engine, sessionProvider), sessionProvider)

        val items = api.getShoppingList(barId = 7, userId = 42)
        assertEquals(2, items.size)
        assertEquals(10, items[0].ingredient.id)
        assertEquals(2, items[0].quantity)
        assertEquals(11, items[1].ingredient.id)
        assertNull(items[1].quantity)

        api.addToShoppingList(
            barId = 8,
            userId = 42,
            items = listOf(
                ShoppingListIngredientInputDto(id = 10, quantity = 2),
                ShoppingListIngredientInputDto(id = 11),
            ),
        )
        api.removeFromShoppingList(barId = 7, userId = 42, ingredientIds = listOf(10, 11))

        val error = assertFailsWith<BarAssistantApiException> {
            api.removeFromShoppingList(barId = 8, userId = 99, ingredientIds = listOf(10))
        }
        assertEquals(403, error.statusCode)
        assertEquals("Forbidden", error.message)

        assertEquals(listOf<String?>("7", "8", "7", "8"), seenBarHeaders)
        assertEquals(
            listOf(
                Triple(HttpMethod.Get, "/api/members/42/shopping-list", ""),
                Triple(
                    HttpMethod.Post,
                    "/api/members/42/shopping-list/batch-store",
                    """{"ingredients":[{"id":10,"quantity":2},{"id":11,"quantity":1}]}""",
                ),
                Triple(
                    HttpMethod.Post,
                    "/api/members/42/shopping-list/batch-delete",
                    """{"ingredients":[{"id":10},{"id":11}]}""",
                ),
                Triple(
                    HttpMethod.Post,
                    "/api/members/99/shopping-list/batch-delete",
                    """{"ingredients":[{"id":10}]}""",
                ),
            ),
            seenRequests,
        )
    }

    @Test
    fun `getIngredients requests hierarchy and images includes and parses ingredient hierarchy`() = runTest {
        var seenInclude: String? = null
        val engine = MockEngine { request ->
            seenInclude = request.url.parameters["include"]
            respond(
                content = """
                    {
                      "data": [
                        {
                          "id": 10,
                          "slug": "london-dry-gin",
                          "name": "London Dry Gin",
                          "strength": 40.0,
                          "hierarchy": {
                            "root_ingredient_id": 1,
                            "path_to_self": "1.10",
                            "parent_ingredient": {"id": 1, "slug": "gin", "name": "Gin"},
                            "ancestors": [{"id": 1, "slug": "gin", "name": "Gin"}]
                          },
                          "images": [{"id": 3, "url": "/img.jpg", "thumb_url": "/thumb.jpg", "sort": 1}]
                        }
                      ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val sessionProvider = FakeSessionProvider(
            serverUrl = "https://bar.example.com",
            token = "1|token",
            barId = 7,
        )
        val api = KtorBarAssistantApi(createBarAssistantHttpClient(engine, sessionProvider), sessionProvider)

        val page = api.getIngredients(page = 1, perPage = 200)
        assertEquals("images,parentIngredient,ancestors", seenInclude)
        val dto = page.data.single()
        assertEquals(1, dto.hierarchy?.rootIngredientId)
        assertEquals(1, dto.hierarchy?.parentIngredient?.id)
        assertEquals(listOf("Gin"), dto.hierarchy?.ancestors?.map { it.name })
        assertEquals(1, dto.images.size)
    }

    @Test
    fun `getProfile overload uses explicit server url and bearer token without saved session`() = runTest {
        var seenAuth: String? = null
        var seenPath: String? = null
        val engine = MockEngine { request ->
            seenAuth = request.headers[HttpHeaders.Authorization]
            seenPath = request.url.encodedPath
            respond(
                content = """{"data":{"id":77,"name":"Ryan","email":"ryan@example.com"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val sessionProvider = FakeSessionProvider(serverUrl = null, token = null, barId = null)
        val api = KtorBarAssistantApi(createBarAssistantHttpClient(engine, sessionProvider), sessionProvider)

        val profile = api.getProfile(serverUrl = "https://bar.example.com", token = "2|explicit")
        assertEquals(77, profile.id)
        assertEquals("Bearer 2|explicit", seenAuth)
        assertEquals("/api/profile", seenPath)
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
        private val onUnauthorizedAction: suspend (String) -> Unit = {},
    ) : NetworkSessionProvider {
        override suspend fun currentServerUrl(): String? = serverUrl
        override suspend fun currentToken(): String? = token
        override suspend fun currentBarId(): Int? = barId
        override suspend fun onUnauthorized(failedToken: String) = onUnauthorizedAction(failedToken)
    }
}
