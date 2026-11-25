package com.ryanvidal.barfriend.core.data

import com.ryanvidal.barfriend.core.database.entity.ShoppingListEntity
import com.ryanvidal.barfriend.core.model.IngredientSummary
import com.ryanvidal.barfriend.core.model.RecommendedIngredient
import com.ryanvidal.barfriend.core.model.ShoppingListItem
import com.ryanvidal.barfriend.core.model.UnlockedCocktail
import com.ryanvidal.barfriend.core.network.dto.ShoppingListIngredientInputDto
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultInventoryRepository(
    private val localDataSource: BarLocalDataSource,
    private val remoteDataSource: BarRemoteDataSource,
    private val sessionRepository: SessionRepository,
    private val syncCoordinator: BarSyncCoordinator,
    private val mutationCoordinator: BarMutationCoordinator,
) : InventoryRepository {

    private val activeBarIdFlow: Flow<Int?> = sessionRepository.activeBarIdFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeBarShelfIngredients(): Flow<List<IngredientSummary>> =
        activeBarIdFlow.flatMapLatest { barId ->
            if (barId == null) {
                flowOf(emptyList())
            } else {
                localDataSource.observeBarShelfIngredients(barId)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeShoppingList(): Flow<List<ShoppingListItem>> =
        activeBarIdFlow.flatMapLatest { barId ->
            if (barId == null) {
                flowOf(emptyList())
            } else {
                localDataSource.observeShoppingList(barId)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeShoppingListIds(): Flow<Set<Int>> =
        activeBarIdFlow.flatMapLatest { barId ->
            if (barId == null) {
                flowOf(emptySet())
            } else {
                localDataSource.observeShoppingListIds(barId)
            }
        }

    override suspend fun syncShoppingList(): Result<Unit> {
        val originEpoch = sessionRepository.currentSessionEpoch()
        val originBarId = sessionRepository.currentActiveBarId()
            ?: return Result.failure(IllegalStateException("No active bar selected."))
        return runSuspendCatching {
            mutationCoordinator.withMutationLock {
                sessionRepository.ensureSessionEpochValid(originEpoch, originBarId)
                val remoteItems = remoteDataSource.getShoppingList(originBarId)
                sessionRepository.ensureSessionEpochValid(originEpoch, originBarId)
                val entities = remoteItems
                    .distinctBy { it.ingredient.id }
                    .map { it.toEntity(originBarId) }
                withContext(NonCancellable) {
                    sessionRepository.ensureSessionEpochValid(originEpoch, originBarId)
                    mutationCoordinator.bumpGenerationLocked()
                    localDataSource.replaceShoppingList(originBarId, entities)
                }
            }
        }
    }

    override suspend fun setInBarShelf(ingredientIds: List<Int>, inStock: Boolean): Result<Unit> {
        val ids = ingredientIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return Result.success(Unit)
        val originEpoch = sessionRepository.currentSessionEpoch()
        val originBarId = sessionRepository.currentActiveBarId()
            ?: return Result.failure(IllegalStateException("No active bar selected."))
        return runSuspendCatching {
            mutationCoordinator.withMutationLock {
                sessionRepository.ensureSessionEpochValid(originEpoch, originBarId)
                mutateBarShelfLocked(
                    barId = originBarId,
                    originEpoch = originEpoch,
                    ids = ids,
                    inStock = inStock,
                )
                syncCoordinator.scheduleBackgroundSync(originEpoch)
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
        val originBarId = sessionRepository.currentActiveBarId()
            ?: return Result.failure(IllegalStateException("No active bar selected."))
        return runSuspendCatching {
            mutationCoordinator.withMutationLock {
                sessionRepository.ensureSessionEpochValid(originEpoch, originBarId)
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
        val originBarId = sessionRepository.currentActiveBarId()
            ?: return Result.failure(IllegalStateException("No active bar selected."))
        return runSuspendCatching {
            mutationCoordinator.withMutationLock {
                sessionRepository.ensureSessionEpochValid(originEpoch, originBarId)
                var shelfMutated = false
                try {
                    mutateBarShelfLocked(
                        barId = originBarId,
                        originEpoch = originEpoch,
                        ids = ids,
                        inStock = true,
                    )
                    shelfMutated = true
                    sessionRepository.ensureSessionEpochValid(originEpoch, originBarId)
                    removeFromShoppingListLocked(
                        barId = originBarId,
                        originEpoch = originEpoch,
                        ids = ids,
                    )
                } finally {
                    if (shelfMutated && sessionRepository.isSessionEpochValid(originEpoch, originBarId)) {
                        syncCoordinator.scheduleBackgroundSync(originEpoch)
                    }
                }
            }
        }
    }

    override suspend fun getRecommendedIngredients(): Result<List<RecommendedIngredient>> = runSuspendCatching {
        val barId = mutationCoordinator.withMutationLock { sessionRepository.requireActiveBarId() }
        val recommended = remoteDataSource.getRecommendedBarIngredients(barId)
        val ingredientsById = localDataSource.getIngredientsForBar(barId).associateBy { it.id }
        val shoppingListIds = localDataSource.getShoppingListSnapshot(barId)
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
            val barId = mutationCoordinator.withMutationLock { sessionRepository.requireActiveBarId() }
            remoteDataSource.getExtraCocktailsForIngredient(barId, ingredientId)
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
        val originBarId = sessionRepository.currentActiveBarId()
            ?: return Result.failure(IllegalStateException("No active bar selected."))
        return runSuspendCatching {
            mutationCoordinator.withMutationLock {
                sessionRepository.ensureSessionEpochValid(originEpoch, originBarId)
                val userContext = remoteDataSource.resolveUserContext()
                sessionRepository.ensureSessionEpochValid(originEpoch, originBarId)
                val previousItems = localDataSource.getShoppingListSnapshot(originBarId)
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
                val requestItems = targetEntities.map { entity ->
                    ShoppingListIngredientInputDto(
                        id = entity.ingredientId,
                        quantity = entity.quantity,
                    )
                }

                mutationCoordinator.runOptimisticMutationLocked(
                    sessionRepository = sessionRepository,
                    originEpoch = originEpoch,
                    originBarId = originBarId,
                    applyLocal = { localDataSource.upsertShoppingListItems(targetEntities) },
                    runRemote = { remoteDataSource.addToShoppingList(originBarId, userContext, requestItems) },
                    rollbackLocal = { localDataSource.replaceShoppingList(originBarId, previousItems) },
                )
            }
        }
    }

    private suspend fun mutateBarShelfLocked(
        barId: Int,
        originEpoch: Long,
        ids: List<Int>,
        inStock: Boolean,
    ) {
        val plan = localDataSource.prepareBarShelfMutation(barId, ids, inStock)
        val toggledSet = ids.toSet()
        mutationCoordinator.runOptimisticMutationLocked(
            sessionRepository = sessionRepository,
            originEpoch = originEpoch,
            originBarId = barId,
            applyLocal = { localDataSource.commitBarShelfMutation(barId, plan) },
            runRemote = { remoteDataSource.setBarShelfIngredients(barId, ids, inStock) },
            rollbackLocal = {
                localDataSource.restoreBarShelfAndRecalculate(
                    barId = barId,
                    previousShelf = plan.previousShelf,
                    toggledIngredientIds = toggledSet,
                )
            },
        )
    }

    private suspend fun removeFromShoppingListLocked(
        barId: Int,
        originEpoch: Long,
        ids: List<Int>,
    ) {
        val userContext = remoteDataSource.resolveUserContext()
        sessionRepository.ensureSessionEpochValid(originEpoch, barId)
        val previousItems = localDataSource.getShoppingListSnapshot(barId)

        mutationCoordinator.runOptimisticMutationLocked(
            sessionRepository = sessionRepository,
            originEpoch = originEpoch,
            originBarId = barId,
            applyLocal = { localDataSource.deleteShoppingListItems(barId, ids) },
            runRemote = { remoteDataSource.removeFromShoppingList(barId, userContext, ids) },
            rollbackLocal = { localDataSource.replaceShoppingList(barId, previousItems) },
        )
    }
}
