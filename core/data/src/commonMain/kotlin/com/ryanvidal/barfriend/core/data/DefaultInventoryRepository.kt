package com.ryanvidal.barfriend.core.data

import com.ryanvidal.barfriend.core.database.dao.CatalogDao
import com.ryanvidal.barfriend.core.database.dao.CocktailDao
import com.ryanvidal.barfriend.core.database.dao.InventoryDao
import com.ryanvidal.barfriend.core.database.entity.BarShelfEntity
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

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
) : InventoryRepository {

    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutationMutex = Mutex()

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

    override suspend fun syncShoppingList(): Result<Unit> = runApiCall {
        mutationMutex.withLock {
            val barId = requireActiveBarId()
            val userId = ensureUserId()
            val remoteItems = api.getShoppingList(userId)
            if (currentActiveBarId() != barId) {
                throw IllegalStateException("Active bar changed while syncing shopping list.")
            }
            val entities = remoteItems
                .distinctBy { it.ingredient.id }
                .map { it.toEntity(barId) }
            inventoryDao.replaceShoppingList(barId, entities)
        }
    }

    override suspend fun setInBarShelf(ingredientIds: List<Int>, inStock: Boolean): Result<Unit> {
        val ids = ingredientIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return Result.success(Unit)
        return runApiCall {
            mutationMutex.withLock {
                val barId = requireActiveBarId()
                mutateBarShelfLocked(barId = barId, ids = ids, inStock = inStock)
                triggerBackgroundSync()
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
        return runApiCall {
            mutationMutex.withLock {
                val barId = requireActiveBarId()
                val userId = ensureUserId()
                removeFromShoppingListLocked(barId = barId, userId = userId, ids = ids)
            }
        }
    }

    override suspend fun markPurchasedToBarShelf(ingredientIds: List<Int>): Result<Unit> {
        val ids = ingredientIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return Result.success(Unit)
        return runApiCall {
            mutationMutex.withLock {
                val barId = requireActiveBarId()
                val userId = ensureUserId()
                mutateBarShelfLocked(barId = barId, ids = ids, inStock = true)
                removeFromShoppingListLocked(barId = barId, userId = userId, ids = ids)
                triggerBackgroundSync()
            }
        }
    }

    override suspend fun getRecommendedIngredients(): Result<List<RecommendedIngredient>> = runApiCall {
        val barId = mutationMutex.withLock { requireActiveBarId() }
        val recommended = api.getRecommendedBarIngredients(barId)
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
        return runApiCall {
            val barId = mutationMutex.withLock { requireActiveBarId() }
            api.getExtraCocktailsForIngredient(barId, ingredientId)
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
        return runApiCall {
            mutationMutex.withLock {
                val barId = requireActiveBarId()
                val userId = ensureUserId()
                val previousItems = inventoryDao.getShoppingListSnapshot(barId)
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
                        barId = barId,
                        ingredientId = id,
                        quantity = itemQty,
                    )
                }

                inventoryDao.upsertShoppingListItems(targetEntities)
                try {
                    api.addToShoppingList(
                        userId = userId,
                        items = targetEntities.map { entity ->
                            ShoppingListIngredientInputDto(
                                id = entity.ingredientId,
                                quantity = entity.quantity,
                            )
                        },
                    )
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        if (currentActiveBarId() == barId) {
                            inventoryDao.replaceShoppingList(barId, previousItems)
                        }
                    }
                    throw e
                } catch (e: Throwable) {
                    withContext(NonCancellable) {
                        if (currentActiveBarId() == barId) {
                            inventoryDao.replaceShoppingList(barId, previousItems)
                        }
                    }
                    throw e
                }
            }
        }
    }

    private suspend fun mutateBarShelfLocked(
        barId: Int,
        ids: List<Int>,
        inStock: Boolean,
    ) {
        val previousShelf = inventoryDao.getBarShelfIngredientIds(barId).toSet()
        val nextShelf = if (inStock) previousShelf + ids else previousShelf - ids.toSet()

        if (inStock) {
            inventoryDao.upsertBarShelfItems(ids.map { BarShelfEntity(barId = barId, ingredientId = it) })
        } else {
            ids.forEach { inventoryDao.deleteBarShelfItem(barId, it) }
        }
        recalculateAndPersistCocktails(barId, nextShelf)

        try {
            if (inStock) {
                api.addBarInventoryIngredients(barId, ids)
            } else {
                api.removeBarInventoryIngredients(barId, ids)
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (currentActiveBarId() == barId) {
                    restoreShelfState(barId, previousShelf)
                }
            }
            throw e
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                if (currentActiveBarId() == barId) {
                    restoreShelfState(barId, previousShelf)
                }
            }
            throw e
        }
    }

    private suspend fun removeFromShoppingListLocked(
        barId: Int,
        userId: Int,
        ids: List<Int>,
    ) {
        val previousItems = inventoryDao.getShoppingListSnapshot(barId)
        ids.forEach { inventoryDao.deleteShoppingListItem(barId, it) }

        try {
            api.removeFromShoppingList(userId, ids)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                if (currentActiveBarId() == barId) {
                    inventoryDao.replaceShoppingList(barId, previousItems)
                }
            }
            throw e
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                if (currentActiveBarId() == barId) {
                    inventoryDao.replaceShoppingList(barId, previousItems)
                }
            }
            throw e
        }
    }

    private suspend fun restoreShelfState(barId: Int, previousShelf: Set<Int>) {
        inventoryDao.replaceBarShelf(barId, previousShelf.toList())
        recalculateAndPersistCocktails(barId, previousShelf)
    }

    private suspend fun recalculateAndPersistCocktails(barId: Int, shelfIds: Set<Int>) {
        val cocktails = cocktailDao.getCocktailsWithIngredientsForBar(barId)
        val (updatedCocktails, updatedIngredients) = recalculateCocktailAvailability(cocktails, shelfIds)
        cocktailDao.updateCocktailsAndIngredients(updatedCocktails, updatedIngredients)
    }

    private fun triggerBackgroundSync() {
        lastBackgroundSyncJob?.cancel()
        lastBackgroundSyncJob = backgroundScope.launch {
            runCatching { cocktailRepository.syncAll(force = true) }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    private suspend fun ensureUserId(): Int =
        sessionRepository.currentUserId()
            ?: api.getProfile().id.also { sessionRepository.setActiveUserId(it) }

    private suspend fun currentActiveBarId(): Int? =
        (sessionRepository.sessionState.value as? SessionState.Authenticated)?.activeBarId
            ?: sessionRepository.currentBarId()

    private suspend fun requireActiveBarId(): Int =
        currentActiveBarId() ?: throw IllegalStateException("No active bar selected.")

    private suspend inline fun <T> runApiCall(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
