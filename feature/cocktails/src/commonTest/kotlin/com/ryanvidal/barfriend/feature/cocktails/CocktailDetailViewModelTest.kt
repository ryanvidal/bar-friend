package com.ryanvidal.barfriend.feature.cocktails

import com.ryanvidal.barfriend.core.data.AppPreferences
import com.ryanvidal.barfriend.core.model.VolumeUnit
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
class CocktailDetailViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initializes preferred unit from AppPreferences and allows toggling ml oz cl`() = runTest(testDispatcher) {
        val prefs = AppPreferences(InMemoryPreferencesDataStore())
        prefs.setPreferredUnit(VolumeUnit.ML)
        val repository = FakeCocktailRepository(
            initialDetails = mapOf(1 to sampleCocktailDetail(1)),
        )

        val viewModel = CocktailDetailViewModel(
            cocktailId = 1,
            cocktailRepository = repository,
            appPreferences = prefs,
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        assertEquals(VolumeUnit.ML, viewModel.uiState.value.selectedUnit)
        assertEquals("Negroni", viewModel.uiState.value.cocktail?.name)
        assertEquals(listOf(1), repository.refreshedCocktailIds)

        viewModel.setUnit(VolumeUnit.OZ)
        advanceUntilIdle()
        assertEquals(VolumeUnit.OZ, viewModel.uiState.value.selectedUnit)

        viewModel.setUnit(VolumeUnit.CL)
        advanceUntilIdle()
        assertEquals(VolumeUnit.CL, viewModel.uiState.value.selectedUnit)
    }

    @Test
    fun `updates serving scale factor and ignores non positive values`() = runTest(testDispatcher) {
        val prefs = AppPreferences(InMemoryPreferencesDataStore())
        val repository = FakeCocktailRepository(
            initialDetails = mapOf(1 to sampleCocktailDetail(1)),
        )

        val viewModel = CocktailDetailViewModel(1, repository, prefs)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        assertEquals(1.0, viewModel.uiState.value.scaleFactor)

        viewModel.setScaleFactor(2.0)
        advanceUntilIdle()
        assertEquals(2.0, viewModel.uiState.value.scaleFactor)

        viewModel.setScaleFactor(0.5)
        advanceUntilIdle()
        assertEquals(0.5, viewModel.uiState.value.scaleFactor)

        viewModel.setScaleFactor(0.0)
        advanceUntilIdle()
        assertEquals(0.5, viewModel.uiState.value.scaleFactor)
    }

    @Test
    fun `toggles Make Mode and step checkmarks`() = runTest(testDispatcher) {
        val prefs = AppPreferences(InMemoryPreferencesDataStore())
        val repository = FakeCocktailRepository(
            initialDetails = mapOf(1 to sampleCocktailDetail(1)),
        )

        val viewModel = CocktailDetailViewModel(1, repository, prefs)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isMakeMode)
        assertTrue(viewModel.uiState.value.checkedSteps.isEmpty())

        viewModel.toggleMakeMode()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isMakeMode)

        viewModel.toggleStepChecked(0)
        viewModel.toggleStepChecked(2)
        advanceUntilIdle()
        assertEquals(setOf(0, 2), viewModel.uiState.value.checkedSteps)

        viewModel.toggleStepChecked(0)
        advanceUntilIdle()
        assertEquals(setOf(2), viewModel.uiState.value.checkedSteps)
    }

    @Test
    fun `toggles favorite and deletes cocktail cleanly`() = runTest(testDispatcher) {
        val prefs = AppPreferences(InMemoryPreferencesDataStore())
        val repository = FakeCocktailRepository(
            initialDetails = mapOf(1 to sampleCocktailDetail(1)),
        )

        val viewModel = CocktailDetailViewModel(1, repository, prefs)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.cocktail?.isFavorite == true)

        viewModel.toggleFavorite()
        advanceUntilIdle()
        assertEquals(listOf(1), repository.toggledFavoriteIds)
        assertTrue(viewModel.uiState.value.cocktail?.isFavorite == true)

        viewModel.deleteCocktail()
        advanceUntilIdle()
        assertEquals(listOf(1), repository.deletedCocktailIds)
        assertTrue(viewModel.uiState.value.deleted)
    }

    @Test
    fun `offline refresh failure is non fatal when cached detail exists`() = runTest(testDispatcher) {
        val prefs = AppPreferences(InMemoryPreferencesDataStore())
        val repository = FakeCocktailRepository(
            initialDetails = mapOf(1 to sampleCocktailDetail(1)),
        ).apply {
            refreshResultOverride = { Result.failure(IllegalStateException("Offline")) }
        }

        val viewModel = CocktailDetailViewModel(1, repository, prefs)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        assertEquals("Negroni", viewModel.uiState.value.cocktail?.name)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.errorMessage)
    }
}
