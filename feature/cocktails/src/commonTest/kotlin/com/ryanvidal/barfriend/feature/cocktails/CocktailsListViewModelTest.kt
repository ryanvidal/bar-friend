package com.ryanvidal.barfriend.feature.cocktails

import com.ryanvidal.barfriend.core.model.BarCatalog
import com.ryanvidal.barfriend.core.model.CocktailFilter
import com.ryanvidal.barfriend.core.model.CocktailMethod
import com.ryanvidal.barfriend.core.model.CocktailSort
import com.ryanvidal.barfriend.core.model.CocktailSummary
import com.ryanvidal.barfriend.core.model.CocktailTag
import com.ryanvidal.barfriend.core.model.Glass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CocktailsListViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sampleSummaries(): List<CocktailSummary> = listOf(
        CocktailSummary(
            id = 1,
            slug = "negroni",
            name = "Negroni",
            shortDescription = null,
            abv = 24.0,
            glassName = "Rocks",
            methodName = "Stir",
            mainImageThumbUrl = null,
            isFavorite = true,
            inBarShelf = false,
            missingIngredientsCount = 1,
            missingIngredientNames = listOf("Sweet Vermouth"),
            tags = listOf(CocktailTag(30, "Classic")),
        ),
        CocktailSummary(
            id = 2,
            slug = "gimlet",
            name = "Gimlet",
            shortDescription = null,
            abv = 21.0,
            glassName = "Coupe",
            methodName = "Shake",
            mainImageThumbUrl = null,
            isFavorite = false,
            inBarShelf = true,
            missingIngredientsCount = 0,
            missingIngredientNames = emptyList(),
            tags = listOf(CocktailTag(30, "Classic"), CocktailTag(32, "Citrus")),
        ),
    )

    @Test
    fun `filters by query, quick toggles, and advanced filters`() = runTest(testDispatcher) {
        val repository = FakeCocktailRepository(initialCocktails = sampleSummaries())
        val viewModel = CocktailsListViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        assertFalse(CocktailFilter(sort = CocktailSort.ABV_DESC).hasActiveFilters)
        assertTrue(CocktailFilter(query = "gin").hasActiveFilters)

        assertEquals(listOf("Gimlet", "Negroni"), viewModel.uiState.value.cocktails.map { it.name })

        viewModel.onQueryChanged("Negroni")
        advanceUntilIdle()
        assertEquals(listOf("Negroni"), viewModel.uiState.value.cocktails.map { it.name })

        viewModel.onQueryChanged("")
        viewModel.toggleCanMakeOnly()
        advanceUntilIdle()
        assertEquals(listOf("Gimlet"), viewModel.uiState.value.cocktails.map { it.name })

        viewModel.toggleCanMakeOnly()
        viewModel.toggleFavoritesOnly()
        advanceUntilIdle()
        assertEquals(listOf("Negroni"), viewModel.uiState.value.cocktails.map { it.name })

        viewModel.toggleFavoritesOnly()
        viewModel.updateFilter(
            CocktailFilter(
                sort = CocktailSort.ABV_DESC,
                glassId = 11,
            ),
        )
        advanceUntilIdle()
        assertEquals(listOf("Gimlet"), viewModel.uiState.value.cocktails.map { it.name })
        assertEquals(2, viewModel.uiState.value.filter.activeAdvancedFilterCount)

        viewModel.clearAdvancedFilters()
        advanceUntilIdle()
        assertEquals(0, viewModel.uiState.value.filter.activeAdvancedFilterCount)
        assertEquals(2, viewModel.uiState.value.cocktails.size)
    }

    @Test
    fun `guards per-cocktail favorite double taps while allowing concurrent taps on other cocktails`() = runTest(testDispatcher) {
        val gates = mapOf(
            1 to CompletableDeferred<Unit>(),
            2 to CompletableDeferred<Unit>(),
        )
        val repository = FakeCocktailRepository(initialCocktails = sampleSummaries()).apply {
            toggleFavoriteSuspendHook = { id -> gates.getValue(id).await() }
        }
        val viewModel = CocktailsListViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.toggleFavorite(2)
        viewModel.toggleFavorite(2)
        viewModel.toggleFavorite(1)
        testDispatcher.scheduler.runCurrent()

        assertEquals(listOf(2, 1), repository.toggledFavoriteIds)
        assertEquals(setOf(2, 1), viewModel.uiState.value.favoriteInFlightIds)

        gates.getValue(2).complete(Unit)
        gates.getValue(1).complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.favoriteInFlightIds.isEmpty())
        assertTrue(viewModel.uiState.value.cocktails.first { it.id == 2 }.isFavorite)
        assertFalse(viewModel.uiState.value.cocktails.first { it.id == 1 }.isFavorite)

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(1, repository.syncAllCalls)
        assertFalse(viewModel.uiState.value.syncStatus.isSyncing)
    }

    @Test
    fun `drops stale tag, glass, and method filters when bar catalog updates`() = runTest(testDispatcher) {
        val repository = FakeCocktailRepository(initialCocktails = sampleSummaries())
        val viewModel = CocktailsListViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.updateFilter(
            CocktailFilter(
                tagIds = setOf(30, 32),
                glassId = 11,
                methodId = 21,
            ),
        )
        advanceUntilIdle()

        // Switch bar catalog: keep tag 30 and method 21, remove tag 32 and glass 11
        repository.setCatalog(
            BarCatalog(
                glasses = listOf(Glass(10, "Rocks", 250.0, "ml")),
                methods = listOf(CocktailMethod(21, "Shake", 25)),
                tags = listOf(CocktailTag(30, "Classic")),
            ),
        )
        advanceUntilIdle()

        val prunedFilter = viewModel.uiState.value.filter
        assertEquals(setOf(30), prunedFilter.tagIds)
        assertNull(prunedFilter.glassId)
        assertEquals(21, prunedFilter.methodId)
    }
}
