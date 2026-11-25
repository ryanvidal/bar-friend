package com.ryanvidal.barfriend.core.data

import com.ryanvidal.barfriend.core.model.CocktailUpsertRequest
import com.ryanvidal.barfriend.core.model.ScrapedCocktail
import com.ryanvidal.barfriend.core.model.UploadedImage
import com.ryanvidal.barfriend.core.network.BarAssistantApi
import com.ryanvidal.barfriend.core.network.dto.CocktailBasicDto
import com.ryanvidal.barfriend.core.network.dto.CocktailDto
import com.ryanvidal.barfriend.core.network.dto.CocktailMethodDto
import com.ryanvidal.barfriend.core.network.dto.GlassDto
import com.ryanvidal.barfriend.core.network.dto.IngredientBasicDto
import com.ryanvidal.barfriend.core.network.dto.IngredientDto
import com.ryanvidal.barfriend.core.network.dto.IngredientRecommendDto
import com.ryanvidal.barfriend.core.network.dto.ShoppingListIngredientInputDto
import com.ryanvidal.barfriend.core.network.dto.ShoppingListItemDto
import com.ryanvidal.barfriend.core.network.dto.TagDto
import com.ryanvidal.barfriend.core.network.dto.UtensilDto
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

data class BarSyncPayload(
    val cocktails: List<CocktailDto>,
    val ingredients: List<IngredientDto>,
    val glasses: List<GlassDto>,
    val methods: List<CocktailMethodDto>,
    val tags: List<TagDto>,
    val utensils: List<UtensilDto>,
    val shelfIngredients: List<IngredientBasicDto>,
    val shoppingListItems: List<ShoppingListItemDto>?,
)

data class ResolvedUserContext(
    val token: String?,
    val userId: Int,
)

interface BarRemoteDataSource {
    suspend fun fetchBarSyncPayload(barId: Int): BarSyncPayload
    suspend fun getCocktail(cocktailId: Int): CocktailDto
    suspend fun toggleFavorite(cocktailId: Int): Boolean
    suspend fun saveCocktail(cocktailId: Int?, request: CocktailUpsertRequest): CocktailDto
    suspend fun deleteCocktail(cocktailId: Int)
    suspend fun uploadImage(
        bytes: ByteArray,
        fileName: String,
        mimeType: String?,
        sort: Int,
        serverUrl: String?,
    ): UploadedImage
    suspend fun uploadImageFromUrl(
        imageUrl: String,
        copyright: String?,
        sort: Int,
        serverUrl: String?,
    ): UploadedImage
    suspend fun scrapeRecipeFromUrl(url: String): ScrapedCocktail
    suspend fun resolveUserContext(): ResolvedUserContext
    suspend fun getShoppingList(barId: Int): List<ShoppingListItemDto>
    suspend fun addToShoppingList(
        barId: Int,
        userContext: ResolvedUserContext,
        items: List<ShoppingListIngredientInputDto>,
    )
    suspend fun removeFromShoppingList(
        barId: Int,
        userContext: ResolvedUserContext,
        ingredientIds: List<Int>,
    )
    suspend fun setBarShelfIngredients(barId: Int, ingredientIds: List<Int>, inStock: Boolean)
    suspend fun getRecommendedBarIngredients(barId: Int): List<IngredientRecommendDto>
    suspend fun getExtraCocktailsForIngredient(barId: Int, ingredientId: Int): List<CocktailBasicDto>
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultBarRemoteDataSource(
    private val api: BarAssistantApi,
    private val sessionRepository: SessionRepository,
) : BarRemoteDataSource {

    override suspend fun fetchBarSyncPayload(barId: Int): BarSyncPayload = coroutineScope {
        val cocktailsDeferred = async {
            fetchAllPages { page -> api.getCocktails(page = page, perPage = 100) }
        }
        val ingredientsDeferred = async {
            fetchAllPages { page -> api.getIngredients(page = page, perPage = 200) }
        }
        val glassesDeferred = async {
            fetchAllPages { page -> api.getGlasses(page = page, perPage = 100) }
        }
        val methodsDeferred = async {
            fetchAllPages { page -> api.getCocktailMethods(page = page, perPage = 100) }
        }
        val tagsDeferred = async {
            fetchAllPages { page -> api.getTags(page = page, perPage = 100) }
        }
        val utensilsDeferred = async {
            fetchAllPages { page -> api.getUtensils(page = page, perPage = 100) }
        }
        val shelfDeferred = async {
            fetchAllPages { page -> api.getBarInventoryIngredientIds(barId = barId, page = page, perPage = 200) }
        }
        val shoppingListDeferred = async {
            runSuspendCatching {
                withResolvedUserIdAnd403Retry(api, sessionRepository) { userId ->
                    api.getShoppingList(barId = barId, userId = userId)
                }
            }.getOrNull()
        }

        BarSyncPayload(
            cocktails = cocktailsDeferred.await(),
            ingredients = ingredientsDeferred.await(),
            glasses = glassesDeferred.await(),
            methods = methodsDeferred.await(),
            tags = tagsDeferred.await(),
            utensils = utensilsDeferred.await(),
            shelfIngredients = shelfDeferred.await(),
            shoppingListItems = shoppingListDeferred.await(),
        )
    }

    override suspend fun getCocktail(cocktailId: Int): CocktailDto =
        api.getCocktail(cocktailId.toString())

    override suspend fun toggleFavorite(cocktailId: Int): Boolean =
        api.toggleFavorite(cocktailId)

    override suspend fun saveCocktail(
        cocktailId: Int?,
        request: CocktailUpsertRequest,
    ): CocktailDto {
        val writeDto = request.toWriteDto()
        return if (cocktailId == null) {
            api.createCocktail(writeDto)
        } else {
            api.updateCocktail(cocktailId, writeDto)
        }
    }

    override suspend fun deleteCocktail(cocktailId: Int) {
        api.deleteCocktail(cocktailId)
    }

    override suspend fun uploadImage(
        bytes: ByteArray,
        fileName: String,
        mimeType: String?,
        sort: Int,
        serverUrl: String?,
    ): UploadedImage = api.uploadImage(
        bytes = bytes,
        fileName = fileName,
        mimeType = mimeType,
        sort = sort,
    ).toUploadedImage(serverUrl)

    override suspend fun uploadImageFromUrl(
        imageUrl: String,
        copyright: String?,
        sort: Int,
        serverUrl: String?,
    ): UploadedImage = api.uploadImageFromUrl(
        imageUrl = imageUrl,
        copyright = copyright,
        sort = sort,
    ).toUploadedImage(serverUrl)

    override suspend fun scrapeRecipeFromUrl(url: String): ScrapedCocktail =
        api.scrapeCocktail(url).toModel()

    override suspend fun resolveUserContext(): ResolvedUserContext {
        val (token, userId) = resolveUserIdAndToken(api, sessionRepository)
        return ResolvedUserContext(token = token, userId = userId)
    }

    override suspend fun getShoppingList(barId: Int): List<ShoppingListItemDto> =
        withResolvedUserIdAnd403Retry(api, sessionRepository) { resolvedUserId ->
            api.getShoppingList(barId = barId, userId = resolvedUserId)
        }

    override suspend fun addToShoppingList(
        barId: Int,
        userContext: ResolvedUserContext,
        items: List<ShoppingListIngredientInputDto>,
    ) {
        executeWith403UserIdRetry(api, sessionRepository, userContext.userId, userContext.token) { resolvedId ->
            api.addToShoppingList(
                barId = barId,
                userId = resolvedId,
                items = items,
            )
        }
    }

    override suspend fun removeFromShoppingList(
        barId: Int,
        userContext: ResolvedUserContext,
        ingredientIds: List<Int>,
    ) {
        executeWith403UserIdRetry(api, sessionRepository, userContext.userId, userContext.token) { resolvedId ->
            api.removeFromShoppingList(
                barId = barId,
                userId = resolvedId,
                ingredientIds = ingredientIds,
            )
        }
    }

    override suspend fun setBarShelfIngredients(
        barId: Int,
        ingredientIds: List<Int>,
        inStock: Boolean,
    ) {
        if (inStock) {
            api.addBarInventoryIngredients(barId, ingredientIds)
        } else {
            api.removeBarInventoryIngredients(barId, ingredientIds)
        }
    }

    override suspend fun getRecommendedBarIngredients(barId: Int): List<IngredientRecommendDto> =
        api.getRecommendedBarIngredients(barId).distinctBy { it.id }

    override suspend fun getExtraCocktailsForIngredient(
        barId: Int,
        ingredientId: Int,
    ): List<CocktailBasicDto> =
        api.getExtraCocktailsForIngredient(barId, ingredientId).distinctBy { it.id }
}
