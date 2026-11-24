package com.ryanvidal.barfriend.feature.cocktails

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ryanvidal.barfriend.core.data.BarSyncCoordinator
import com.ryanvidal.barfriend.core.data.CocktailRepository
import com.ryanvidal.barfriend.core.data.SyncStatus
import com.ryanvidal.barfriend.core.model.BarCatalog
import com.ryanvidal.barfriend.core.model.CocktailFilter
import com.ryanvidal.barfriend.core.model.CocktailSort
import com.ryanvidal.barfriend.core.model.CocktailSummary
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CocktailsListUiState(
    val filter: CocktailFilter = CocktailFilter(),
    val cocktails: List<CocktailSummary> = emptyList(),
    val catalog: BarCatalog = BarCatalog(),
    val syncStatus: SyncStatus = SyncStatus(),
    val hasLoaded: Boolean = false,
    val favoriteInFlightIds: Set<Int> = emptySet(),
    val actionErrorMessage: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@Inject
class CocktailsListViewModel(
    private val cocktailRepository: CocktailRepository,
    private val syncCoordinator: BarSyncCoordinator,
) : ViewModel() {
    private val filter = MutableStateFlow(CocktailFilter())
    private val favoriteInFlightIds = MutableStateFlow<Set<Int>>(emptySet())
    private val actionErrorMessage = MutableStateFlow<String?>(null)

    private val cocktailsFlow = filter.flatMapLatest { activeFilter ->
        cocktailRepository.observeCocktails(activeFilter)
    }

    init {
        viewModelScope.launch {
            cocktailRepository.observeCatalog().collect(::pruneStaleCatalogFilters)
        }
    }

    val uiState: StateFlow<CocktailsListUiState> = combine(
        combine(
            filter,
            cocktailsFlow,
            cocktailRepository.observeCatalog(),
        ) { currentFilter, cocktails, catalog ->
            Triple(currentFilter, cocktails, catalog)
        },
        syncCoordinator.syncStatus,
        favoriteInFlightIds,
        actionErrorMessage,
    ) { (currentFilter, cocktails, catalog), syncStatus, inFlightFavorites, error ->
        CocktailsListUiState(
            filter = currentFilter,
            cocktails = cocktails,
            catalog = catalog,
            syncStatus = syncStatus,
            hasLoaded = true,
            favoriteInFlightIds = inFlightFavorites,
            actionErrorMessage = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CocktailsListUiState(),
    )

    fun onQueryChanged(query: String) {
        filter.update { it.copy(query = query) }
    }

    fun toggleCanMakeOnly() {
        filter.update { it.copy(canMakeOnly = !it.canMakeOnly) }
    }

    fun toggleFavoritesOnly() {
        filter.update { it.copy(favoritesOnly = !it.favoritesOnly) }
    }

    fun updateFilter(updated: CocktailFilter) {
        filter.value = updated
    }

    fun clearAdvancedFilters() {
        filter.update { current ->
            current.copy(
                tagIds = emptySet(),
                glassId = null,
                methodId = null,
                abvMin = null,
                abvMax = null,
                sort = CocktailSort.NAME_ASC,
            )
        }
    }

    fun clearAllFilters() {
        filter.value = CocktailFilter()
    }

    fun toggleFavorite(cocktailId: Int) {
        while (true) {
            val current = favoriteInFlightIds.value
            if (cocktailId in current) return
            if (favoriteInFlightIds.compareAndSet(current, current + cocktailId)) {
                break
            }
        }
        viewModelScope.launch {
            try {
                cocktailRepository.toggleFavorite(cocktailId).onFailure { error ->
                    actionErrorMessage.value = error.message ?: "Unable to update favorite status."
                }
            } finally {
                favoriteInFlightIds.update { it - cocktailId }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            syncCoordinator.syncAll(force = true)
        }
    }

    fun dismissActionError() {
        actionErrorMessage.value = null
    }

    private fun pruneStaleCatalogFilters(catalog: BarCatalog) {
        val validTagIds = catalog.tags.mapTo(HashSet(catalog.tags.size)) { it.id }
        val validGlassIds = catalog.glasses.mapTo(HashSet(catalog.glasses.size)) { it.id }
        val validMethodIds = catalog.methods.mapTo(HashSet(catalog.methods.size)) { it.id }
        filter.update { current ->
            val prunedTags = current.tagIds.filterTo(mutableSetOf()) { it in validTagIds }
            val prunedGlass = current.glassId?.takeIf { it in validGlassIds }
            val prunedMethod = current.methodId?.takeIf { it in validMethodIds }
            if (prunedTags == current.tagIds &&
                prunedGlass == current.glassId &&
                prunedMethod == current.methodId
            ) {
                current
            } else {
                current.copy(
                    tagIds = prunedTags,
                    glassId = prunedGlass,
                    methodId = prunedMethod,
                )
            }
        }
    }
}
