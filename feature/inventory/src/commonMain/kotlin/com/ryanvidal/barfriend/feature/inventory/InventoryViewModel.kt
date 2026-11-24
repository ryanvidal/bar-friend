package com.ryanvidal.barfriend.feature.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ryanvidal.barfriend.core.data.CocktailRepository
import com.ryanvidal.barfriend.core.data.InventoryRepository
import com.ryanvidal.barfriend.core.data.SyncStatus
import com.ryanvidal.barfriend.core.model.IngredientSummary
import com.ryanvidal.barfriend.core.model.RecommendedIngredient
import com.ryanvidal.barfriend.core.model.ShoppingListItem
import com.ryanvidal.barfriend.core.model.UnlockedCocktail
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class InventoryTab(val label: String) {
    BAR_SHELF("Bar Shelf"),
    SHOPPING_LIST("Shopping List"),
}

enum class ShelfStockFilter(val label: String) {
    ALL("All"),
    IN_STOCK("In Stock"),
    OUT_OF_STOCK("Out of Stock"),
}

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
    val isBulkUpdating: Boolean = false,
    val isRefreshing: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus(),
    val snackbarMessage: String? = null,
)

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
    val optimisticShelfStates: Map<Int, Boolean> = emptyMap(),
    val optimisticShoppingStates: Map<Int, Boolean> = emptyMap(),
    val isBulkUpdating: Boolean = false,
    val isRefreshing: Boolean = false,
    val snackbarMessage: String? = null,
)

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
    private val cocktailRepository: CocktailRepository,
) : ViewModel() {
    private val interaction = MutableStateFlow(InventoryInteractionState())

    private var recommendationsJob: Job? = null
    private var recommendationsGeneration: Long = 0L
    private var unlockedCocktailsJob: Job? = null
    private var unlockedSheetRequestId: Long = 0L

    private val barShelfIdsFlow: Flow<Set<Int>> = inventoryRepository
        .observeBarShelfIngredients()
        .map { items -> items.filter { it.inBarShelf }.mapTo(HashSet(items.size)) { it.id } }
        .distinctUntilChanged()

    private val dataFlow: Flow<InventoryDataSnapshot> = combine(
        cocktailRepository.observeCatalog(),
        barShelfIdsFlow,
        inventoryRepository.observeShoppingList(),
        inventoryRepository.observeShoppingListIds(),
        cocktailRepository.syncStatus,
    ) { catalog, barShelfIds, shoppingList, shoppingListIds, syncStatus ->
        InventoryDataSnapshot(
            catalogIngredients = catalog.ingredients,
            barShelfIds = barShelfIds,
            shoppingList = shoppingList,
            shoppingListIds = shoppingListIds,
            syncStatus = syncStatus,
        )
    }

    init {
        viewModelScope.launch {
            cocktailRepository.observeCatalog().collect { catalog ->
                pruneStaleCategory(catalog.ingredients)
            }
        }
        viewModelScope.launch {
            barShelfIdsFlow.collect {
                launchRecommendationsLoad()
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

        val syncedRecommendations = local.recommendations
            .filter { rec -> rec.id !in effectiveShelfIds }
            .map { rec ->
                val inShopping = rec.id in effectiveShoppingIds
                if (rec.inShoppingList == inShopping) rec else rec.copy(inShoppingList = inShopping)
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
            shoppingList = data.shoppingList,
            recommendations = syncedRecommendations,
            isLoadingRecommendations = local.isLoadingRecommendations,
            recommendationsError = local.recommendationsError,
            unlockedSheet = unlockedSheet,
            inFlightShelfIds = local.inFlightShelfIds,
            inFlightShoppingIds = local.inFlightShoppingIds,
            inFlightPurchaseIds = local.inFlightPurchaseIds,
            isBulkUpdating = local.isBulkUpdating,
            isRefreshing = local.isRefreshing || data.syncStatus.isSyncing,
            syncStatus = data.syncStatus,
            snackbarMessage = local.snackbarMessage,
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
                val (syncResult, shoppingResult) = coroutineScope {
                    val syncDeferred = async { cocktailRepository.syncAll(force = true) }
                    val shoppingDeferred = async { inventoryRepository.syncShoppingList() }
                    syncDeferred.await() to shoppingDeferred.await()
                }
                val syncError = syncResult.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                val shoppingError = shoppingResult.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                when {
                    shoppingResult.isFailure -> interaction.update {
                        it.copy(snackbarMessage = shoppingError ?: "Unable to sync shopping list.")
                    }
                    syncResult.isFailure && cocktailRepository.syncStatus.value.errorMessage == null -> interaction.update {
                        it.copy(snackbarMessage = syncError ?: "Sync failed.")
                    }
                }
                launchRecommendationsLoad().join()
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
            try {
                inventoryRepository.setInBarShelf(listOf(ingredientId), inStock)
                    .onFailure { error ->
                        interaction.update {
                            it.copy(
                                snackbarMessage = error.message?.takeIf { msg -> msg.isNotBlank() }
                                    ?: "Unable to update bar shelf.",
                            )
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } finally {
                interaction.update { current ->
                    current.copy(
                        inFlightShelfIds = current.inFlightShelfIds - ingredientId,
                        optimisticShelfStates = current.optimisticShelfStates - ingredientId,
                    )
                }
            }
        }
    }

    fun toggleShoppingList(ingredientId: Int) {
        while (true) {
            val current = interaction.value
            if (current.isBulkUpdating ||
                ingredientId in current.inFlightShoppingIds ||
                ingredientId in current.inFlightPurchaseIds
            ) {
                return
            }
            val next = current.copy(inFlightShoppingIds = current.inFlightShoppingIds + ingredientId)
            if (interaction.compareAndSet(current, next)) {
                break
            }
        }
        viewModelScope.launch {
            try {
                val currentlyInList = ingredientId in inventoryRepository.observeShoppingListIds().first()
                interaction.update {
                    it.copy(optimisticShoppingStates = it.optimisticShoppingStates + (ingredientId to !currentlyInList))
                }
                val result = if (currentlyInList) {
                    inventoryRepository.removeFromShoppingList(listOf(ingredientId))
                } else {
                    inventoryRepository.addToShoppingList(listOf(ingredientId), quantity = 1)
                }
                result.onFailure { error ->
                    interaction.update {
                        it.copy(
                            snackbarMessage = error.message?.takeIf { msg -> msg.isNotBlank() }
                                ?: "Unable to update shopping list.",
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                interaction.update { current ->
                    current.copy(
                        inFlightShoppingIds = current.inFlightShoppingIds - ingredientId,
                        optimisticShoppingStates = current.optimisticShoppingStates - ingredientId,
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
                optimisticShoppingStates = current.optimisticShoppingStates + (ingredientId to true),
            )
            if (interaction.compareAndSet(current, next)) {
                break
            }
        }
        viewModelScope.launch {
            try {
                inventoryRepository.addToShoppingList(listOf(ingredientId), quantity = 1)
                    .onFailure { error ->
                        interaction.update {
                            it.copy(
                                snackbarMessage = error.message?.takeIf { msg -> msg.isNotBlank() }
                                    ?: "Unable to add to shopping list.",
                            )
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } finally {
                interaction.update { current ->
                    current.copy(
                        inFlightShoppingIds = current.inFlightShoppingIds - ingredientId,
                        optimisticShoppingStates = current.optimisticShoppingStates - ingredientId,
                    )
                }
            }
        }
    }

    fun updateQuantity(ingredientId: Int, quantity: Int) {
        while (true) {
            val current = interaction.value
            if (current.isBulkUpdating ||
                ingredientId in current.inFlightShoppingIds ||
                ingredientId in current.inFlightPurchaseIds
            ) {
                return
            }
            val next = current.copy(inFlightShoppingIds = current.inFlightShoppingIds + ingredientId)
            if (interaction.compareAndSet(current, next)) {
                break
            }
        }
        viewModelScope.launch {
            try {
                val result = if (quantity <= 0) {
                    inventoryRepository.removeFromShoppingList(listOf(ingredientId))
                } else {
                    inventoryRepository.updateShoppingListQuantity(ingredientId, quantity)
                }
                result.onFailure { error ->
                    interaction.update {
                        it.copy(
                            snackbarMessage = error.message?.takeIf { msg -> msg.isNotBlank() }
                                ?: "Unable to update quantity.",
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                interaction.update {
                    it.copy(inFlightShoppingIds = it.inFlightShoppingIds - ingredientId)
                }
            }
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
                optimisticShoppingStates = current.optimisticShoppingStates + (ingredientId to false),
            )
            if (interaction.compareAndSet(current, next)) {
                break
            }
        }
        viewModelScope.launch {
            try {
                inventoryRepository.removeFromShoppingList(listOf(ingredientId))
                    .onFailure { error ->
                        interaction.update {
                            it.copy(
                                snackbarMessage = error.message?.takeIf { msg -> msg.isNotBlank() }
                                    ?: "Unable to remove item.",
                            )
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } finally {
                interaction.update { current ->
                    current.copy(
                        inFlightShoppingIds = current.inFlightShoppingIds - ingredientId,
                        optimisticShoppingStates = current.optimisticShoppingStates - ingredientId,
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
                optimisticShelfStates = current.optimisticShelfStates + (ingredientId to true),
                optimisticShoppingStates = current.optimisticShoppingStates + (ingredientId to false),
            )
            if (interaction.compareAndSet(current, next)) {
                break
            }
        }
        viewModelScope.launch {
            try {
                val itemName = inventoryRepository.observeShoppingList().first()
                    .firstOrNull { it.ingredientId == ingredientId }
                    ?.ingredientName
                    ?: cocktailRepository.observeCatalog().first()
                        .ingredients
                        .firstOrNull { it.id == ingredientId }
                        ?.name
                inventoryRepository.markPurchasedToBarShelf(listOf(ingredientId))
                    .onSuccess {
                        interaction.update {
                            it.copy(
                                snackbarMessage = if (itemName != null) {
                                    "Moved $itemName to Bar Shelf"
                                } else {
                                    "Moved item to Bar Shelf"
                                },
                            )
                        }
                    }
                    .onFailure { error ->
                        interaction.update {
                            it.copy(
                                snackbarMessage = error.message?.takeIf { msg -> msg.isNotBlank() }
                                    ?: "Unable to mark item as purchased.",
                            )
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } finally {
                interaction.update { current ->
                    current.copy(
                        inFlightPurchaseIds = current.inFlightPurchaseIds - ingredientId,
                        optimisticShelfStates = current.optimisticShelfStates - ingredientId,
                        optimisticShoppingStates = current.optimisticShoppingStates - ingredientId,
                    )
                }
            }
        }
    }

    fun markAllPurchased() {
        while (true) {
            val current = interaction.value
            if (current.isBulkUpdating) return
            if (interaction.compareAndSet(current, current.copy(isBulkUpdating = true))) {
                break
            }
        }
        viewModelScope.launch {
            try {
                val ids = inventoryRepository.observeShoppingList().first()
                    .map { it.ingredientId }
                    .distinct()
                if (ids.isEmpty()) return@launch
                inventoryRepository.markPurchasedToBarShelf(ids)
                    .onSuccess {
                        interaction.update {
                            it.copy(snackbarMessage = "Moved all items to Bar Shelf")
                        }
                    }
                    .onFailure { error ->
                        interaction.update {
                            it.copy(
                                snackbarMessage = error.message?.takeIf { msg -> msg.isNotBlank() }
                                    ?: "Unable to move items to Bar Shelf.",
                            )
                        }
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
            if (current.isBulkUpdating) return
            if (interaction.compareAndSet(current, current.copy(isBulkUpdating = true))) {
                break
            }
        }
        viewModelScope.launch {
            try {
                val ids = inventoryRepository.observeShoppingList().first()
                    .map { it.ingredientId }
                    .distinct()
                if (ids.isEmpty()) return@launch
                inventoryRepository.removeFromShoppingList(ids)
                    .onSuccess {
                        interaction.update {
                            it.copy(snackbarMessage = "Cleared shopping list")
                        }
                    }
                    .onFailure { error ->
                        interaction.update {
                            it.copy(
                                snackbarMessage = error.message?.takeIf { msg -> msg.isNotBlank() }
                                    ?: "Unable to clear shopping list.",
                            )
                        }
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
                        interaction.update { current ->
                            val draft = current.unlockedSheetDraft ?: return@update current
                            if (draft.ingredientId != ingredientId) return@update current
                            current.copy(
                                unlockedSheetDraft = draft.copy(
                                    isLoading = false,
                                    cocktails = cocktails,
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

    fun consumeSnackbar() {
        interaction.update { it.copy(snackbarMessage = null) }
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
                        interaction.update {
                            it.copy(
                                recommendations = items,
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

    private fun pruneStaleCategory(ingredients: List<IngredientSummary>) {
        val validCategories = ingredients.mapNotNullTo(HashSet()) {
            it.categoryName?.trim()?.takeIf { name -> name.isNotEmpty() }
        }
        interaction.update { current ->
            val selected = current.selectedCategory
            if (selected != null && selected !in validCategories) {
                current.copy(selectedCategory = null)
            } else {
                current
            }
        }
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
