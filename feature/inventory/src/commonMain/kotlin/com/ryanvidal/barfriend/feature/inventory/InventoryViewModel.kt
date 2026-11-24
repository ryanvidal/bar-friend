package com.ryanvidal.barfriend.feature.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ryanvidal.barfriend.core.data.BarSyncCoordinator
import com.ryanvidal.barfriend.core.data.InventoryRepository
import com.ryanvidal.barfriend.core.data.SyncStatus
import com.ryanvidal.barfriend.core.model.IngredientSummary
import com.ryanvidal.barfriend.core.model.RecommendedIngredient
import com.ryanvidal.barfriend.core.model.ShoppingListItem
import com.ryanvidal.barfriend.core.model.UnlockedCocktail
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val QUANTITY_DEBOUNCE_MILLIS = 250L

enum class InventoryTab(val label: String) {
    BAR_SHELF("Bar Shelf"),
    SHOPPING_LIST("Shopping List"),
}

enum class ShelfStockFilter(val label: String) {
    ALL("All"),
    IN_STOCK("In Stock"),
    OUT_OF_STOCK("Out of Stock"),
}

data class InventorySnackbarEvent(
    val id: Long,
    val message: String,
)

data class UnlockedCocktailsSheetState(
    val ingredientId: Int,
    val ingredientName: String,
    val inBarShelf: Boolean,
    val inShoppingList: Boolean,
    val isLoading: Boolean = true,
    val cocktails: List<UnlockedCocktail> = emptyList(),
    val errorMessage: String? = null,
)

data class InventoryUiState(
    val selectedTab: InventoryTab = InventoryTab.BAR_SHELF,
    val searchQuery: String = "",
    val stockFilter: ShelfStockFilter = ShelfStockFilter.ALL,
    val selectedCategory: String? = null,
    val allIngredients: List<IngredientSummary> = emptyList(),
    val filteredIngredients: List<IngredientSummary> = emptyList(),
    val categories: List<String> = emptyList(),
    val inStockCount: Int = 0,
    val totalIngredientsCount: Int = 0,
    val shoppingList: List<ShoppingListItem> = emptyList(),
    val recommendations: List<RecommendedIngredient> = emptyList(),
    val isLoadingRecommendations: Boolean = false,
    val recommendationsError: String? = null,
    val unlockedSheet: UnlockedCocktailsSheetState? = null,
    val inFlightShelfIds: Set<Int> = emptySet(),
    val inFlightShoppingIds: Set<Int> = emptySet(),
    val inFlightPurchaseIds: Set<Int> = emptySet(),
    val pendingQuantityIds: Set<Int> = emptySet(),
    val inFlightQuantityIds: Set<Int> = emptySet(),
    val isBulkUpdating: Boolean = false,
    val isRefreshing: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus(),
    val snackbarEvent: InventorySnackbarEvent? = null,
) {
    val snackbarMessage: String?
        get() = snackbarEvent?.message

    val hasInFlightItemMutations: Boolean
        get() = inFlightShelfIds.isNotEmpty() ||
            inFlightShoppingIds.isNotEmpty() ||
            inFlightPurchaseIds.isNotEmpty() ||
            pendingQuantityIds.isNotEmpty() ||
            inFlightQuantityIds.isNotEmpty()
}

private data class UnlockedSheetDraft(
    val ingredientId: Int,
    val ingredientName: String,
    val isLoading: Boolean = true,
    val cocktails: List<UnlockedCocktail> = emptyList(),
    val errorMessage: String? = null,
)

private data class InventoryInteractionState(
    val selectedTab: InventoryTab = InventoryTab.BAR_SHELF,
    val searchQuery: String = "",
    val stockFilter: ShelfStockFilter = ShelfStockFilter.ALL,
    val selectedCategory: String? = null,
    val recommendations: List<RecommendedIngredient> = emptyList(),
    val isLoadingRecommendations: Boolean = false,
    val recommendationsError: String? = null,
    val unlockedSheetDraft: UnlockedSheetDraft? = null,
    val inFlightShelfIds: Set<Int> = emptySet(),
    val inFlightShoppingIds: Set<Int> = emptySet(),
    val inFlightPurchaseIds: Set<Int> = emptySet(),
    val pendingQuantityIds: Set<Int> = emptySet(),
    val inFlightQuantityIds: Set<Int> = emptySet(),
    val optimisticShelfStates: Map<Int, Boolean> = emptyMap(),
    val optimisticShoppingStates: Map<Int, Boolean> = emptyMap(),
    val optimisticQuantities: Map<Int, Int> = emptyMap(),
    val roomShelfIds: Set<Int> = emptySet(),
    val roomCatalogShoppingIds: Set<Int> = emptySet(),
    val roomCatalogNames: Map<Int, String> = emptyMap(),
    val roomShoppingMap: Map<Int, Int> = emptyMap(),
    val roomShoppingNames: Map<Int, String> = emptyMap(),
    val hasReceivedShoppingList: Boolean = false,
    val isBulkUpdating: Boolean = false,
    val isRefreshing: Boolean = false,
    val lastSnackbarId: Long = 0L,
    val snackbarEvent: InventorySnackbarEvent? = null,
) {
    val hasInFlightItemMutations: Boolean
        get() = inFlightShelfIds.isNotEmpty() ||
            inFlightShoppingIds.isNotEmpty() ||
            inFlightPurchaseIds.isNotEmpty() ||
            pendingQuantityIds.isNotEmpty() ||
            inFlightQuantityIds.isNotEmpty()
}

private data class InventoryDataSnapshot(
    val catalogIngredients: List<IngredientSummary> = emptyList(),
    val barShelfIds: Set<Int> = emptySet(),
    val shoppingList: List<ShoppingListItem> = emptyList(),
    val shoppingListIds: Set<Int> = emptySet(),
    val syncStatus: SyncStatus = SyncStatus(),
)

@Inject
class InventoryViewModel(
    private val inventoryRepository: InventoryRepository,
    private val syncCoordinator: BarSyncCoordinator,
) : ViewModel() {
    private val interaction = MutableStateFlow(InventoryInteractionState())

    private var recommendationsJob: Job? = null
    private var recommendationsGeneration: Long = 0L
    private var hasLoadedInitialRecommendations: Boolean = false
    private var unlockedCocktailsJob: Job? = null
    private var unlockedSheetRequestId: Long = 0L
    private val quantityUpdateJobs = mutableMapOf<Int, Job>()
    private val quantityGenerations = mutableMapOf<Int, Long>()

    private val barShelfIngredientsFlow: SharedFlow<List<IngredientSummary>> = inventoryRepository
        .observeBarShelfIngredients()
        .shareIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            replay = 1,
        )

    private val shoppingListFlow: SharedFlow<List<ShoppingListItem>> = inventoryRepository
        .observeShoppingList()
        .shareIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            replay = 1,
        )

    private val dataFlow: Flow<InventoryDataSnapshot> = combine(
        barShelfIngredientsFlow,
        shoppingListFlow,
        syncCoordinator.syncStatus,
    ) { catalogIngredients, shoppingList, syncStatus ->
        val barShelfIds = catalogIngredients.asSequence()
            .filter { it.inBarShelf }
            .mapTo(HashSet(catalogIngredients.size)) { it.id }
        val shoppingListIds = HashSet<Int>(shoppingList.size + catalogIngredients.size).apply {
            shoppingList.forEach { add(it.ingredientId) }
            catalogIngredients.forEach { if (it.inShoppingList) add(it.id) }
        }
        InventoryDataSnapshot(
            catalogIngredients = catalogIngredients,
            barShelfIds = barShelfIds,
            shoppingList = shoppingList,
            shoppingListIds = shoppingListIds,
            syncStatus = syncStatus,
        )
    }

    init {
        viewModelScope.launch {
            barShelfIngredientsFlow.collect { ingredients ->
                val roomShelfIds = ingredients.asSequence()
                    .filter { it.inBarShelf }
                    .mapTo(HashSet(ingredients.size)) { it.id }
                val roomCatalogShoppingIds = ingredients.asSequence()
                    .filter { it.inShoppingList }
                    .mapTo(HashSet(ingredients.size)) { it.id }
                val roomCatalogNames = ingredients.associate { it.id to it.name }
                val validCategories = ingredients.mapNotNullTo(HashSet()) {
                    it.categoryName?.trim()?.takeIf { name -> name.isNotEmpty() }
                }

                interaction.update { current ->
                    val selected = current.selectedCategory
                    val prunedCategory = if (selected != null && selected !in validCategories) null else selected
                    current.copy(
                        selectedCategory = prunedCategory,
                        roomShelfIds = roomShelfIds,
                        roomCatalogShoppingIds = roomCatalogShoppingIds,
                        roomCatalogNames = roomCatalogNames,
                        optimisticShelfStates = current.optimisticShelfStates.filterNot { (id, target) ->
                            id !in current.inFlightShelfIds &&
                                id !in current.inFlightPurchaseIds &&
                                (id in roomShelfIds) == target
                        },
                        optimisticShoppingStates = current.optimisticShoppingStates.filterNot { (id, target) ->
                            isShoppingOverrideConverged(
                                ingredientId = id,
                                target = target,
                                inFlightShoppingIds = current.inFlightShoppingIds,
                                inFlightPurchaseIds = current.inFlightPurchaseIds,
                                roomShoppingMap = current.roomShoppingMap,
                                roomCatalogShoppingIds = roomCatalogShoppingIds,
                            )
                        },
                    )
                }

                if (ingredients.isEmpty()) {
                    if (hasLoadedInitialRecommendations) {
                        recommendationsJob?.cancel()
                        interaction.update {
                            it.copy(
                                recommendations = emptyList(),
                                isLoadingRecommendations = false,
                                recommendationsError = null,
                            )
                        }
                    }
                    hasLoadedInitialRecommendations = false
                } else if (!hasLoadedInitialRecommendations) {
                    hasLoadedInitialRecommendations = true
                    if (recommendationsJob?.isActive != true) {
                        launchRecommendationsLoad()
                    }
                }
            }
        }
        viewModelScope.launch {
            shoppingListFlow.collect { shoppingList ->
                val roomShoppingMap = shoppingList.associate { it.ingredientId to it.quantity }
                val roomShoppingNames = shoppingList.associate { it.ingredientId to it.ingredientName }
                interaction.update { current ->
                    val pendingOrInFlightQuantityIds = current.pendingQuantityIds + current.inFlightQuantityIds
                    current.copy(
                        hasReceivedShoppingList = true,
                        roomShoppingMap = roomShoppingMap,
                        roomShoppingNames = roomShoppingNames,
                        optimisticShoppingStates = current.optimisticShoppingStates.filterNot { (id, target) ->
                            isShoppingOverrideConverged(
                                ingredientId = id,
                                target = target,
                                inFlightShoppingIds = current.inFlightShoppingIds,
                                inFlightPurchaseIds = current.inFlightPurchaseIds,
                                roomShoppingMap = roomShoppingMap,
                                roomCatalogShoppingIds = current.roomCatalogShoppingIds,
                            )
                        },
                        optimisticQuantities = current.optimisticQuantities.filterNot { (id, targetQty) ->
                            id !in pendingOrInFlightQuantityIds && (
                                roomShoppingMap[id] == targetQty ||
                                    (id !in roomShoppingMap && targetQty <= 0)
                                )
                        },
                    )
                }
            }
        }
    }

    val uiState: StateFlow<InventoryUiState> = combine(
        dataFlow,
        interaction,
    ) { data, local ->
        val effectiveShelfIds = applyBooleanOverrides(data.barShelfIds, local.optimisticShelfStates)
        val effectiveShoppingIds = applyBooleanOverrides(data.shoppingListIds, local.optimisticShoppingStates)

        val allIngredients = data.catalogIngredients.map { ingredient ->
            val inShelf = ingredient.id in effectiveShelfIds
            val inShopping = ingredient.id in effectiveShoppingIds
            if (ingredient.inBarShelf == inShelf && ingredient.inShoppingList == inShopping) {
                ingredient
            } else {
                ingredient.copy(
                    inBarShelf = inShelf,
                    inShoppingList = inShopping,
                )
            }
        }

        val categories = allIngredients
            .mapNotNull { it.categoryName?.trim()?.takeIf { name -> name.isNotEmpty() } }
            .distinct()
            .sortedWith(compareBy<String> { it.lowercase() }.thenBy { it })

        val activeCategory = local.selectedCategory?.takeIf { it in categories }
        val trimmedQuery = local.searchQuery.trim()
        val filteredIngredients = allIngredients.filter { ingredient ->
            val matchesStock = when (local.stockFilter) {
                ShelfStockFilter.ALL -> true
                ShelfStockFilter.IN_STOCK -> ingredient.inBarShelf
                ShelfStockFilter.OUT_OF_STOCK -> !ingredient.inBarShelf
            }
            val matchesCategory = activeCategory == null ||
                ingredient.categoryName?.trim() == activeCategory
            val matchesQuery = trimmedQuery.isEmpty() ||
                ingredient.name.contains(trimmedQuery, ignoreCase = true) ||
                (ingredient.categoryName?.contains(trimmedQuery, ignoreCase = true) == true)
            matchesStock && matchesCategory && matchesQuery
        }

        val shoppingList = data.shoppingList
            .filter { item -> local.optimisticShoppingStates[item.ingredientId] != false }
            .map { item ->
                val optimisticQty = local.optimisticQuantities[item.ingredientId]
                if (optimisticQty != null && optimisticQty != item.quantity) {
                    item.copy(quantity = optimisticQty)
                } else {
                    item
                }
            }

        val catalogById = if (local.recommendations.isEmpty()) {
            emptyMap()
        } else {
            data.catalogIngredients.associateBy { it.id }
        }
        val syncedRecommendations = local.recommendations
            .filter { rec -> rec.id !in effectiveShelfIds }
            .map { rec ->
                val info = catalogById[rec.id]
                val inShopping = rec.id in effectiveShoppingIds
                val categoryName = rec.categoryName ?: info?.categoryName
                val thumbUrl = rec.mainImageThumbUrl ?: info?.mainImageThumbUrl
                if (rec.inShoppingList == inShopping &&
                    rec.categoryName == categoryName &&
                    rec.mainImageThumbUrl == thumbUrl
                ) {
                    rec
                } else {
                    rec.copy(
                        categoryName = categoryName,
                        mainImageThumbUrl = thumbUrl,
                        inShoppingList = inShopping,
                    )
                }
            }

        val unlockedSheet = local.unlockedSheetDraft?.let { draft ->
            UnlockedCocktailsSheetState(
                ingredientId = draft.ingredientId,
                ingredientName = draft.ingredientName,
                inBarShelf = draft.ingredientId in effectiveShelfIds,
                inShoppingList = draft.ingredientId in effectiveShoppingIds,
                isLoading = draft.isLoading,
                cocktails = draft.cocktails,
                errorMessage = draft.errorMessage,
            )
        }

        InventoryUiState(
            selectedTab = local.selectedTab,
            searchQuery = local.searchQuery,
            stockFilter = local.stockFilter,
            selectedCategory = activeCategory,
            allIngredients = allIngredients,
            filteredIngredients = filteredIngredients,
            categories = categories,
            inStockCount = allIngredients.count { it.inBarShelf },
            totalIngredientsCount = allIngredients.size,
            shoppingList = shoppingList,
            recommendations = syncedRecommendations,
            isLoadingRecommendations = local.isLoadingRecommendations,
            recommendationsError = local.recommendationsError,
            unlockedSheet = unlockedSheet,
            inFlightShelfIds = local.inFlightShelfIds,
            inFlightShoppingIds = local.inFlightShoppingIds,
            inFlightPurchaseIds = local.inFlightPurchaseIds,
            pendingQuantityIds = local.pendingQuantityIds,
            inFlightQuantityIds = local.inFlightQuantityIds,
            isBulkUpdating = local.isBulkUpdating,
            isRefreshing = local.isRefreshing,
            syncStatus = data.syncStatus,
            snackbarEvent = local.snackbarEvent,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = InventoryUiState(),
    )

    fun selectTab(tab: InventoryTab) {
        interaction.update { it.copy(selectedTab = tab) }
    }

    fun onSearchQueryChanged(query: String) {
        interaction.update { it.copy(searchQuery = query) }
    }

    fun selectStockFilter(filter: ShelfStockFilter) {
        interaction.update { it.copy(stockFilter = filter) }
    }

    fun selectCategory(category: String?) {
        interaction.update { it.copy(selectedCategory = category) }
    }

    fun clearFilters() {
        interaction.update {
            it.copy(
                searchQuery = "",
                stockFilter = ShelfStockFilter.ALL,
                selectedCategory = null,
            )
        }
    }

    fun refreshAll() {
        while (true) {
            val current = interaction.value
            if (current.isRefreshing) return
            if (interaction.compareAndSet(current, current.copy(isRefreshing = true))) {
                break
            }
        }
        viewModelScope.launch {
            try {
                val syncResult = syncCoordinator.syncAll(force = true)
                if (syncResult.isSuccess) {
                    val shoppingResult = inventoryRepository.syncShoppingList()
                    shoppingResult.onFailure { error ->
                        val shoppingError = error.message?.takeIf { it.isNotBlank() }
                        emitSnackbar(shoppingError ?: "Unable to sync shopping list.")
                    }
                    launchRecommendationsLoad().join()
                } else {
                    val syncError = syncResult.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                    if (syncCoordinator.syncStatus.value.errorMessage == null) {
                        emitSnackbar(syncError ?: "Sync failed.")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                interaction.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun loadRecommendations() {
        launchRecommendationsLoad()
    }

    fun toggleBarShelf(ingredientId: Int, inStock: Boolean) {
        while (true) {
            val current = interaction.value
            if (current.isBulkUpdating ||
                ingredientId in current.inFlightShelfIds ||
                ingredientId in current.inFlightPurchaseIds
            ) {
                return
            }
            val next = current.copy(
                inFlightShelfIds = current.inFlightShelfIds + ingredientId,
                optimisticShelfStates = current.optimisticShelfStates + (ingredientId to inStock),
            )
            if (interaction.compareAndSet(current, next)) {
                break
            }
        }
        viewModelScope.launch {
            var succeeded = false
            try {
                inventoryRepository.setInBarShelf(listOf(ingredientId), inStock)
                    .onSuccess {
                        succeeded = true
                        launchRecommendationsLoad()
                    }
                    .onFailure { error ->
                        emitSnackbar(
                            error.message?.takeIf { msg -> msg.isNotBlank() }
                                ?: "Unable to update bar shelf.",
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            } finally {
                interaction.update { current ->
                    val remainingShelf = current.inFlightShelfIds - ingredientId
                    val converged = succeeded && (ingredientId in current.roomShelfIds) == inStock
                    current.copy(
                        inFlightShelfIds = remainingShelf,
                        optimisticShelfStates = if (!succeeded || converged) {
                            current.optimisticShelfStates - ingredientId
                        } else {
                            current.optimisticShelfStates
                        },
                    )
                }
            }
        }
    }

    fun toggleShoppingList(ingredientId: Int, inShoppingList: Boolean) {
        while (true) {
            val current = interaction.value
            if (current.isBulkUpdating ||
                ingredientId in current.inFlightShoppingIds ||
                ingredientId in current.inFlightPurchaseIds
            ) {
                return
            }
            val next = current.copy(
                inFlightShoppingIds = current.inFlightShoppingIds + ingredientId,
                pendingQuantityIds = current.pendingQuantityIds - ingredientId,
                inFlightQuantityIds = current.inFlightQuantityIds - ingredientId,
                optimisticQuantities = current.optimisticQuantities - ingredientId,
                optimisticShoppingStates = current.optimisticShoppingStates + (ingredientId to inShoppingList),
            )
            if (interaction.compareAndSet(current, next)) {
                break
            }
        }
        cancelQuantityJob(ingredientId)
        viewModelScope.launch {
            var succeeded = false
            try {
                val result = if (inShoppingList) {
                    inventoryRepository.addToShoppingList(listOf(ingredientId), quantity = 1)
                } else {
                    inventoryRepository.removeFromShoppingList(listOf(ingredientId))
                }
                result
                    .onSuccess {
                        succeeded = true
                    }
                    .onFailure { error ->
                        emitSnackbar(
                            error.message?.takeIf { msg -> msg.isNotBlank() }
                                ?: "Unable to update shopping list.",
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            } finally {
                interaction.update { current ->
                    val remainingShopping = current.inFlightShoppingIds - ingredientId
                    val converged = succeeded && isShoppingStateConverged(
                        ingredientId = ingredientId,
                        target = inShoppingList,
                        roomShoppingMap = current.roomShoppingMap,
                        roomCatalogShoppingIds = current.roomCatalogShoppingIds,
                    )
                    current.copy(
                        inFlightShoppingIds = remainingShopping,
                        optimisticShoppingStates = if (!succeeded || converged) {
                            current.optimisticShoppingStates - ingredientId
                        } else {
                            current.optimisticShoppingStates
                        },
                    )
                }
            }
        }
    }

    fun addToShoppingList(ingredientId: Int) {
        while (true) {
            val current = interaction.value
            if (current.isBulkUpdating ||
                ingredientId in current.inFlightShoppingIds ||
                ingredientId in current.inFlightPurchaseIds
            ) {
                return
            }
            val next = current.copy(
                inFlightShoppingIds = current.inFlightShoppingIds + ingredientId,
                pendingQuantityIds = current.pendingQuantityIds - ingredientId,
                inFlightQuantityIds = current.inFlightQuantityIds - ingredientId,
                optimisticQuantities = current.optimisticQuantities - ingredientId,
                optimisticShoppingStates = current.optimisticShoppingStates + (ingredientId to true),
            )
            if (interaction.compareAndSet(current, next)) {
                break
            }
        }
        cancelQuantityJob(ingredientId)
        viewModelScope.launch {
            var succeeded = false
            try {
                inventoryRepository.addToShoppingList(listOf(ingredientId), quantity = 1)
                    .onSuccess {
                        succeeded = true
                    }
                    .onFailure { error ->
                        emitSnackbar(
                            error.message?.takeIf { msg -> msg.isNotBlank() }
                                ?: "Unable to add to shopping list.",
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            } finally {
                interaction.update { current ->
                    val remainingShopping = current.inFlightShoppingIds - ingredientId
                    val converged = succeeded && isShoppingStateConverged(
                        ingredientId = ingredientId,
                        target = true,
                        roomShoppingMap = current.roomShoppingMap,
                        roomCatalogShoppingIds = current.roomCatalogShoppingIds,
                    )
                    current.copy(
                        inFlightShoppingIds = remainingShopping,
                        optimisticShoppingStates = if (!succeeded || converged) {
                            current.optimisticShoppingStates - ingredientId
                        } else {
                            current.optimisticShoppingStates
                        },
                    )
                }
            }
        }
    }

    fun updateQuantity(ingredientId: Int, newQuantity: Int) {
        if (newQuantity <= 0) {
            while (true) {
                val current = interaction.value
                if (current.isBulkUpdating ||
                    ingredientId in current.inFlightPurchaseIds ||
                    ingredientId in current.inFlightShoppingIds
                ) {
                    return
                }
                val next = current.copy(
                    inFlightShoppingIds = current.inFlightShoppingIds + ingredientId,
                    pendingQuantityIds = current.pendingQuantityIds - ingredientId,
                    inFlightQuantityIds = current.inFlightQuantityIds - ingredientId,
                    optimisticQuantities = current.optimisticQuantities - ingredientId,
                    optimisticShoppingStates = current.optimisticShoppingStates + (ingredientId to false),
                )
                if (interaction.compareAndSet(current, next)) {
                    break
                }
            }
            cancelQuantityJob(ingredientId)
            viewModelScope.launch {
                var succeeded = false
                try {
                    inventoryRepository.removeFromShoppingList(listOf(ingredientId))
                        .onSuccess {
                            succeeded = true
                        }
                        .onFailure { error ->
                            emitSnackbar(
                                error.message?.takeIf { msg -> msg.isNotBlank() }
                                    ?: "Unable to update quantity.",
                            )
                        }
                } catch (e: CancellationException) {
                    throw e
                } finally {
                    interaction.update { current ->
                        val remainingShopping = current.inFlightShoppingIds - ingredientId
                        val converged = succeeded && isShoppingStateConverged(
                            ingredientId = ingredientId,
                            target = false,
                            roomShoppingMap = current.roomShoppingMap,
                            roomCatalogShoppingIds = current.roomCatalogShoppingIds,
                        )
                        current.copy(
                            inFlightShoppingIds = remainingShopping,
                            optimisticShoppingStates = if (!succeeded || converged) {
                                current.optimisticShoppingStates - ingredientId
                            } else {
                                current.optimisticShoppingStates
                            },
                        )
                    }
                }
            }
            return
        }

        while (true) {
            val current = interaction.value
            if (current.isBulkUpdating ||
                ingredientId in current.inFlightPurchaseIds ||
                ingredientId in current.inFlightShoppingIds
            ) {
                return
            }
            val next = current.copy(
                pendingQuantityIds = current.pendingQuantityIds + ingredientId,
                inFlightQuantityIds = current.inFlightQuantityIds - ingredientId,
                optimisticQuantities = current.optimisticQuantities + (ingredientId to newQuantity),
            )
            if (interaction.compareAndSet(current, next)) {
                break
            }
        }
        val generation = cancelQuantityJob(ingredientId)
        val job = viewModelScope.launch {
            var succeeded = false
            try {
                delay(QUANTITY_DEBOUNCE_MILLIS)
                var shouldExecute = false
                interaction.update { current ->
                    if (quantityGenerations[ingredientId] == generation) {
                        shouldExecute = true
                        current.copy(
                            pendingQuantityIds = current.pendingQuantityIds - ingredientId,
                            inFlightQuantityIds = current.inFlightQuantityIds + ingredientId,
                        )
                    } else {
                        shouldExecute = false
                        current
                    }
                }
                if (!shouldExecute) return@launch
                inventoryRepository.updateShoppingListQuantity(ingredientId, newQuantity)
                    .onSuccess {
                        if (quantityGenerations[ingredientId] == generation) {
                            succeeded = true
                        }
                    }
                    .onFailure { error ->
                        if (quantityGenerations[ingredientId] == generation) {
                            emitSnackbar(
                                error.message?.takeIf { msg -> msg.isNotBlank() }
                                    ?: "Unable to update quantity.",
                            )
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (quantityGenerations[ingredientId] == generation) {
                    quantityUpdateJobs.remove(ingredientId)
                    interaction.update { current ->
                        if (quantityGenerations[ingredientId] != generation) {
                            current
                        } else {
                            val converged = succeeded && current.roomShoppingMap[ingredientId] == newQuantity
                            current.copy(
                                pendingQuantityIds = current.pendingQuantityIds - ingredientId,
                                inFlightQuantityIds = current.inFlightQuantityIds - ingredientId,
                                optimisticQuantities = if (!succeeded || converged) {
                                    current.optimisticQuantities - ingredientId
                                } else {
                                    current.optimisticQuantities
                                },
                            )
                        }
                    }
                }
            }
        }
        if (job.isActive) {
            quantityUpdateJobs[ingredientId] = job
        }
    }

    fun removeFromShoppingList(ingredientId: Int) {
        while (true) {
            val current = interaction.value
            if (current.isBulkUpdating ||
                ingredientId in current.inFlightShoppingIds ||
                ingredientId in current.inFlightPurchaseIds
            ) {
                return
            }
            val next = current.copy(
                inFlightShoppingIds = current.inFlightShoppingIds + ingredientId,
                pendingQuantityIds = current.pendingQuantityIds - ingredientId,
                inFlightQuantityIds = current.inFlightQuantityIds - ingredientId,
                optimisticQuantities = current.optimisticQuantities - ingredientId,
                optimisticShoppingStates = current.optimisticShoppingStates + (ingredientId to false),
            )
            if (interaction.compareAndSet(current, next)) {
                break
            }
        }
        cancelQuantityJob(ingredientId)
        viewModelScope.launch {
            var succeeded = false
            try {
                inventoryRepository.removeFromShoppingList(listOf(ingredientId))
                    .onSuccess {
                        succeeded = true
                    }
                    .onFailure { error ->
                        emitSnackbar(
                            error.message?.takeIf { msg -> msg.isNotBlank() }
                                ?: "Unable to remove item.",
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            } finally {
                interaction.update { current ->
                    val remainingShopping = current.inFlightShoppingIds - ingredientId
                    val converged = succeeded && isShoppingStateConverged(
                        ingredientId = ingredientId,
                        target = false,
                        roomShoppingMap = current.roomShoppingMap,
                        roomCatalogShoppingIds = current.roomCatalogShoppingIds,
                    )
                    current.copy(
                        inFlightShoppingIds = remainingShopping,
                        optimisticShoppingStates = if (!succeeded || converged) {
                            current.optimisticShoppingStates - ingredientId
                        } else {
                            current.optimisticShoppingStates
                        },
                    )
                }
            }
        }
    }

    fun markPurchased(ingredientId: Int) {
        while (true) {
            val current = interaction.value
            if (current.isBulkUpdating ||
                ingredientId in current.inFlightPurchaseIds ||
                ingredientId in current.inFlightShelfIds ||
                ingredientId in current.inFlightShoppingIds
            ) {
                return
            }
            val next = current.copy(
                inFlightPurchaseIds = current.inFlightPurchaseIds + ingredientId,
                pendingQuantityIds = current.pendingQuantityIds - ingredientId,
                inFlightQuantityIds = current.inFlightQuantityIds - ingredientId,
                optimisticQuantities = current.optimisticQuantities - ingredientId,
                optimisticShelfStates = current.optimisticShelfStates + (ingredientId to true),
                optimisticShoppingStates = current.optimisticShoppingStates + (ingredientId to false),
            )
            if (interaction.compareAndSet(current, next)) {
                break
            }
        }
        cancelQuantityJob(ingredientId)
        viewModelScope.launch {
            var succeeded = false
            try {
                val snapshot = interaction.value
                val itemName = snapshot.roomShoppingNames[ingredientId]
                    ?: snapshot.roomCatalogNames[ingredientId]
                inventoryRepository.markPurchasedToBarShelf(listOf(ingredientId))
                    .onSuccess {
                        succeeded = true
                        launchRecommendationsLoad()
                        emitSnackbar(
                            if (itemName != null) {
                                "Moved $itemName to Bar Shelf"
                            } else {
                                "Moved item to Bar Shelf"
                            },
                        )
                    }
                    .onFailure { error ->
                        emitSnackbar(
                            error.message?.takeIf { msg -> msg.isNotBlank() }
                                ?: "Unable to mark item as purchased.",
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            } finally {
                interaction.update { current ->
                    val remainingPurchase = current.inFlightPurchaseIds - ingredientId
                    val shelfConverged = succeeded && (ingredientId in current.roomShelfIds)
                    val shoppingConverged = succeeded && isShoppingStateConverged(
                        ingredientId = ingredientId,
                        target = false,
                        roomShoppingMap = current.roomShoppingMap,
                        roomCatalogShoppingIds = current.roomCatalogShoppingIds,
                    )
                    current.copy(
                        inFlightPurchaseIds = remainingPurchase,
                        optimisticShelfStates = if (!succeeded || shelfConverged) {
                            current.optimisticShelfStates - ingredientId
                        } else {
                            current.optimisticShelfStates
                        },
                        optimisticShoppingStates = if (!succeeded || shoppingConverged) {
                            current.optimisticShoppingStates - ingredientId
                        } else {
                            current.optimisticShoppingStates
                        },
                    )
                }
            }
        }
    }

    fun markAllPurchased() {
        while (true) {
            val current = interaction.value
            if (current.isBulkUpdating || current.hasInFlightItemMutations) return
            if (interaction.compareAndSet(current, current.copy(isBulkUpdating = true))) {
                break
            }
        }
        viewModelScope.launch {
            try {
                val ids = resolveCurrentShoppingIds()
                if (ids.isEmpty()) return@launch
                inventoryRepository.markPurchasedToBarShelf(ids)
                    .onSuccess {
                        val idSet = ids.toSet()
                        interaction.update { it.copy(optimisticQuantities = it.optimisticQuantities - idSet) }
                        launchRecommendationsLoad()
                        emitSnackbar("Moved all items to Bar Shelf")
                    }
                    .onFailure { error ->
                        emitSnackbar(
                            error.message?.takeIf { msg -> msg.isNotBlank() }
                                ?: "Unable to move items to Bar Shelf.",
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            } finally {
                interaction.update { it.copy(isBulkUpdating = false) }
            }
        }
    }

    fun clearShoppingList() {
        while (true) {
            val current = interaction.value
            if (current.isBulkUpdating || current.hasInFlightItemMutations) return
            if (interaction.compareAndSet(current, current.copy(isBulkUpdating = true))) {
                break
            }
        }
        viewModelScope.launch {
            try {
                val ids = resolveCurrentShoppingIds()
                if (ids.isEmpty()) return@launch
                inventoryRepository.removeFromShoppingList(ids)
                    .onSuccess {
                        val idSet = ids.toSet()
                        interaction.update { it.copy(optimisticQuantities = it.optimisticQuantities - idSet) }
                        emitSnackbar("Cleared shopping list")
                    }
                    .onFailure { error ->
                        emitSnackbar(
                            error.message?.takeIf { msg -> msg.isNotBlank() }
                                ?: "Unable to clear shopping list.",
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            } finally {
                interaction.update { it.copy(isBulkUpdating = false) }
            }
        }
    }

    fun openUnlockedCocktails(ingredientId: Int, ingredientName: String) {
        unlockedCocktailsJob?.cancel()
        val requestId = ++unlockedSheetRequestId
        interaction.update {
            it.copy(
                unlockedSheetDraft = UnlockedSheetDraft(
                    ingredientId = ingredientId,
                    ingredientName = ingredientName,
                    isLoading = true,
                ),
            )
        }
        unlockedCocktailsJob = viewModelScope.launch {
            try {
                inventoryRepository.getUnlockedCocktails(ingredientId)
                    .onSuccess { cocktails ->
                        if (requestId != unlockedSheetRequestId) return@launch
                        val deduplicated = cocktails.distinctBy { it.id }
                        interaction.update { current ->
                            val draft = current.unlockedSheetDraft ?: return@update current
                            if (draft.ingredientId != ingredientId) return@update current
                            current.copy(
                                unlockedSheetDraft = draft.copy(
                                    isLoading = false,
                                    cocktails = deduplicated,
                                    errorMessage = null,
                                ),
                            )
                        }
                    }
                    .onFailure { error ->
                        if (requestId != unlockedSheetRequestId) return@launch
                        interaction.update { current ->
                            val draft = current.unlockedSheetDraft ?: return@update current
                            if (draft.ingredientId != ingredientId) return@update current
                            current.copy(
                                unlockedSheetDraft = draft.copy(
                                    isLoading = false,
                                    errorMessage = error.message?.takeIf { msg -> msg.isNotBlank() }
                                        ?: "Unable to load unlocked cocktails.",
                                ),
                            )
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (requestId != unlockedSheetRequestId) return@launch
                interaction.update { current ->
                    val draft = current.unlockedSheetDraft ?: return@update current
                    if (draft.ingredientId != ingredientId) return@update current
                    current.copy(
                        unlockedSheetDraft = draft.copy(
                            isLoading = false,
                            errorMessage = e.message?.takeIf { msg -> msg.isNotBlank() }
                                ?: "Unable to load unlocked cocktails.",
                        ),
                    )
                }
            }
        }
    }

    fun dismissUnlockedCocktails() {
        unlockedCocktailsJob?.cancel()
        unlockedCocktailsJob = null
        unlockedSheetRequestId += 1
        interaction.update { it.copy(unlockedSheetDraft = null) }
    }

    fun consumeSnackbar(eventId: Long) {
        interaction.update { current ->
            if (current.snackbarEvent?.id == eventId) {
                current.copy(snackbarEvent = null)
            } else {
                current
            }
        }
    }

    private fun emitSnackbar(message: String) {
        interaction.update { current ->
            val nextId = current.lastSnackbarId + 1L
            current.copy(
                lastSnackbarId = nextId,
                snackbarEvent = InventorySnackbarEvent(id = nextId, message = message),
            )
        }
    }

    private fun cancelQuantityJob(ingredientId: Int): Long {
        val nextGeneration = (quantityGenerations[ingredientId] ?: 0L) + 1L
        quantityGenerations[ingredientId] = nextGeneration
        quantityUpdateJobs.remove(ingredientId)?.cancel()
        return nextGeneration
    }

    private suspend fun resolveCurrentShoppingIds(): List<Int> {
        val snapshot = interaction.value
        return if (snapshot.hasReceivedShoppingList) {
            snapshot.roomShoppingMap.keys.toList()
        } else {
            shoppingListFlow.first()
                .map { it.ingredientId }
                .distinct()
        }
    }

    private fun launchRecommendationsLoad(): Job {
        recommendationsJob?.cancel()
        val generation = ++recommendationsGeneration
        interaction.update {
            it.copy(
                isLoadingRecommendations = true,
                recommendationsError = null,
            )
        }
        val job = viewModelScope.launch {
            try {
                inventoryRepository.getRecommendedIngredients()
                    .onSuccess { items ->
                        if (generation != recommendationsGeneration) return@launch
                        val deduplicated = items.distinctBy { it.id }
                        interaction.update {
                            it.copy(
                                recommendations = deduplicated,
                                isLoadingRecommendations = false,
                                recommendationsError = null,
                            )
                        }
                    }
                    .onFailure { error ->
                        if (generation != recommendationsGeneration) return@launch
                        interaction.update {
                            it.copy(
                                isLoadingRecommendations = false,
                                recommendationsError = error.message?.takeIf { msg -> msg.isNotBlank() }
                                    ?: "Unable to load recommendations.",
                            )
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (generation != recommendationsGeneration) return@launch
                interaction.update {
                    it.copy(
                        isLoadingRecommendations = false,
                        recommendationsError = e.message?.takeIf { msg -> msg.isNotBlank() }
                            ?: "Unable to load recommendations.",
                    )
                }
            }
        }
        recommendationsJob = job
        return job
    }

    private fun isShoppingOverrideConverged(
        ingredientId: Int,
        target: Boolean,
        inFlightShoppingIds: Set<Int>,
        inFlightPurchaseIds: Set<Int>,
        roomShoppingMap: Map<Int, Int>,
        roomCatalogShoppingIds: Set<Int>,
    ): Boolean {
        if (ingredientId in inFlightShoppingIds || ingredientId in inFlightPurchaseIds) return false
        return isShoppingStateConverged(ingredientId, target, roomShoppingMap, roomCatalogShoppingIds)
    }

    private fun isShoppingStateConverged(
        ingredientId: Int,
        target: Boolean,
        roomShoppingMap: Map<Int, Int>,
        roomCatalogShoppingIds: Set<Int>,
    ): Boolean = if (target) {
        ingredientId in roomShoppingMap
    } else {
        ingredientId !in roomShoppingMap && ingredientId !in roomCatalogShoppingIds
    }

    private fun applyBooleanOverrides(
        base: Set<Int>,
        overrides: Map<Int, Boolean>,
    ): Set<Int> {
        if (overrides.isEmpty()) return base
        val result = HashSet<Int>(base.size + overrides.size)
        result.addAll(base)
        for ((id, enabled) in overrides) {
            if (enabled) result.add(id) else result.remove(id)
        }
        return result
    }
}
