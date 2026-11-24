package com.ryanvidal.barfriend.feature.cocktails

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ryanvidal.barfriend.core.data.AppPreferences
import com.ryanvidal.barfriend.core.data.CocktailRepository
import com.ryanvidal.barfriend.core.data.InventoryRepository
import com.ryanvidal.barfriend.core.model.CocktailDetail
import com.ryanvidal.barfriend.core.model.VolumeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DetailSnackbarEvent(
    val id: Long,
    val message: String,
)

data class CocktailDetailUiState(
    val cocktail: CocktailDetail? = null,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isTogglingFavorite: Boolean = false,
    val selectedUnit: VolumeUnit = VolumeUnit.OZ,
    val scaleFactor: Double = 1.0,
    val isMakeMode: Boolean = false,
    val checkedSteps: Set<Int> = emptySet(),
    val isDeleting: Boolean = false,
    val deleted: Boolean = false,
    val errorMessage: String? = null,
    val shoppingListIngredientIds: Set<Int> = emptySet(),
    val shoppingListInFlightIds: Set<Int> = emptySet(),
    val isAddingMissingToShoppingList: Boolean = false,
    val snackbarEvent: DetailSnackbarEvent? = null,
) {
    val snackbarMessage: String?
        get() = snackbarEvent?.message
}

private data class DetailInteractionState(
    val unitOverride: VolumeUnit? = null,
    val scaleFactor: Double = 1.0,
    val isMakeMode: Boolean = false,
    val checkedSteps: Set<Int> = emptySet(),
    val isRefreshing: Boolean = false,
    val isTogglingFavorite: Boolean = false,
    val isDeleting: Boolean = false,
    val deleted: Boolean = false,
    val initialRefreshDone: Boolean = false,
    val refreshError: String? = null,
    val shoppingListInFlightIds: Set<Int> = emptySet(),
    val isAddingMissingToShoppingList: Boolean = false,
    val snackbarEvent: DetailSnackbarEvent? = null,
)

class CocktailDetailViewModel(
    private val cocktailId: Int,
    private val cocktailRepository: CocktailRepository,
    private val inventoryRepository: InventoryRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val interaction = MutableStateFlow(DetailInteractionState())
    private var nextSnackbarId = 1L

    val uiState: StateFlow<CocktailDetailUiState> = combine(
        cocktailRepository.observeCocktailDetail(cocktailId),
        appPreferences.settings,
        inventoryRepository.observeShoppingListIds(),
        interaction,
    ) { cocktail, settings, shoppingListIds, local ->
        val effectiveError = if (cocktail == null) local.refreshError else null
        val boundedCheckedSteps = if (cocktail != null) {
            val validIndices = parseInstructionSteps(cocktail.instructions).indices
            local.checkedSteps.filterTo(HashSet()) { it in validIndices }
        } else {
            emptySet()
        }
        CocktailDetailUiState(
            cocktail = cocktail,
            isLoading = cocktail == null && !local.initialRefreshDone,
            isRefreshing = local.isRefreshing,
            isTogglingFavorite = local.isTogglingFavorite,
            selectedUnit = local.unitOverride ?: settings.preferredUnit,
            scaleFactor = local.scaleFactor,
            isMakeMode = local.isMakeMode,
            checkedSteps = boundedCheckedSteps,
            isDeleting = local.isDeleting,
            deleted = local.deleted,
            errorMessage = effectiveError,
            shoppingListIngredientIds = shoppingListIds,
            shoppingListInFlightIds = local.shoppingListInFlightIds,
            isAddingMissingToShoppingList = local.isAddingMissingToShoppingList,
            snackbarEvent = local.snackbarEvent,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CocktailDetailUiState(),
    )

    init {
        refresh()
    }

    fun refresh() {
        while (true) {
            val current = interaction.value
            if (current.isRefreshing) return
            if (interaction.compareAndSet(current, current.copy(isRefreshing = true, refreshError = null))) {
                break
            }
        }
        viewModelScope.launch {
            try {
                val result = cocktailRepository.refreshCocktail(cocktailId)
                interaction.update { current ->
                    current.copy(
                        isRefreshing = false,
                        initialRefreshDone = true,
                        refreshError = result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                interaction.update { current ->
                    current.copy(
                        isRefreshing = false,
                        initialRefreshDone = true,
                        refreshError = e.message?.takeIf { it.isNotBlank() },
                    )
                }
            }
        }
    }

    fun setUnit(unit: VolumeUnit) {
        interaction.update { it.copy(unitOverride = unit) }
    }

    fun setScaleFactor(scale: Double) {
        if (!scale.isFinite() || scale <= 0.0) return
        interaction.update { it.copy(scaleFactor = scale) }
    }

    fun toggleMakeMode() {
        interaction.update { it.copy(isMakeMode = !it.isMakeMode) }
    }

    fun toggleStepChecked(stepIndex: Int) {
        interaction.update { current ->
            val updated = if (stepIndex in current.checkedSteps) {
                current.checkedSteps - stepIndex
            } else {
                current.checkedSteps + stepIndex
            }
            current.copy(checkedSteps = updated)
        }
    }

    fun toggleFavorite() {
        while (true) {
            val current = interaction.value
            if (current.isTogglingFavorite || current.isDeleting || current.deleted) return
            if (interaction.compareAndSet(current, current.copy(isTogglingFavorite = true))) {
                break
            }
        }
        viewModelScope.launch {
            try {
                cocktailRepository.toggleFavorite(cocktailId).onFailure { error ->
                    emitSnackbar(
                        error.message?.takeIf { it.isNotBlank() } ?: "Unable to update favorite status.",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emitSnackbar(
                    e.message?.takeIf { it.isNotBlank() } ?: "Unable to update favorite status.",
                )
            } finally {
                interaction.update { it.copy(isTogglingFavorite = false) }
            }
        }
    }

    fun deleteCocktail() {
        while (true) {
            val current = interaction.value
            if (current.isDeleting || current.deleted) return
            if (interaction.compareAndSet(current, current.copy(isDeleting = true))) {
                break
            }
        }
        viewModelScope.launch {
            try {
                cocktailRepository.deleteCocktail(cocktailId)
                    .onSuccess {
                        interaction.update { it.copy(isDeleting = false, deleted = true) }
                    }
                    .onFailure { error ->
                        val message = error.message?.takeIf { it.isNotBlank() } ?: "Unable to delete recipe."
                        interaction.update { it.copy(isDeleting = false).withSnackbar(message) }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message?.takeIf { it.isNotBlank() } ?: "Unable to delete recipe."
                interaction.update { it.copy(isDeleting = false).withSnackbar(message) }
            }
        }
    }

    fun toggleIngredientInShoppingList(ingredientId: Int, ingredientName: String) {
        while (true) {
            val current = interaction.value
            if (
                current.isDeleting ||
                current.deleted ||
                current.isAddingMissingToShoppingList ||
                ingredientId in current.shoppingListInFlightIds
            ) {
                return
            }
            if (
                interaction.compareAndSet(
                    current,
                    current.copy(shoppingListInFlightIds = current.shoppingListInFlightIds + ingredientId),
                )
            ) {
                break
            }
        }
        viewModelScope.launch {
            var isCurrentlyInList = false
            try {
                val currentShoppingIds = inventoryRepository.observeShoppingListIds().first()
                isCurrentlyInList = ingredientId in currentShoppingIds
                val result = if (isCurrentlyInList) {
                    inventoryRepository.removeFromShoppingList(listOf(ingredientId))
                } else {
                    inventoryRepository.addToShoppingList(listOf(ingredientId))
                }
                result
                    .onSuccess {
                        val message = if (isCurrentlyInList) {
                            "Removed $ingredientName from shopping list"
                        } else {
                            "Added $ingredientName to shopping list"
                        }
                        emitSnackbar(message)
                    }
                    .onFailure { error ->
                        val fallback = if (isCurrentlyInList) {
                            "Unable to remove $ingredientName from shopping list."
                        } else {
                            "Unable to add $ingredientName to shopping list."
                        }
                        emitSnackbar(error.message?.takeIf { it.isNotBlank() } ?: fallback)
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val fallback = if (isCurrentlyInList) {
                    "Unable to remove $ingredientName from shopping list."
                } else {
                    "Unable to add $ingredientName to shopping list."
                }
                emitSnackbar(e.message?.takeIf { it.isNotBlank() } ?: fallback)
            } finally {
                interaction.update {
                    it.copy(shoppingListInFlightIds = it.shoppingListInFlightIds - ingredientId)
                }
            }
        }
    }

    fun addMissingIngredientsToShoppingList() {
        while (true) {
            val current = interaction.value
            if (
                current.isDeleting ||
                current.deleted ||
                current.isAddingMissingToShoppingList ||
                current.shoppingListInFlightIds.isNotEmpty()
            ) {
                return
            }
            if (
                interaction.compareAndSet(
                    current,
                    current.copy(isAddingMissingToShoppingList = true),
                )
            ) {
                break
            }
        }
        viewModelScope.launch {
            try {
                val cocktail = cocktailRepository.observeCocktailDetail(cocktailId).first() ?: return@launch
                val missingRequiredIds = cocktail.ingredients
                    .asSequence()
                    .filter { !it.optional && !it.isAvailableInBar }
                    .map { it.ingredientId }
                    .distinct()
                    .toList()
                if (missingRequiredIds.isEmpty()) return@launch

                val alreadyListed = inventoryRepository.observeShoppingListIds().first()
                val missingIds = missingRequiredIds.filterNot { it in alreadyListed }
                if (missingIds.isEmpty()) {
                    emitSnackbar("All missing ingredients are already on your shopping list")
                    return@launch
                }

                inventoryRepository.addToShoppingList(missingIds)
                    .onSuccess {
                        val message = if (missingIds.size == 1) {
                            "Added 1 ingredient to shopping list"
                        } else {
                            "Added ${missingIds.size} ingredients to shopping list"
                        }
                        emitSnackbar(message)
                    }
                    .onFailure { error ->
                        emitSnackbar(
                            error.message?.takeIf { it.isNotBlank() }
                                ?: "Unable to add missing ingredients to shopping list.",
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emitSnackbar(
                    e.message?.takeIf { it.isNotBlank() }
                        ?: "Unable to add missing ingredients to shopping list.",
                )
            } finally {
                interaction.update { it.copy(isAddingMissingToShoppingList = false) }
            }
        }
    }

    fun consumeDeletedEvent() {
        interaction.update { it.copy(deleted = false) }
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

    fun clearError() {
        interaction.update { it.copy(refreshError = null) }
    }

    private fun DetailInteractionState.withSnackbar(message: String): DetailInteractionState {
        val nextId = maxOf(nextSnackbarId, (snackbarEvent?.id ?: 0L) + 1L)
        nextSnackbarId = nextId + 1L
        return copy(snackbarEvent = DetailSnackbarEvent(id = nextId, message = message))
    }

    private fun emitSnackbar(message: String) {
        interaction.update { it.withSnackbar(message) }
    }
}
