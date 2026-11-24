package com.ryanvidal.barfriend.core.data

import com.ryanvidal.barfriend.core.database.dao.CatalogDao
import com.ryanvidal.barfriend.core.database.dao.CocktailDao
import com.ryanvidal.barfriend.core.database.dao.InventoryDao
import com.ryanvidal.barfriend.core.database.dao.SyncDao
import com.ryanvidal.barfriend.core.database.entity.ShoppingListEntity
import com.ryanvidal.barfriend.core.model.IngredientSummary
import com.ryanvidal.barfriend.core.model.RecommendedIngredient
import com.ryanvidal.barfriend.core.model.ShoppingListItem
import com.ryanvidal.barfriend.core.model.UnlockedCocktail
import com.ryanvidal.barfriend.core.network.BarAssistantApi
import com.ryanvidal.barfriend.core.network.dto.ShoppingListIngredientInputDto
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultInventoryRepository(
    private val api: BarAssistantApi,
    private val sessionRepository: SessionRepository,
    private val cocktailRepository: CocktailRepository,
    private val cocktailDao: CocktailDao,
    private val inventoryDao: InventoryDao,
    private val catalogDao: CatalogDao,
    private val syncDao: SyncDao,
    private val mutationCoordinator: BarMutationCoordinator,
) : InventoryRepository {

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    internal var lastBackgroundSyncJob: Job? = null

    private val activeBarIdFlow: Flow<Int?> = sessionRepository.sessionState
        .map { state ->
            when (state) {
                is SessionState.Authenticated -> state.activeBarId
                is SessionState.Loading, is SessionState.Unauthenticated -> null
            }
        }
        .distinctUntilChanged()

    init {
        backgroundScope.launch {
            sessionRepository.sessionState
                .map { state ->
                    (state as? SessionState.Authenticated)?.let { it.serverUrl to it.activeBarId }
                }
                .distinctUntilChanged()
                .collect {
                    lastBackgroundSyncJob?.cancel()
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeBarShelfIngredients(): Flow<List<IngredientSummary>> =
        activeBarIdFlow.flatMapLatest { barId ->
            if (barId == null) {
                flowOf(emptyList())
            } else {
                combine(
                    catalogDao.observeIngredients(barId),
                    inventoryDao.observeBarShelfIngredientIds(barId),
                    inventoryDao.observeShoppingList(barId),
                ) { ingredients, shelfIds, shoppingItems ->
                    val shelfSet = shelfIds.toSet()
                    val shoppingSet = shoppingItems.mapTo(HashSet(shoppingItems.size)) { it.ingredientId }
                    ingredients.map { it.toSummary(shelfSet, shoppingSet) }
                }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeShoppingList(): Flow<List<ShoppingListItem>> =
        activeBarIdFlow.flatMapLatest { barId ->
            if (barId == null) {
                flowOf(emptyList())
            } else {
                combine(
                    inventoryDao.observeShoppingList(barId),
                    catalogDao.observeIngredients(barId),
                ) { shoppingItems, ingredients ->
                    val ingredientsById = ingredients.associateBy { it.id }
                    shoppingItems
                        .map { it.toModel(ingredientsById[it.ingredientId]) }
                        .sortedWith(
                            compareBy<ShoppingListItem> { it.ingredientName.lowercase() }
                                .thenBy { it.ingredientName }
                                .thenBy { it.ingredientId },
                        )
                }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeShoppingListIds(): Flow<Set<Int>> =
        activeBarIdFlow.flatMapLatest { barId ->
            if (barId == null) {
                flowOf(emptySet())
            } else {
                inventoryDao.observeShoppingList(barId)
                    .map { items -> items.mapTo(HashSet(items.size)) { it.ingredientId } }
            }
        }

    override suspend fun syncShoppingList(): Result<Unit> {
        val originEpoch = sessionRepository.currentSessionEpoch()
        val originBarId = currentActiveBarId()
            ?: return Result.failure(IllegalStateException("No active bar selected."))
        return runSuspendCatching {
            mutationCoordinator.withMutationLock {
                ensureSessionEpochValid(originEpoch, originBarId)
                val remoteItems = withResolvedUserIdAnd403Retry(api, sessionRepository) { resolvedUserId ->
                    api.getShoppingList(barId = originBarId, userId = resolvedUserId)
                }
                ensureSessionEpochValid(originEpoch, originBarId)
                val entities = remoteItems
                    .distinctBy { it.ingredient.id }
                    .map { it.toEntity(originBarId) }
                withContext(NonCancellable) {
                    ensureSessionEpochValid(originEpoch, originBarId)
                    mutationCoordinator.bumpGenerationLocked()
                    inventoryDao.replaceShoppingList(originBarId, entities)
                }
            }
        }
    }

    override suspend fun setInBarShelf(ingredientIds: List<Int>, inStock: Boolean): Result<Unit> {
        val ids = ingredientIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return Result.success(Unit)
        val originEpoch = sessionRepository.currentSessionEpoch()
        val originBarId = currentActiveBarId()
            ?: return Result.failure(IllegalStateException("No active bar selected."))
        return runSuspendCatching {
            mutationCoordinator.withMutationLock {
                ensureSessionEpochValid(originEpoch, originBarId)
                mutateBarShelfLocked(
                    barId = originBarId,
                    originEpoch = originEpoch,
                    ids = ids,
                    inStock = inStock,
                )
                triggerBackgroundSync(originEpoch)
            }
        }
    }

    override suspend fun addToShoppingList(ingredientIds: List<Int>, quantity: Int): Result<Unit> =
        upsertShoppingList(
            ingredientIds = ingredientIds,
            quantity = quantity,
            preserveExistingQuantity = quantity <= 1,
        )

    override suspend fun updateShoppingListQuantity(ingredientId: Int, quantity: Int): Result<Unit> {
        if (ingredientId <= 0) return Result.success(Unit)
        if (quantity <= 0) return removeFromShoppingList(listOf(ingredientId))
        return upsertShoppingList(
            ingredientIds = listOf(ingredientId),
            quantity = quantity,
            preserveExistingQuantity = false,
        )
    }

    override suspend fun removeFromShoppingList(ingredientIds: List<Int>): Result<Unit> {
        val ids = ingredientIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return Result.success(Unit)
        val originEpoch = sessionRepository.currentSessionEpoch()
        val originBarId = currentActiveBarId()
            ?: return Result.failure(IllegalStateException("No active bar selected."))
        return runSuspendCatching {
            mutationCoordinator.withMutationLock {
                ensureSessionEpochValid(originEpoch, originBarId)
                removeFromShoppingListLocked(
                    barId = originBarId,
                    originEpoch = originEpoch,
                    ids = ids,
                )
            }
        }
    }

    override suspend fun markPurchasedToBarShelf(ingredientIds: List<Int>): Result<Unit> {
        val ids = ingredientIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return Result.success(Unit)
        val originEpoch = sessionRepository.currentSessionEpoch()
        val originBarId = currentActiveBarId()
            ?: return Result.failure(IllegalStateException("No active bar selected."))
        return runSuspendCatching {
            mutationCoordinator.withMutationLock {
                ensureSessionEpochValid(originEpoch, originBarId)
                var shelfMutated = false
                try {
                    mutateBarShelfLocked(
                        barId = originBarId,
                        originEpoch = originEpoch,
                        ids = ids,
                        inStock = true,
                    )
                    shelfMutated = true
                    ensureSessionEpochValid(originEpoch, originBarId)
                    removeFromShoppingListLocked(
                        barId = originBarId,
                        originEpoch = originEpoch,
                        ids = ids,
                    )
                } finally {
                    if (shelfMutated && isSessionEpochValid(originEpoch, originBarId)) {
                        triggerBackgroundSync(originEpoch)
                    }
                }
            }
        }
    }

    override suspend fun getRecommendedIngredients(): Result<List<RecommendedIngredient>> = runSuspendCatching {
        val barId = mutationCoordinator.withMutationLock { requireActiveBarId() }
        val recommended = api.getRecommendedBarIngredients(barId)
            .distinctBy { it.id }
        val ingredientsById = catalogDao.getIngredientsForBar(barId).associateBy { it.id }
        val shoppingListIds = inventoryDao.getShoppingListSnapshot(barId)
            .mapTo(HashSet()) { it.ingredientId }
        recommended.map { dto ->
            dto.toModel(
                ingredient = ingredientsById[dto.id],
                inShoppingList = dto.id in shoppingListIds,
            )
        }
    }

    override suspend fun getUnlockedCocktails(ingredientId: Int): Result<List<UnlockedCocktail>> {
        if (ingredientId <= 0) return Result.success(emptyList())
        return runSuspendCatching {
            val barId = mutationCoordinator.withMutationLock { requireActiveBarId() }
            api.getExtraCocktailsForIngredient(barId, ingredientId)
                .distinctBy { it.id }
                .map { it.toUnlockedCocktail() }
        }
    }

    private suspend fun upsertShoppingList(
        ingredientIds: List<Int>,
        quantity: Int,
        preserveExistingQuantity: Boolean,
    ): Result<Unit> {
        val ids = ingredientIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return Result.success(Unit)
        val originEpoch = sessionRepository.currentSessionEpoch()
        val originBarId = currentActiveBarId()
            ?: return Result.failure(IllegalStateException("No active bar selected."))
        return runSuspendCatching {
            mutationCoordinator.withMutationLock {
                ensureSessionEpochValid(originEpoch, originBarId)
                val (token, initialUserId) = resolveUserIdAndToken(api, sessionRepository)
                ensureSessionEpochValid(originEpoch, originBarId)
                val previousItems = inventoryDao.getShoppingListSnapshot(originBarId)
                val previousById = previousItems.associateBy { it.ingredientId }
                val resolvedQty = quantity.coerceAtLeast(1)
                val targetEntities = ids.map { id ->
                    val existingQty = previousById[id]?.quantity
                    val itemQty = if (preserveExistingQuantity && existingQty != null && existingQty > 0) {
                        existingQty
                    } else {
                        resolvedQty
                    }
                    ShoppingListEntity(
                        barId = originBarId,
                        ingredientId = id,
                        quantity = itemQty,
                    )
                }

                var appliedLocally = false
                try {
                    coroutineContext.ensureActive()
                    withContext(NonCancellable) {
                        ensureSessionEpochValid(originEpoch, originBarId)
                        appliedLocally = true
                        mutationCoordinator.bumpGenerationLocked()
                        inventoryDao.upsertShoppingListItems(targetEntities)
                    }
                    executeWith403UserIdRetry(api, sessionRepository, initialUserId, token) { resolvedId ->
                        api.addToShoppingList(
                            barId = originBarId,
                            userId = resolvedId,
                            items = targetEntities.map { entity ->
                                ShoppingListIngredientInputDto(
                                    id = entity.ingredientId,
                                    quantity = entity.quantity,
                                )
                            },
                        )
                    }
                } catch (e: CancellationException) {
                    if (appliedLocally) {
                        withContext(NonCancellable) {
                            if (isSessionEpochValid(originEpoch, originBarId)) {
                                mutationCoordinator.bumpGenerationLocked()
                                inventoryDao.replaceShoppingList(originBarId, previousItems)
                            }
                        }
                    }
                    throw e
                } catch (e: Throwable) {
                    if (appliedLocally) {
                        withContext(NonCancellable) {
                            if (isSessionEpochValid(originEpoch, originBarId)) {
                                mutationCoordinator.bumpGenerationLocked()
                                inventoryDao.replaceShoppingList(originBarId, previousItems)
                            }
                        }
                    }
                    throw e
                }
            }
        }
    }

    private suspend fun mutateBarShelfLocked(
        barId: Int,
        originEpoch: Long,
        ids: List<Int>,
        inStock: Boolean,
    ) {
        val previousShelf = inventoryDao.getBarShelfIngredientIds(barId).toSet()
        val nextShelf = if (inStock) previousShelf + ids else previousShelf - ids.toSet()
        val catalogIngredients = catalogDao.getIngredientsForBar(barId)
        val cocktails = cocktailDao.getCocktailsWithIngredientsForBar(barId)
        val toggledSet = ids.toSet()
        val recalc = recalculateCocktailAvailability(
            items = cocktails,
            shelfIngredientIds = nextShelf,
            catalogIngredients = catalogIngredients,
            toggledIngredientIds = toggledSet,
        )

        var appliedLocally = false
        try {
            coroutineContext.ensureActive()
            withContext(NonCancellable) {
                ensureSessionEpochValid(originEpoch, barId)
                appliedLocally = true
                mutationCoordinator.bumpGenerationLocked()
                syncDao.applyBarShelfAndAvailability(
                    barId = barId,
                    addShelfIds = if (inStock) ids else emptyList(),
                    removeShelfIds = if (inStock) emptyList() else ids,
                    cocktailUpdates = recalc.cocktailUpdates,
                    ingredientUpdates = recalc.ingredientUpdates,
                )
            }
            if (inStock) {
                api.addBarInventoryIngredients(barId, ids)
            } else {
                api.removeBarInventoryIngredients(barId, ids)
            }
        } catch (e: CancellationException) {
            if (appliedLocally) {
                withContext(NonCancellable) {
                    if (isSessionEpochValid(originEpoch, barId)) {
                        restoreShelfStateLocked(barId, previousShelf, toggledSet)
                    }
                }
            }
            throw e
        } catch (e: Throwable) {
            if (appliedLocally) {
                withContext(NonCancellable) {
                    if (isSessionEpochValid(originEpoch, barId)) {
                        restoreShelfStateLocked(barId, previousShelf, toggledSet)
                    }
                }
            }
            throw e
        }
    }

    private suspend fun removeFromShoppingListLocked(
        barId: Int,
        originEpoch: Long,
        ids: List<Int>,
    ) {
        val (token, initialUserId) = resolveUserIdAndToken(api, sessionRepository)
        ensureSessionEpochValid(originEpoch, barId)
        val previousItems = inventoryDao.getShoppingListSnapshot(barId)

        var appliedLocally = false
        try {
            coroutineContext.ensureActive()
            withContext(NonCancellable) {
                ensureSessionEpochValid(originEpoch, barId)
                appliedLocally = true
                mutationCoordinator.bumpGenerationLocked()
                inventoryDao.deleteShoppingListItems(barId, ids)
            }
            executeWith403UserIdRetry(api, sessionRepository, initialUserId, token) { resolvedId ->
                api.removeFromShoppingList(barId = barId, userId = resolvedId, ingredientIds = ids)
            }
        } catch (e: CancellationException) {
            if (appliedLocally) {
                withContext(NonCancellable) {
                    if (isSessionEpochValid(originEpoch, barId)) {
                        mutationCoordinator.bumpGenerationLocked()
                        inventoryDao.replaceShoppingList(barId, previousItems)
                    }
                }
            }
            throw e
        } catch (e: Throwable) {
            if (appliedLocally) {
                withContext(NonCancellable) {
                    if (isSessionEpochValid(originEpoch, barId)) {
                        mutationCoordinator.bumpGenerationLocked()
                        inventoryDao.replaceShoppingList(barId, previousItems)
                    }
                }
            }
            throw e
        }
    }

    private suspend fun restoreShelfStateLocked(
        barId: Int,
        previousShelf: Set<Int>,
        toggledIngredientIds: Set<Int>,
    ) {
        val catalogIngredients = catalogDao.getIngredientsForBar(barId)
        val cocktails = cocktailDao.getCocktailsWithIngredientsForBar(barId)
        val recalc = recalculateCocktailAvailability(
            items = cocktails,
            shelfIngredientIds = previousShelf,
            catalogIngredients = catalogIngredients,
            toggledIngredientIds = toggledIngredientIds,
        )
        mutationCoordinator.bumpGenerationLocked()
        syncDao.applyBarShelfAndAvailability(
            barId = barId,
            replaceAllShelfIds = previousShelf.toList(),
            cocktailUpdates = recalc.cocktailUpdates,
            ingredientUpdates = recalc.ingredientUpdates,
        )
    }

    private fun triggerBackgroundSync(originEpoch: Long) {
        lastBackgroundSyncJob?.cancel()
        lastBackgroundSyncJob = backgroundScope.launch {
            if (sessionRepository.currentSessionEpoch() != originEpoch) return@launch
            runSuspendCatching { cocktailRepository.syncAllSilent(force = true) }
        }
    }

    private suspend fun isSessionEpochValid(expectedEpoch: Long, expectedBarId: Int): Boolean =
        sessionRepository.currentSessionEpoch() == expectedEpoch && currentActiveBarId() == expectedBarId

    private suspend fun ensureSessionEpochValid(expectedEpoch: Long, expectedBarId: Int) {
        if (!isSessionEpochValid(expectedEpoch, expectedBarId)) {
            throw IllegalStateException("Active session changed during inventory update.")
        }
    }

    private suspend fun currentActiveBarId(): Int? =
        (sessionRepository.sessionState.value as? SessionState.Authenticated)?.activeBarId
            ?: sessionRepository.currentBarId()

    private suspend fun requireActiveBarId(): Int =
        currentActiveBarId() ?: throw IllegalStateException("No active bar selected.")
}
