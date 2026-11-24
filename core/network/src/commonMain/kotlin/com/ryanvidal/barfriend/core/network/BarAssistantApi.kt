package com.ryanvidal.barfriend.core.network

import com.ryanvidal.barfriend.core.network.dto.ApiErrorDto
import com.ryanvidal.barfriend.core.network.dto.BarDto
import com.ryanvidal.barfriend.core.network.dto.CocktailDto
import com.ryanvidal.barfriend.core.network.dto.CocktailMethodDto
import com.ryanvidal.barfriend.core.network.dto.CocktailWriteRequestDto
import com.ryanvidal.barfriend.core.network.dto.DataEnvelope
import com.ryanvidal.barfriend.core.network.dto.GlassDto
import com.ryanvidal.barfriend.core.network.dto.ImageDto
import com.ryanvidal.barfriend.core.network.dto.IngredientBasicDto
import com.ryanvidal.barfriend.core.network.dto.IngredientDto
import com.ryanvidal.barfriend.core.network.dto.LoginRequestDto
import com.ryanvidal.barfriend.core.network.dto.PaginatedEnvelope
import com.ryanvidal.barfriend.core.network.dto.ScrapeRequestDto
import com.ryanvidal.barfriend.core.network.dto.ScrapeResponseDto
import com.ryanvidal.barfriend.core.network.dto.ServerVersionDto
import com.ryanvidal.barfriend.core.network.dto.TagDto
import com.ryanvidal.barfriend.core.network.dto.ToggleFavoriteDto
import com.ryanvidal.barfriend.core.network.dto.TokenDto
import com.ryanvidal.barfriend.core.network.dto.UtensilDto
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.appendPathSegments
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

interface BarAssistantApi {
    suspend fun getServerVersion(serverUrl: String): ServerVersionDto
    suspend fun login(serverUrl: String, email: String, password: String): TokenDto
    suspend fun logout()
    suspend fun getBars(page: Int = 1, perPage: Int = 100): PaginatedEnvelope<BarDto>
    suspend fun getBars(
        serverUrl: String,
        token: String,
        page: Int = 1,
        perPage: Int = 100,
    ): PaginatedEnvelope<BarDto>
    suspend fun getCocktails(
        page: Int = 1,
        perPage: Int = 25,
        query: String? = null,
    ): PaginatedEnvelope<CocktailDto>
    suspend fun getCocktail(idOrSlug: String): CocktailDto
    suspend fun createCocktail(request: CocktailWriteRequestDto): CocktailDto
    suspend fun updateCocktail(cocktailId: Int, request: CocktailWriteRequestDto): CocktailDto
    suspend fun deleteCocktail(cocktailId: Int)
    suspend fun toggleFavorite(cocktailId: Int): Boolean
    suspend fun uploadImage(
        bytes: ByteArray,
        fileName: String,
        mimeType: String?,
        sort: Int = 1,
        copyright: String? = null,
    ): ImageDto
    suspend fun uploadImageFromUrl(
        imageUrl: String,
        copyright: String? = null,
        sort: Int = 1,
    ): ImageDto
    suspend fun scrapeCocktail(url: String): ScrapeResponseDto
    suspend fun getIngredients(page: Int = 1, perPage: Int = 200): PaginatedEnvelope<IngredientDto>
    suspend fun getGlasses(page: Int = 1, perPage: Int = 100): PaginatedEnvelope<GlassDto>
    suspend fun getCocktailMethods(page: Int = 1, perPage: Int = 100): PaginatedEnvelope<CocktailMethodDto>
    suspend fun getTags(page: Int = 1, perPage: Int = 100): PaginatedEnvelope<TagDto>
    suspend fun getUtensils(page: Int = 1, perPage: Int = 100): PaginatedEnvelope<UtensilDto>
    suspend fun getBarInventoryIngredientIds(
        barId: Int,
        page: Int = 1,
        perPage: Int = 200,
    ): PaginatedEnvelope<IngredientBasicDto>
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
        val response = httpClient.get("$base/server/version") {
            attributes.put(UnauthenticatedRequestKey, true)
        }
        return response.decodeSuccess<DataEnvelope<ServerVersionDto>>().data
    }

    override suspend fun login(serverUrl: String, email: String, password: String): TokenDto {
        val base = ServerUrlNormalizer.apiBaseUrl(serverUrl)
        val response = httpClient.post("$base/auth/login") {
            attributes.put(UnauthenticatedRequestKey, true)
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

    override suspend fun getBars(page: Int, perPage: Int): PaginatedEnvelope<BarDto> {
        val base = requireApiBaseUrl()
        val response = httpClient.get("$base/bars") {
            parameter("page", page)
            parameter("per_page", perPage)
        }
        return response.decodeSuccess()
    }

    override suspend fun getBars(
        serverUrl: String,
        token: String,
        page: Int,
        perPage: Int,
    ): PaginatedEnvelope<BarDto> {
        val normalized = ServerUrlNormalizer.normalize(serverUrl)
        val base = ServerUrlNormalizer.apiBaseUrl(normalized)
        val response = httpClient.get("$base/bars") {
            attributes.put(ExplicitAuthContextKey, ExplicitAuthContext(normalized, token))
            parameter("page", page)
            parameter("per_page", perPage)
        }
        return response.decodeSuccess()
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
        val response = httpClient.get {
            url {
                takeFrom(base)
                appendPathSegments("cocktails", idOrSlug)
            }
        }
        return response.decodeSuccess<DataEnvelope<CocktailDto>>().data
    }

    override suspend fun createCocktail(request: CocktailWriteRequestDto): CocktailDto {
        val base = requireApiBaseUrl()
        val jsonBody = BarAssistantWriteJson.encodeToString(CocktailWriteRequestDto.serializer(), request)
        val response = httpClient.post("$base/cocktails") {
            setBody(TextContent(jsonBody, ContentType.Application.Json))
        }
        if (!response.status.isSuccess()) {
            throw response.toApiException()
        }
        val slugOrId = response.headers[HttpHeaders.Location]?.let { location ->
            parseCocktailSlugOrIdFromLocation(base, location)
        }
        if (slugOrId != null) {
            return getCocktail(slugOrId)
        }
        val rawBody = runCatching { response.bodyAsText() }.getOrNull().orEmpty()
        if (rawBody.isNotBlank()) {
            val parsed = runCatching {
                BarAssistantJson.decodeFromString<DataEnvelope<CocktailDto>>(rawBody).data
            }.getOrNull()
            if (parsed != null) {
                return parsed
            }
        }
        throw BarAssistantApiException(
            statusCode = response.status.value,
            message = "Created cocktail response was missing a Location header.",
        )
    }

    override suspend fun updateCocktail(
        cocktailId: Int,
        request: CocktailWriteRequestDto,
    ): CocktailDto {
        val base = requireApiBaseUrl()
        val jsonBody = BarAssistantWriteJson.encodeToString(CocktailWriteRequestDto.serializer(), request)
        val response = httpClient.put("$base/cocktails/$cocktailId") {
            setBody(TextContent(jsonBody, ContentType.Application.Json))
        }
        if (!response.status.isSuccess()) {
            throw response.toApiException()
        }
        return getCocktail(cocktailId.toString())
    }

    override suspend fun deleteCocktail(cocktailId: Int) {
        val base = requireApiBaseUrl()
        val response = httpClient.delete("$base/cocktails/$cocktailId")
        if (!response.status.isSuccess()) {
            throw response.toApiException()
        }
    }

    override suspend fun toggleFavorite(cocktailId: Int): Boolean {
        val base = requireApiBaseUrl()
        val response = httpClient.post("$base/cocktails/$cocktailId/toggle-favorite")
        return response.decodeSuccess<DataEnvelope<ToggleFavoriteDto>>().data.isFavorited
    }

    override suspend fun uploadImage(
        bytes: ByteArray,
        fileName: String,
        mimeType: String?,
        sort: Int,
        copyright: String?,
    ): ImageDto {
        val base = requireApiBaseUrl()
        val resolvedMimeType = mimeType?.takeIf { it.isNotBlank() } ?: "image/jpeg"
        val safeFileName = (fileName.takeIf { it.isNotBlank() } ?: "upload.jpg")
            .replace("\"", "")
            .replace("\r", "")
            .replace("\n", "")
        val response = httpClient.submitFormWithBinaryData(
            url = "$base/images",
            formData = formData {
                append(
                    key = "images[1][image]",
                    value = bytes,
                    headers = Headers.build {
                        append(HttpHeaders.ContentType, resolvedMimeType)
                        append(HttpHeaders.ContentDisposition, "filename=\"$safeFileName\"")
                    },
                )
                append("images[1][sort]", sort.toString())
                append("images[1][copyright]", copyright.orEmpty())
            },
        )
        val images = response.decodeSuccess<DataEnvelope<List<ImageDto>>>().data
        return images.firstOrNull()
            ?: throw BarAssistantApiException(response.status.value, "Image upload succeeded without image payload.")
    }

    override suspend fun uploadImageFromUrl(
        imageUrl: String,
        copyright: String?,
        sort: Int,
    ): ImageDto {
        val base = requireApiBaseUrl()
        val response = httpClient.submitFormWithBinaryData(
            url = "$base/images",
            formData = formData {
                append("images[1][image]", imageUrl)
                append("images[1][sort]", sort.toString())
                append("images[1][copyright]", copyright.orEmpty())
            },
        )
        val images = response.decodeSuccess<DataEnvelope<List<ImageDto>>>().data
        return images.firstOrNull()
            ?: throw BarAssistantApiException(response.status.value, "Image upload succeeded without image payload.")
    }

    override suspend fun scrapeCocktail(url: String): ScrapeResponseDto {
        val base = requireApiBaseUrl()
        val response = httpClient.post("$base/import/scrape") {
            contentType(ContentType.Application.Json)
            setBody(ScrapeRequestDto(source = url))
        }
        if (!response.status.isSuccess()) {
            throw response.toApiException()
        }
        val rawBody = response.bodyAsText()
        val root = BarAssistantJson.parseToJsonElement(rawBody)
        return if (root is JsonObject && "data" in root) {
            BarAssistantJson.decodeFromJsonElement<DataEnvelope<ScrapeResponseDto>>(root).data
        } else {
            BarAssistantJson.decodeFromJsonElement<ScrapeResponseDto>(root)
        }
    }

    override suspend fun getIngredients(page: Int, perPage: Int): PaginatedEnvelope<IngredientDto> {
        val base = requireApiBaseUrl()
        val response = httpClient.get("$base/ingredients") {
            parameter("page", page)
            parameter("per_page", perPage)
        }
        return response.decodeSuccess()
    }

    override suspend fun getGlasses(page: Int, perPage: Int): PaginatedEnvelope<GlassDto> {
        val base = requireApiBaseUrl()
        val response = httpClient.get("$base/glasses") {
            parameter("page", page)
            parameter("per_page", perPage)
        }
        return response.decodeSuccess()
    }

    override suspend fun getCocktailMethods(
        page: Int,
        perPage: Int,
    ): PaginatedEnvelope<CocktailMethodDto> {
        val base = requireApiBaseUrl()
        val response = httpClient.get("$base/cocktail-methods") {
            parameter("page", page)
            parameter("per_page", perPage)
        }
        return response.decodeSuccess()
    }

    override suspend fun getTags(page: Int, perPage: Int): PaginatedEnvelope<TagDto> {
        val base = requireApiBaseUrl()
        val response = httpClient.get("$base/tags") {
            parameter("page", page)
            parameter("per_page", perPage)
        }
        return response.decodeSuccess()
    }

    override suspend fun getUtensils(page: Int, perPage: Int): PaginatedEnvelope<UtensilDto> {
        val base = requireApiBaseUrl()
        val response = httpClient.get("$base/utensils") {
            parameter("page", page)
            parameter("per_page", perPage)
        }
        return response.decodeSuccess()
    }

    override suspend fun getBarInventoryIngredientIds(
        barId: Int,
        page: Int,
        perPage: Int,
    ): PaginatedEnvelope<IngredientBasicDto> {
        val base = requireApiBaseUrl()
        val response = httpClient.get("$base/bars/$barId/inventory/ingredients") {
            parameter("page", page)
            parameter("per_page", perPage)
        }
        return response.decodeSuccess()
    }

    private fun parseCocktailSlugOrIdFromLocation(base: String, location: String): String? {
        val parsed = runCatching {
            URLBuilder(Url(base)).takeFrom(location).build()
        }.getOrNull() ?: return null
        return parsed.segments
            .lastOrNull { it.isNotBlank() }
            ?.takeIf { !it.equals("cocktails", ignoreCase = true) }
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

    @SingleIn(AppScope::class)
    @Provides
    fun provideImageHttpClient(sessionProvider: NetworkSessionProvider): ImageHttpClient =
        ImageHttpClient(
            createImageHttpClient(
                engine = createPlatformHttpClientEngine(),
                sessionProvider = sessionProvider,
            ),
        )
}
