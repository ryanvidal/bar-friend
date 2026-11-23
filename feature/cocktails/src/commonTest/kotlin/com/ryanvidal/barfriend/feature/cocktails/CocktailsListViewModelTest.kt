package com.ryanvidal.barfriend.feature.cocktails

import com.ryanvidal.barfriend.core.model.CocktailFilter
import com.ryanvidal.barfriend.core.model.CocktailSort
import com.ryanvidal.barfriend.core.model.CocktailSummary
import com.ryanvidal.barfriend.core.model.CocktailTag
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
    fun `toggles favorite and triggers forced refresh`() = runTest(testDispatcher) {
        val repository = FakeCocktailRepository(initialCocktails = sampleSummaries())
        val viewModel = CocktailsListViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.toggleFavorite(2)
        advanceUntilIdle()
        assertEquals(listOf(2), repository.toggledFavoriteIds)
        assertTrue(viewModel.uiState.value.cocktails.first { it.id == 2 }.isFavorite)

        viewModel.refresh()
        advanceUntilIdle()
        assertEquals(1, repository.syncAllCalls)
        assertFalse(viewModel.uiState.value.syncStatus.isSyncing)
    }
}
