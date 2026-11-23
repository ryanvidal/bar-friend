package com.ryanvidal.barfriend.core.data

import com.ryanvidal.barfriend.core.database.dao.CatalogDao
import com.ryanvidal.barfriend.core.database.dao.CocktailDao
import com.ryanvidal.barfriend.core.database.dao.CocktailWithIngredients
import com.ryanvidal.barfriend.core.database.dao.InventoryDao
import com.ryanvidal.barfriend.core.database.entity.SyncMetadataEntity
import com.ryanvidal.barfriend.core.model.BarCatalog
import com.ryanvidal.barfriend.core.model.CocktailDetail
import com.ryanvidal.barfriend.core.model.CocktailFilter
import com.ryanvidal.barfriend.core.model.CocktailMethod
import com.ryanvidal.barfriend.core.model.CocktailSummary
import com.ryanvidal.barfriend.core.model.CocktailUpsertRequest
import com.ryanvidal.barfriend.core.model.Glass
import com.ryanvidal.barfriend.core.model.ScrapedCocktail
import com.ryanvidal.barfriend.core.model.UploadedImage
import com.ryanvidal.barfriend.core.network.BarAssistantApi
import com.ryanvidal.barfriend.core.network.dto.CocktailDto
import com.ryanvidal.barfriend.core.network.dto.CocktailMethodDto
import com.ryanvidal.barfriend.core.network.dto.GlassDto
import com.ryanvidal.barfriend.core.network.dto.IngredientBasicDto
import com.ryanvidal.barfriend.core.network.dto.IngredientDto
import com.ryanvidal.barfriend.core.network.dto.PaginatedEnvelope
import com.ryanvidal.barfriend.core.network.dto.TagDto
import com.ryanvidal.barfriend.core.network.dto.UtensilDto
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlin.time.Clock

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultCocktailRepository(
    private val api: BarAssistantApi,
    private val sessionRepository: SessionRepository,
    private val cocktailDao: CocktailDao,
    private val inventoryDao: InventoryDao,
    private val catalogDao: CatalogDao,
) : CocktailRepository {

    private val syncMutex = Mutex()
    private val _syncStatus = MutableStateFlow(SyncStatus())
    override val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private val activeBarIdFlow: Flow<Int?> = sessionRepository.sessionState
        .map { state ->
            when (state) {
                is SessionState.Authenticated -> state.activeBarId
                is SessionState.Loading, is SessionState.Unauthenticated -> null
            }
        }
        .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeCocktails(filter: CocktailFilter): Flow<List<CocktailSummary>> =
        activeBarIdFlow.flatMapLatest { barId ->
            if (barId == null) {
                flowOf(emptyList())
            } else {
                cocktailDao.observeCocktailsWithIngredients(barId)
                    .map { items -> filterAndSortCocktails(items, filter) }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeCocktailDetail(cocktailId: Int): Flow<CocktailDetail?> =
        activeBarIdFlow.flatMapLatest { barId ->
            if (barId == null) {
                cocktailDao.observeCocktailWithIngredients(cocktailId).map { it?.toCocktailDetail() }
            } else {
                combine(
                    cocktailDao.observeCocktailWithIngredients(cocktailId),
                    catalogDao.observeGlasses(barId),
                    catalogDao.observeMethods(barId),
                ) { cocktail, glasses, methods ->
                    if (cocktail == null) {
                        null
                    } else {
                        val glass = cocktail.cocktail.glassId?.let { glassId ->
                            glasses.firstOrNull { it.id == glassId }?.toModel()
                        }
                        val method = cocktail.cocktail.methodId?.let { methodId ->
                            methods.firstOrNull { it.id == methodId }?.toModel()
                        }
                        cocktail.toCocktailDetail(glass = glass, method = method)
                    }
                }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeCatalog(): Flow<BarCatalog> =
        activeBarIdFlow.flatMapLatest { barId ->
            if (barId == null) {
                flowOf(BarCatalog())
            } else {
                val ingredientsFlow = combine(
                    catalogDao.observeIngredients(barId),
                    inventoryDao.observeBarShelfIngredientIds(barId),
                    inventoryDao.observeShoppingList(barId),
                ) { ingredients, shelfIds, shoppingItems ->
                    val shelfSet = shelfIds.toSet()
                    val shoppingSet = shoppingItems.mapTo(HashSet(shoppingItems.size)) { it.ingredientId }
                    ingredients.map { it.toSummary(shelfSet, shoppingSet) }
                }
                combine(
                    ingredientsFlow,
                    catalogDao.observeGlasses(barId),
                    catalogDao.observeMethods(barId),
                    catalogDao.observeTags(barId),
                    catalogDao.observeUtensils(barId),
                ) { ingredients, glasses, methods, tags, utensils ->
                    BarCatalog(
                        ingredients = ingredients,
                        glasses = glasses.map { it.toModel() },
                        methods = methods.map { it.toModel() },
                        tags = tags.map { it.toModel() },
                        utensils = utensils.map { it.toModel() },
                    )
                }
            }
        }

    override suspend fun syncAll(force: Boolean): Result<Unit> {
        if (!syncMutex.tryLock()) {
            return Result.success(Unit)
        }
        var previousTimestamp = _syncStatus.value.lastSyncedEpochMillis
        return try {
            val barId = currentActiveBarId()
                ?: return Result.failure(IllegalStateException("No active bar selected."))
            val serverUrl = currentServerUrl()
            previousTimestamp = previousTimestamp ?: catalogDao.getSyncTimestamp(syncKey(barId))
            _syncStatus.value = SyncStatus(
                isSyncing = true,
                lastSyncedEpochMillis = previousTimestamp,
                errorMessage = null,
            )

            val payload = coroutineScope {
                val cocktailsDeferred = async {
                    fetchAllPages { page -> api.getCocktails(page = page, perPage = 100) }
                }
                val ingredientsDeferred = async {
                    fetchAllPages { page -> api.getIngredients(page = page, perPage = 200) }
                }
                val glassesDeferred = async { api.getGlasses() }
                val methodsDeferred = async { api.getCocktailMethods() }
                val tagsDeferred = async { api.getTags() }
                val utensilsDeferred = async { api.getUtensils() }
                val shelfDeferred = async {
                    fetchAllPages { page -> api.getBarInventoryIngredientIds(barId = barId, page = page, perPage = 200) }
                }

                SyncPayload(
                    cocktails = cocktailsDeferred.await(),
                    ingredients = ingredientsDeferred.await(),
                    glasses = glassesDeferred.await(),
                    methods = methodsDeferred.await(),
                    tags = tagsDeferred.await(),
                    utensils = utensilsDeferred.await(),
                    shelfIngredients = shelfDeferred.await(),
                )
            }

            val mappedCocktails = payload.cocktails.map { it.toEntityAndIngredients(barId, serverUrl) }
            val cocktailEntities = mappedCocktails.map { it.first }
            val cocktailIngredientEntities = mappedCocktails.flatMap { it.second }
            val ingredientEntities = payload.ingredients.map { it.toEntity(barId, serverUrl) }
            val glassEntities = payload.glasses.map { it.toEntity(barId) }
            val methodEntities = payload.methods.map { it.toEntity(barId) }
            val tagEntities = payload.tags.map { it.toEntity(barId) }
            val utensilEntities = payload.utensils.map { it.toEntity(barId) }
            val shelfIds = payload.shelfIngredients.map { it.id }.distinct()

            cocktailDao.replaceAllCocktailsForBar(
                barId = barId,
                cocktails = cocktailEntities,
                ingredients = cocktailIngredientEntities,
            )

            catalogDao.clearIngredientsForBar(barId)
            if (ingredientEntities.isNotEmpty()) {
                catalogDao.upsertIngredients(ingredientEntities)
            }

            catalogDao.clearGlassesForBar(barId)
            if (glassEntities.isNotEmpty()) {
                catalogDao.upsertGlasses(glassEntities)
            }

            catalogDao.clearMethodsForBar(barId)
            if (methodEntities.isNotEmpty()) {
                catalogDao.upsertMethods(methodEntities)
            }

            catalogDao.clearTagsForBar(barId)
            if (tagEntities.isNotEmpty()) {
                catalogDao.upsertTags(tagEntities)
            }

            catalogDao.clearUtensilsForBar(barId)
            if (utensilEntities.isNotEmpty()) {
                catalogDao.upsertUtensils(utensilEntities)
            }

            inventoryDao.replaceBarShelf(barId, shelfIds)

            val now = Clock.System.now().toEpochMilliseconds()
            catalogDao.setSyncTimestamp(
                SyncMetadataEntity(
                    key = syncKey(barId),
                    lastSyncedEpochMillis = now,
                ),
            )
            _syncStatus.value = SyncStatus(
                isSyncing = false,
                lastSyncedEpochMillis = now,
                errorMessage = null,
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            _syncStatus.value = SyncStatus(
                isSyncing = false,
                lastSyncedEpochMillis = previousTimestamp,
                errorMessage = null,
            )
            throw e
        } catch (e: Throwable) {
            _syncStatus.value = SyncStatus(
                isSyncing = false,
                lastSyncedEpochMillis = previousTimestamp,
                errorMessage = e.message?.takeIf { it.isNotBlank() } ?: "Sync failed.",
            )
            Result.failure(e)
        } finally {
            syncMutex.unlock()
        }
    }

    override suspend fun refreshCocktail(cocktailId: Int): Result<CocktailDetail> = runApiCall {
        val barId = requireActiveBarId()
        val serverUrl = currentServerUrl()
        val dto = api.getCocktail(cocktailId.toString())
        persistCocktail(dto, barId, serverUrl)
    }

    override suspend fun toggleFavorite(cocktailId: Int): Result<Boolean> {
        val existing = cocktailDao.observeCocktailWithIngredients(cocktailId).first()
            ?: return runApiCall { api.toggleFavorite(cocktailId) }

        val previous = existing.cocktail.isFavorite
        val optimistic = !previous
        cocktailDao.updateFavorite(cocktailId, optimistic)

        return try {
            val actual = api.toggleFavorite(cocktailId)
            if (actual != optimistic) {
                cocktailDao.updateFavorite(cocktailId, actual)
            }
            Result.success(actual)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                cocktailDao.updateFavorite(cocktailId, previous)
            }
            throw e
        } catch (e: Throwable) {
            cocktailDao.updateFavorite(cocktailId, previous)
            Result.failure(e)
        }
    }

    override suspend fun saveCocktail(
        cocktailId: Int?,
        request: CocktailUpsertRequest,
    ): Result<CocktailDetail> = runApiCall {
        val barId = requireActiveBarId()
        val serverUrl = currentServerUrl()
        val writeDto = request.toWriteDto()
        val dto = if (cocktailId == null) {
            api.createCocktail(writeDto)
        } else {
            api.updateCocktail(cocktailId, writeDto)
        }
        if (dto.tags.isNotEmpty()) {
            catalogDao.upsertTags(dto.tags.map { it.toEntity(barId) })
        }
        persistCocktail(dto, barId, serverUrl)
    }

    override suspend fun deleteCocktail(cocktailId: Int): Result<Unit> = runApiCall {
        api.deleteCocktail(cocktailId)
        cocktailDao.deleteCocktailById(cocktailId)
    }

    override suspend fun uploadImage(
        bytes: ByteArray,
        fileName: String,
        mimeType: String?,
        sort: Int,
    ): Result<UploadedImage> = runApiCall {
        val serverUrl = currentServerUrl()
        api.uploadImage(
            bytes = bytes,
            fileName = fileName,
            mimeType = mimeType,
            sort = sort,
        ).toUploadedImage(serverUrl)
    }

    override suspend fun uploadImageFromUrl(
        imageUrl: String,
        copyright: String?,
        sort: Int,
    ): Result<UploadedImage> = runApiCall {
        val serverUrl = currentServerUrl()
        api.uploadImageFromUrl(
            imageUrl = imageUrl,
            copyright = copyright,
            sort = sort,
        ).toUploadedImage(serverUrl)
    }

    override suspend fun scrapeRecipeFromUrl(url: String): Result<ScrapedCocktail> = runApiCall {
        api.scrapeCocktail(url).toModel()
    }

    private suspend fun persistCocktail(
        dto: CocktailDto,
        barId: Int,
        serverUrl: String?,
    ): CocktailDetail {
        val (entity, ingredients) = dto.toEntityAndIngredients(barId, serverUrl)
        cocktailDao.replaceCocktailWithIngredients(entity, ingredients)
        val glass = dto.glass?.let {
            Glass(
                id = it.id,
                name = it.name,
                volume = it.volume,
                volumeUnits = it.volumeUnits,
            )
        }
        val method = dto.method?.let {
            CocktailMethod(
                id = it.id,
                name = it.name,
                dilutionPercentage = it.dilutionPercentage,
            )
        }
        return CocktailWithIngredients(entity, ingredients).toCocktailDetail(glass = glass, method = method)
    }

    private suspend fun <T> fetchAllPages(
        fetchPage: suspend (page: Int) -> PaginatedEnvelope<T>,
    ): List<T> {
        val items = mutableListOf<T>()
        var page = 1
        while (true) {
            val envelope = fetchPage(page)
            items += envelope.data
            val lastPage = envelope.meta?.lastPage ?: page
            if (page >= lastPage || envelope.data.isEmpty()) {
                break
            }
            page += 1
        }
        return items
    }

    private suspend fun currentActiveBarId(): Int? =
        (sessionRepository.sessionState.value as? SessionState.Authenticated)?.activeBarId
            ?: sessionRepository.currentBarId()

    private suspend fun requireActiveBarId(): Int =
        currentActiveBarId() ?: throw IllegalStateException("No active bar selected.")

    private suspend fun currentServerUrl(): String? =
        (sessionRepository.sessionState.value as? SessionState.Authenticated)?.serverUrl
            ?: sessionRepository.currentServerUrl()

    private suspend inline fun <T> runApiCall(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private fun syncKey(barId: Int): String = "bar_${barId}_sync"

    private class SyncPayload(
        val cocktails: List<CocktailDto>,
        val ingredients: List<IngredientDto>,
        val glasses: List<GlassDto>,
        val methods: List<CocktailMethodDto>,
        val tags: List<TagDto>,
        val utensils: List<UtensilDto>,
        val shelfIngredients: List<IngredientBasicDto>,
    )
}
