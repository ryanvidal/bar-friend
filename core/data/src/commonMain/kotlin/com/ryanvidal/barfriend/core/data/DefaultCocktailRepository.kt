package com.ryanvidal.barfriend.core.data

import com.ryanvidal.barfriend.core.database.dao.CatalogDao
import com.ryanvidal.barfriend.core.database.dao.CocktailDao
import com.ryanvidal.barfriend.core.database.dao.CocktailWithIngredients
import com.ryanvidal.barfriend.core.database.dao.InventoryDao
import com.ryanvidal.barfriend.core.database.dao.SyncDao
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
import com.ryanvidal.barfriend.core.network.dto.ShoppingListItemDto
import com.ryanvidal.barfriend.core.network.dto.TagDto
import com.ryanvidal.barfriend.core.network.dto.UtensilDto
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
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
    private val syncDao: SyncDao,
) : CocktailRepository {

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncMutex = Mutex()
    private val mutationMutex = Mutex()
    private val favoriteMutexMapLock = Mutex()
    private val favoriteMutexes = mutableMapOf<Int, Mutex>()
    private val _syncStatus = MutableStateFlow(SyncStatus())
    override val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    private var activeAutoSyncJob: Job? = null

    @Volatile
    private var lastSyncedSession: Pair<String, Int>? = null
    private var mutationGeneration: Long = 0L

    init {
        repositoryScope.launch {
            sessionRepository.sessionState.collect { state ->
                when (state) {
                    is SessionState.Loading -> Unit
                    is SessionState.Unauthenticated -> {
                        activeAutoSyncJob?.cancel()
                        activeAutoSyncJob = null
                        lastSyncedSession = null
                        _syncStatus.value = SyncStatus()
                    }
                    is SessionState.Authenticated -> {
                        val barId = state.activeBarId ?: return@collect
                        val sessionKey = state.serverUrl to barId
                        if (lastSyncedSession != sessionKey) {
                            activeAutoSyncJob?.cancel()
                            activeAutoSyncJob = repositoryScope.launch {
                                syncAll(force = false)
                            }
                        }
                    }
                }
            }
        }
    }

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
                flowOf(null)
            } else {
                combine(
                    cocktailDao.observeCocktailWithIngredients(barId, cocktailId),
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

    override suspend fun syncAll(force: Boolean): Result<Unit> = syncMutex.withLock {
        val barId = currentActiveBarId()
        val serverUrl = currentServerUrl()
        if (barId == null || serverUrl.isNullOrBlank()) {
            _syncStatus.value = SyncStatus(
                isSyncing = false,
                lastSyncedEpochMillis = null,
                errorMessage = "No active bar selected.",
            )
            return@withLock Result.failure(IllegalStateException("No active bar selected."))
        }

        val sessionKey = serverUrl to barId
        if (!force && lastSyncedSession == sessionKey) {
            return@withLock Result.success(Unit)
        }

        val previousTimestamp = _syncStatus.value.lastSyncedEpochMillis
            ?: catalogDao.getSyncTimestamp(syncKey(barId))
        try {
            _syncStatus.value = SyncStatus(
                isSyncing = true,
                lastSyncedEpochMillis = previousTimestamp,
                errorMessage = null,
            )

            for (attempt in 1..MAX_SYNC_ATTEMPTS) {
                val startGen = mutationMutex.withLock { mutationGeneration }
                val payload = fetchSyncPayload(barId)

                if (!isSessionStillActive(serverUrl, barId)) {
                    if (sessionRepository.sessionState.value is SessionState.Unauthenticated) {
                        _syncStatus.value = SyncStatus()
                    }
                    return@withLock Result.success(Unit)
                }

                val mappedCocktails = payload.cocktails.distinctBy { it.id }
                    .map { it.toEntityAndIngredients(barId, serverUrl) }
                val cocktailEntities = mappedCocktails.map { it.first }
                val cocktailIngredientEntities = mappedCocktails.flatMap { it.second }
                val ingredientEntities = payload.ingredients.distinctBy { it.id }
                    .map { it.toEntity(barId, serverUrl) }
                val glassEntities = payload.glasses.distinctBy { it.id }
                    .map { it.toEntity(barId) }
                val methodEntities = payload.methods.distinctBy { it.id }
                    .map { it.toEntity(barId) }
                val tagEntities = payload.tags.distinctBy { it.id }
                    .map { it.toEntity(barId) }
                val utensilEntities = payload.utensils.distinctBy { it.id }
                    .map { it.toEntity(barId) }
                val shelfIds = payload.shelfIngredients.map { it.id }.distinct()
                val shoppingListEntities = payload.shoppingListItems
                    ?.distinctBy { it.ingredient.id }
                    ?.map { it.toEntity(barId) }

                val wroteSnapshot = mutationMutex.withLock {
                    if (!isSessionStillActive(serverUrl, barId) || mutationGeneration != startGen) {
                        false
                    } else {
                        val now = Clock.System.now().toEpochMilliseconds()
                        syncDao.replaceBarSnapshot(
                            barId = barId,
                            cocktails = cocktailEntities,
                            cocktailIngredients = cocktailIngredientEntities,
                            ingredients = ingredientEntities,
                            glasses = glassEntities,
                            methods = methodEntities,
                            tags = tagEntities,
                            utensils = utensilEntities,
                            shelfIngredientIds = shelfIds,
                            syncKey = syncKey(barId),
                            syncedAtEpochMillis = now,
                            shoppingListItems = shoppingListEntities,
                        )
                        lastSyncedSession = sessionKey
                        _syncStatus.value = SyncStatus(
                            isSyncing = false,
                            lastSyncedEpochMillis = now,
                            errorMessage = null,
                        )
                        true
                    }
                }

                if (wroteSnapshot) {
                    return@withLock Result.success(Unit)
                }
                if (!isSessionStillActive(serverUrl, barId)) {
                    if (sessionRepository.sessionState.value is SessionState.Unauthenticated) {
                        _syncStatus.value = SyncStatus()
                    }
                    return@withLock Result.success(Unit)
                }
            }

            _syncStatus.value = SyncStatus(
                isSyncing = false,
                lastSyncedEpochMillis = previousTimestamp,
                errorMessage = null,
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            if (isSessionStillActive(serverUrl, barId)) {
                _syncStatus.value = SyncStatus(
                    isSyncing = false,
                    lastSyncedEpochMillis = previousTimestamp,
                    errorMessage = null,
                )
            } else if (sessionRepository.sessionState.value is SessionState.Unauthenticated) {
                _syncStatus.value = SyncStatus()
            }
            throw e
        } catch (e: Throwable) {
            if (isSessionStillActive(serverUrl, barId)) {
                _syncStatus.value = SyncStatus(
                    isSyncing = false,
                    lastSyncedEpochMillis = previousTimestamp,
                    errorMessage = e.message?.takeIf { it.isNotBlank() } ?: "Sync failed.",
                )
            }
            Result.failure(e)
        }
    }

    override suspend fun refreshCocktail(cocktailId: Int): Result<CocktailDetail> = runApiCall {
        val barId = requireActiveBarId()
        val serverUrl = currentServerUrl()
        val dto = api.getCocktail(cocktailId.toString())
        mutationMutex.withLock {
            mutationGeneration += 1
            if (currentActiveBarId() != barId) {
                throw IllegalStateException("Active bar changed during cocktail refresh.")
            }
            persistCocktail(dto, barId, serverUrl)
        }
    }

    override suspend fun toggleFavorite(cocktailId: Int): Result<Boolean> =
        favoriteMutexFor(cocktailId).withLock {
            val barId = currentActiveBarId()
                ?: return@withLock Result.failure(IllegalStateException("No active bar selected."))
            val existing = cocktailDao.observeCocktailWithIngredients(barId, cocktailId).first()
                ?: return@withLock runApiCall {
                    val actual = api.toggleFavorite(cocktailId)
                    mutationMutex.withLock { mutationGeneration += 1 }
                    actual
                }

            val previous = existing.cocktail.isFavorite
            val optimistic = !previous
            mutationMutex.withLock {
                mutationGeneration += 1
                cocktailDao.updateFavorite(barId, cocktailId, optimistic)
            }

            try {
                val actual = api.toggleFavorite(cocktailId)
                mutationMutex.withLock {
                    mutationGeneration += 1
                    if (currentActiveBarId() == barId && actual != optimistic) {
                        cocktailDao.updateFavorite(barId, cocktailId, actual)
                    }
                }
                Result.success(actual)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    mutationMutex.withLock {
                        mutationGeneration += 1
                        if (currentActiveBarId() == barId) {
                            cocktailDao.updateFavorite(barId, cocktailId, previous)
                        }
                    }
                }
                throw e
            } catch (e: Throwable) {
                mutationMutex.withLock {
                    mutationGeneration += 1
                    if (currentActiveBarId() == barId) {
                        cocktailDao.updateFavorite(barId, cocktailId, previous)
                    }
                }
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
        mutationMutex.withLock {
            mutationGeneration += 1
            if (currentActiveBarId() != barId) {
                throw IllegalStateException("Active bar changed while saving cocktail.")
            }
            if (dto.tags.isNotEmpty()) {
                catalogDao.upsertTags(dto.tags.map { it.toEntity(barId) }.distinctBy { it.id })
            }
            persistCocktail(dto, barId, serverUrl)
        }
    }

    override suspend fun deleteCocktail(cocktailId: Int): Result<Unit> = runApiCall {
        val barId = requireActiveBarId()
        api.deleteCocktail(cocktailId)
        mutationMutex.withLock {
            mutationGeneration += 1
            if (currentActiveBarId() == barId) {
                cocktailDao.deleteCocktailById(barId, cocktailId)
            }
        }
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

    private suspend fun fetchSyncPayload(barId: Int): SyncPayload = coroutineScope {
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
            runCatching {
                val userId = sessionRepository.currentUserId()
                    ?: api.getProfile().id.also { sessionRepository.setActiveUserId(it) }
                api.getShoppingList(userId)
            }.onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        }

        SyncPayload(
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

    private suspend fun favoriteMutexFor(cocktailId: Int): Mutex =
        favoriteMutexMapLock.withLock {
            favoriteMutexes.getOrPut(cocktailId) { Mutex() }
        }

    private fun isSessionStillActive(serverUrl: String, barId: Int): Boolean {
        val auth = sessionRepository.sessionState.value as? SessionState.Authenticated ?: return false
        return auth.serverUrl == serverUrl && auth.activeBarId == barId
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
        val shoppingListItems: List<ShoppingListItemDto>?,
    )

    private companion object {
        const val MAX_SYNC_ATTEMPTS = 2
    }
}

internal suspend fun <T> fetchAllPages(
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
