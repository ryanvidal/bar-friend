package com.ryanvidal.barfriend.feature.cocktails

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ryanvidal.barfriend.core.data.AppPreferences
import com.ryanvidal.barfriend.core.data.CocktailRepository
import com.ryanvidal.barfriend.core.model.CocktailDetail
import com.ryanvidal.barfriend.core.model.VolumeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
)

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
    val actionError: String? = null,
)

class CocktailDetailViewModel(
    private val cocktailId: Int,
    private val cocktailRepository: CocktailRepository,
    private val appPreferences: AppPreferences,
) : ViewModel() {
    private val interaction = MutableStateFlow(DetailInteractionState())

    val uiState: StateFlow<CocktailDetailUiState> = combine(
        cocktailRepository.observeCocktailDetail(cocktailId),
        appPreferences.settings,
        interaction,
    ) { cocktail, settings, local ->
        val effectiveError = local.actionError
            ?: if (cocktail == null) local.refreshError else null
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
                        refreshError = result.exceptionOrNull()?.message,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                interaction.update { current ->
                    current.copy(
                        isRefreshing = false,
                        initialRefreshDone = true,
                        refreshError = e.message,
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
                    interaction.update {
                        it.copy(actionError = error.message ?: "Unable to update favorite status.")
                    }
                }
            } finally {
                interaction.update { it.copy(isTogglingFavorite = false) }
            }
        }
    }

    fun deleteCocktail() {
        while (true) {
            val current = interaction.value
            if (current.isDeleting || current.deleted) return
            if (interaction.compareAndSet(current, current.copy(isDeleting = true, actionError = null))) {
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
                        interaction.update {
                            it.copy(
                                isDeleting = false,
                                actionError = error.message ?: "Unable to delete recipe.",
                            )
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                interaction.update {
                    it.copy(
                        isDeleting = false,
                        actionError = e.message ?: "Unable to delete recipe.",
                    )
                }
            }
        }
    }

    fun consumeDeletedEvent() {
        interaction.update { it.copy(deleted = false) }
    }

    fun clearError() {
        interaction.update { it.copy(actionError = null, refreshError = null) }
    }
}
