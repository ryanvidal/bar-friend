package com.ryanvidal.barfriend.feature.cocktails

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.ryanvidal.barfriend.core.data.CocktailRepository
import com.ryanvidal.barfriend.core.data.SyncStatus
import com.ryanvidal.barfriend.core.model.BarCatalog
import com.ryanvidal.barfriend.core.model.CocktailDetail
import com.ryanvidal.barfriend.core.model.CocktailFilter
import com.ryanvidal.barfriend.core.model.CocktailImage
import com.ryanvidal.barfriend.core.model.CocktailIngredient
import com.ryanvidal.barfriend.core.model.CocktailMethod
import com.ryanvidal.barfriend.core.model.CocktailSort
import com.ryanvidal.barfriend.core.model.CocktailSummary
import com.ryanvidal.barfriend.core.model.CocktailTag
import com.ryanvidal.barfriend.core.model.CocktailUpsertRequest
import com.ryanvidal.barfriend.core.model.Glass
import com.ryanvidal.barfriend.core.model.IngredientSummary
import com.ryanvidal.barfriend.core.model.ScrapedCocktail
import com.ryanvidal.barfriend.core.model.UploadedImage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

internal class InMemoryPreferencesDataStore(
    initial: Preferences = emptyPreferences(),
) : DataStore<Preferences> {
    private val state = MutableStateFlow(initial)
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}

internal data class LocalUploadCall(
    val bytes: ByteArray,
    val fileName: String,
    val mimeType: String?,
    val sort: Int,
)

internal data class RemoteUploadCall(
    val imageUrl: String,
    val copyright: String?,
    val sort: Int,
)

internal data class SaveCocktailCall(
    val cocktailId: Int?,
    val request: CocktailUpsertRequest,
)

internal class FakeCocktailRepository(
    initialCocktails: List<CocktailSummary> = emptyList(),
    initialDetails: Map<Int, CocktailDetail> = emptyMap(),
    initialCatalog: BarCatalog = sampleCatalog(),
) : CocktailRepository {
    private val _syncStatus = MutableStateFlow(SyncStatus())
    override val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val cocktailsFlow = MutableStateFlow(initialCocktails)
    private val detailsFlow = MutableStateFlow(initialDetails)
    private val catalogFlow = MutableStateFlow(initialCatalog)

    var refreshResultOverride: ((Int) -> Result<CocktailDetail>)? = null
    var toggleFavoriteFailure: Throwable? = null
    var deleteFailure: Throwable? = null
    var scrapeResult: Result<ScrapedCocktail> = Result.failure(IllegalStateException("No scrape configured"))
    var uploadLocalFailure: Throwable? = null
    var uploadRemoteFailure: Throwable? = null
    var saveFailure: Throwable? = null

    val refreshedCocktailIds = mutableListOf<Int>()
    val toggledFavoriteIds = mutableListOf<Int>()
    val deletedCocktailIds = mutableListOf<Int>()
    val scrapedUrls = mutableListOf<String>()
    val localUploads = mutableListOf<LocalUploadCall>()
    val remoteUploads = mutableListOf<RemoteUploadCall>()
    val saveCalls = mutableListOf<SaveCocktailCall>()
    var syncAllCalls = 0

    private var nextImageId = 500
    private var nextCocktailId = 900

    override fun observeCocktails(filter: CocktailFilter): Flow<List<CocktailSummary>> =
        cocktailsFlow.map { items ->
            val filtered = items.filter { item ->
                val matchesQuery = filter.query.isBlank() ||
                    item.name.contains(filter.query.trim(), ignoreCase = true) ||
                    item.missingIngredientNames.any { it.contains(filter.query.trim(), ignoreCase = true) }
                val matchesCanMake = !filter.canMakeOnly || item.inBarShelf || item.missingIngredientsCount == 0
                val matchesFavorites = !filter.favoritesOnly || item.isFavorite
                val matchesTags = filter.tagIds.isEmpty() || item.tags.any { it.id in filter.tagIds }
                val matchesGlass = filter.glassId == null ||
                    catalogFlow.value.glasses.firstOrNull { it.id == filter.glassId }?.name == item.glassName
                val matchesMethod = filter.methodId == null ||
                    catalogFlow.value.methods.firstOrNull { it.id == filter.methodId }?.name == item.methodName
                val matchesMinAbv = filter.abvMin == null || (item.abv ?: 0.0) >= filter.abvMin!!
                val matchesMaxAbv = filter.abvMax == null || (item.abv ?: 0.0) <= filter.abvMax!!
                matchesQuery &&
                    matchesCanMake &&
                    matchesFavorites &&
                    matchesTags &&
                    matchesGlass &&
                    matchesMethod &&
                    matchesMinAbv &&
                    matchesMaxAbv
            }
            when (filter.sort) {
                CocktailSort.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
                CocktailSort.NAME_DESC -> filtered.sortedByDescending { it.name.lowercase() }
                CocktailSort.ABV_DESC -> filtered.sortedByDescending { it.abv ?: Double.NEGATIVE_INFINITY }
                CocktailSort.ABV_ASC -> filtered.sortedBy { it.abv ?: Double.POSITIVE_INFINITY }
                CocktailSort.MISSING_ASC -> filtered.sortedBy { it.missingIngredientsCount }
            }
        }

    override fun observeCocktailDetail(cocktailId: Int): Flow<CocktailDetail?> =
        detailsFlow.map { it[cocktailId] }

    override fun observeCatalog(): Flow<BarCatalog> = catalogFlow

    override suspend fun syncAll(force: Boolean): Result<Unit> {
        syncAllCalls += 1
        return Result.success(Unit)
    }

    override suspend fun refreshCocktail(cocktailId: Int): Result<CocktailDetail> {
        refreshedCocktailIds += cocktailId
        refreshResultOverride?.let { return it(cocktailId) }
        val detail = detailsFlow.value[cocktailId]
        return if (detail != null) {
            Result.success(detail)
        } else {
            Result.failure(NoSuchElementException("Cocktail $cocktailId not found"))
        }
    }

    override suspend fun toggleFavorite(cocktailId: Int): Result<Boolean> {
        toggledFavoriteIds += cocktailId
        toggleFavoriteFailure?.let { return Result.failure(it) }
        val current = detailsFlow.value[cocktailId]?.isFavorite
            ?: cocktailsFlow.value.firstOrNull { it.id == cocktailId }?.isFavorite
            ?: false
        val next = !current
        detailsFlow.update { map ->
            val existing = map[cocktailId] ?: return@update map
            map + (cocktailId to existing.copy(isFavorite = next))
        }
        cocktailsFlow.update { list ->
            list.map { item ->
                if (item.id == cocktailId) item.copy(isFavorite = next) else item
            }
        }
        return Result.success(next)
    }

    override suspend fun saveCocktail(
        cocktailId: Int?,
        request: CocktailUpsertRequest,
    ): Result<CocktailDetail> {
        saveCalls += SaveCocktailCall(cocktailId, request)
        saveFailure?.let { return Result.failure(it) }
        val resolvedId = cocktailId ?: nextCocktailId++
        val catalog = catalogFlow.value
        val detail = CocktailDetail(
            id = resolvedId,
            slug = request.name.lowercase().replace(' ', '-'),
            name = request.name,
            shortDescription = request.description,
            instructions = request.instructions,
            garnish = request.garnish,
            description = request.description,
            source = request.source,
            abv = 18.0,
            glass = request.glassId?.let { gid -> catalog.glasses.firstOrNull { it.id == gid } },
            method = request.methodId?.let { mid -> catalog.methods.firstOrNull { it.id == mid } },
            images = request.imageIds.mapIndexed { idx, imgId ->
                CocktailImage(
                    id = imgId,
                    url = "https://bar.example/images/$imgId.jpg",
                    thumbUrl = "https://bar.example/images/$imgId-thumb.jpg",
                    sort = idx + 1,
                    placeholderHash = null,
                    copyright = null,
                )
            },
            tags = request.tags.mapIndexed { idx, name -> CocktailTag(id = idx + 1, name = name) },
            utensils = emptyList(),
            ingredients = request.ingredients.mapIndexed { idx, input ->
                CocktailIngredient(
                    id = idx + 1,
                    ingredientId = input.ingredientId,
                    ingredientSlug = input.ingredientName.lowercase().replace(' ', '-'),
                    name = input.ingredientName,
                    sort = input.sort,
                    amount = input.amount,
                    amountMax = input.amountMax,
                    units = input.units,
                    optional = input.optional,
                    note = input.note,
                    inBarShelf = true,
                    substitutes = emptyList(),
                )
            },
            isFavorite = detailsFlow.value[resolvedId]?.isFavorite ?: false,
            inBarShelf = true,
            missingIngredientsCount = 0,
        )
        detailsFlow.update { it + (resolvedId to detail) }
        return Result.success(detail)
    }

    override suspend fun deleteCocktail(cocktailId: Int): Result<Unit> {
        deletedCocktailIds += cocktailId
        deleteFailure?.let { return Result.failure(it) }
        detailsFlow.update { it - cocktailId }
        cocktailsFlow.update { list -> list.filterNot { it.id == cocktailId } }
        return Result.success(Unit)
    }

    override suspend fun uploadImage(
        bytes: ByteArray,
        fileName: String,
        mimeType: String?,
        sort: Int,
    ): Result<UploadedImage> {
        localUploads += LocalUploadCall(bytes, fileName, mimeType, sort)
        uploadLocalFailure?.let { return Result.failure(it) }
        val id = nextImageId++
        return Result.success(
            UploadedImage(
                id = id,
                url = "https://bar.example/uploads/$id.jpg",
                thumbUrl = "https://bar.example/uploads/$id-thumb.jpg",
                sort = sort,
            ),
        )
    }

    override suspend fun uploadImageFromUrl(
        imageUrl: String,
        copyright: String?,
        sort: Int,
    ): Result<UploadedImage> {
        remoteUploads += RemoteUploadCall(imageUrl, copyright, sort)
        uploadRemoteFailure?.let { return Result.failure(it) }
        val id = nextImageId++
        return Result.success(
            UploadedImage(
                id = id,
                url = imageUrl,
                thumbUrl = imageUrl,
                sort = sort,
                copyright = copyright,
            ),
        )
    }

    override suspend fun scrapeRecipeFromUrl(url: String): Result<ScrapedCocktail> {
        scrapedUrls += url
        return scrapeResult
    }
}

internal fun sampleCatalog(): BarCatalog = BarCatalog(
    ingredients = listOf(
        IngredientSummary(1, "gin", "London Dry Gin", 40.0, "Gin", null, inBarShelf = true, inShoppingList = false),
        IngredientSummary(2, "campari", "Campari", 25.0, "Amaro", null, inBarShelf = true, inShoppingList = false),
        IngredientSummary(3, "sweet-vermouth", "Sweet Vermouth", 16.0, "Vermouth", null, inBarShelf = false, inShoppingList = true),
        IngredientSummary(4, "lime-juice", "Lime Juice", 0.0, "Citrus", null, inBarShelf = true, inShoppingList = false),
    ),
    glasses = listOf(
        Glass(10, "Rocks", 250.0, "ml"),
        Glass(11, "Coupe", 160.0, "ml"),
    ),
    methods = listOf(
        CocktailMethod(20, "Stir", 20),
        CocktailMethod(21, "Shake", 25),
    ),
    tags = listOf(
        CocktailTag(30, "Classic"),
        CocktailTag(31, "Bitter"),
        CocktailTag(32, "Citrus"),
    ),
    utensils = emptyList(),
)

internal fun sampleCocktailDetail(id: Int = 1): CocktailDetail = CocktailDetail(
    id = id,
    slug = "negroni",
    name = "Negroni",
    shortDescription = "Equal parts bitter Italian aperitivo.",
    instructions = "1. Add ingredients to a mixing glass with ice.\n2. Stir until chilled.\n3. Strain over a large ice cube.",
    garnish = "Orange peel",
    description = "Equal parts gin, Campari, and sweet vermouth.",
    source = "IBA",
    abv = 24.0,
    glass = Glass(10, "Rocks", 250.0, "ml"),
    method = CocktailMethod(20, "Stir", 20),
    images = listOf(
        CocktailImage(101, "https://bar.example/negroni.jpg", "https://bar.example/negroni-thumb.jpg", 1, null, null),
    ),
    tags = listOf(CocktailTag(30, "Classic"), CocktailTag(31, "Bitter")),
    utensils = emptyList(),
    ingredients = listOf(
        CocktailIngredient(1, 1, "gin", "London Dry Gin", 1, 30.0, null, "ml", false, null, inBarShelf = true, substitutes = emptyList()),
        CocktailIngredient(2, 2, "campari", "Campari", 2, 30.0, null, "ml", false, null, inBarShelf = true, substitutes = emptyList()),
        CocktailIngredient(3, 3, "sweet-vermouth", "Sweet Vermouth", 3, 30.0, null, "ml", false, null, inBarShelf = false, substitutes = emptyList()),
    ),
    isFavorite = false,
    inBarShelf = false,
    missingIngredientsCount = 1,
)
