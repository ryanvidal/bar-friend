package com.ryanvidal.barfriend.feature.cocktails

import com.ryanvidal.barfriend.core.data.InventoryRepository
import com.ryanvidal.barfriend.core.model.IngredientSummary
import com.ryanvidal.barfriend.core.model.RecommendedIngredient
import com.ryanvidal.barfriend.core.model.ShoppingListItem
import com.ryanvidal.barfriend.core.model.UnlockedCocktail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

internal data class AddToShoppingListCall(
    val ingredientIds: List<Int>,
    val quantity: Int,
)

internal data class SetInBarShelfCall(
    val ingredientIds: List<Int>,
    val inStock: Boolean,
)

internal class FakeInventoryRepository(
    initialBarShelfIngredients: List<IngredientSummary> = emptyList(),
    initialShoppingListIds: Set<Int> = emptySet(),
) : InventoryRepository {
    private val barShelfFlow = MutableStateFlow(initialBarShelfIngredients)
    private val shoppingListFlow = MutableStateFlow(
        initialShoppingListIds.map(::placeholderShoppingListItem),
    )

    var syncShoppingListFailure: Throwable? = null
    var setInBarShelfFailure: Throwable? = null
    var addToShoppingListFailure: Throwable? = null
    var addToShoppingListSuspendHook: (suspend (List<Int>, Int) -> Unit)? = null
    var updateQuantityFailure: Throwable? = null
    var removeFromShoppingListFailure: Throwable? = null
    var removeFromShoppingListSuspendHook: (suspend (List<Int>) -> Unit)? = null
    var markPurchasedFailure: Throwable? = null
    var delayShoppingIdsEmission: CompletableDeferred<Unit>? = null

    var syncShoppingListCalls = 0
    val setInBarShelfCalls = mutableListOf<SetInBarShelfCall>()
    val addToShoppingListCalls = mutableListOf<AddToShoppingListCall>()
    val updateQuantityCalls = mutableListOf<Pair<Int, Int>>()
    val removeFromShoppingListCalls = mutableListOf<List<Int>>()
    val markPurchasedCalls = mutableListOf<List<Int>>()

    fun setShoppingListIds(ids: Set<Int>) {
        shoppingListFlow.value = ids.map(::placeholderShoppingListItem)
    }

    override fun observeBarShelfIngredients(): Flow<List<IngredientSummary>> = barShelfFlow

    override fun observeShoppingList(): Flow<List<ShoppingListItem>> = shoppingListFlow

    override fun observeShoppingListIds(): Flow<Set<Int>> =
        shoppingListFlow.map { items ->
            delayShoppingIdsEmission?.await()
            items.mapTo(HashSet()) { it.ingredientId }
        }

    override suspend fun syncShoppingList(): Result<Unit> {
        syncShoppingListCalls += 1
        syncShoppingListFailure?.let { return Result.failure(it) }
        return Result.success(Unit)
    }

    override suspend fun setInBarShelf(ingredientIds: List<Int>, inStock: Boolean): Result<Unit> {
        setInBarShelfCalls += SetInBarShelfCall(ingredientIds, inStock)
        setInBarShelfFailure?.let { return Result.failure(it) }
        val targets = ingredientIds.toHashSet()
        barShelfFlow.update { current ->
            current.map { item ->
                if (item.id in targets) item.copy(inBarShelf = inStock) else item
            }
        }
        return Result.success(Unit)
    }

    override suspend fun addToShoppingList(ingredientIds: List<Int>, quantity: Int): Result<Unit> {
        val ids = ingredientIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return Result.success(Unit)
        addToShoppingListCalls += AddToShoppingListCall(ids, quantity)
        val targetSet = ids.toHashSet()
        val resolvedQuantity = quantity.coerceAtLeast(1)
        val previousForTargets = shoppingListFlow.value
            .filter { it.ingredientId in targetSet }
            .associateBy { it.ingredientId }
        shoppingListFlow.update { current ->
            val existingIds = current.mapTo(HashSet()) { it.ingredientId }
            val additions = ids
                .filterNot { it in existingIds }
                .map { id -> placeholderShoppingListItem(id, resolvedQuantity) }
            current + additions
        }
        try {
            addToShoppingListSuspendHook?.invoke(ids, quantity)
            addToShoppingListFailure?.let { failure ->
                rollbackShoppingList(targetSet, previousForTargets)
                return Result.failure(failure)
            }
            return Result.success(Unit)
        } catch (e: CancellationException) {
            rollbackShoppingList(targetSet, previousForTargets)
            throw e
        } catch (e: Throwable) {
            rollbackShoppingList(targetSet, previousForTargets)
            return Result.failure(e)
        }
    }

    override suspend fun updateShoppingListQuantity(ingredientId: Int, quantity: Int): Result<Unit> {
        updateQuantityCalls += ingredientId to quantity
        updateQuantityFailure?.let { return Result.failure(it) }
        shoppingListFlow.update { current ->
            if (quantity <= 0) {
                current.filterNot { it.ingredientId == ingredientId }
            } else {
                current.map { item ->
                    if (item.ingredientId == ingredientId) item.copy(quantity = quantity) else item
                }
            }
        }
        return Result.success(Unit)
    }

    override suspend fun removeFromShoppingList(ingredientIds: List<Int>): Result<Unit> {
        val ids = ingredientIds.filter { it > 0 }.distinct()
        if (ids.isEmpty()) return Result.success(Unit)
        removeFromShoppingListCalls += ids
        val targetSet = ids.toHashSet()
        val previousForTargets = shoppingListFlow.value
            .filter { it.ingredientId in targetSet }
            .associateBy { it.ingredientId }
        shoppingListFlow.update { current ->
            current.filterNot { it.ingredientId in targetSet }
        }
        try {
            removeFromShoppingListSuspendHook?.invoke(ids)
            removeFromShoppingListFailure?.let { failure ->
                rollbackShoppingList(targetSet, previousForTargets)
                return Result.failure(failure)
            }
            return Result.success(Unit)
        } catch (e: CancellationException) {
            rollbackShoppingList(targetSet, previousForTargets)
            throw e
        } catch (e: Throwable) {
            rollbackShoppingList(targetSet, previousForTargets)
            return Result.failure(e)
        }
    }

    override suspend fun markPurchasedToBarShelf(ingredientIds: List<Int>): Result<Unit> {
        markPurchasedCalls += ingredientIds
        markPurchasedFailure?.let { return Result.failure(it) }
        val purchasedIds = ingredientIds.toHashSet()
        shoppingListFlow.update { current ->
            current.filterNot { it.ingredientId in purchasedIds }
        }
        barShelfFlow.update { current ->
            current.map { item ->
                if (item.id in purchasedIds) item.copy(inBarShelf = true, inShoppingList = false) else item
            }
        }
        return Result.success(Unit)
    }

    override suspend fun getRecommendedIngredients(): Result<List<RecommendedIngredient>> =
        Result.success(emptyList())

    override suspend fun getUnlockedCocktails(ingredientId: Int): Result<List<UnlockedCocktail>> =
        Result.success(emptyList())

    private fun rollbackShoppingList(
        targetSet: Set<Int>,
        previousForTargets: Map<Int, ShoppingListItem>,
    ) {
        shoppingListFlow.update { current ->
            val withoutTargets = current.filterNot { it.ingredientId in targetSet }
            val restored = previousForTargets.values.filter { prev ->
                withoutTargets.none { it.ingredientId == prev.ingredientId }
            }
            withoutTargets + restored
        }
    }

    private fun placeholderShoppingListItem(ingredientId: Int, quantity: Int = 1): ShoppingListItem =
        ShoppingListItem(
            ingredientId = ingredientId,
            ingredientSlug = "ingredient-$ingredientId",
            ingredientName = "Ingredient $ingredientId",
            categoryName = null,
            quantity = quantity,
            mainImageThumbUrl = null,
        )
}
