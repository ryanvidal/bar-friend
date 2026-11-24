package com.ryanvidal.barfriend.feature.cocktails

import com.ryanvidal.barfriend.core.data.AppPreferences
import com.ryanvidal.barfriend.core.model.VolumeUnit
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
    fun `updates serving scale factor and rejects non positive or non finite values`() = runTest(testDispatcher) {
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
        viewModel.setScaleFactor(-1.0)
        viewModel.setScaleFactor(Double.NaN)
        viewModel.setScaleFactor(Double.POSITIVE_INFINITY)
        viewModel.setScaleFactor(Double.NEGATIVE_INFINITY)
        advanceUntilIdle()
        assertEquals(0.5, viewModel.uiState.value.scaleFactor)
    }

    @Test
    fun `toggles Make Mode and bounds checkedSteps to current instruction steps`() = runTest(testDispatcher) {
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
        viewModel.toggleStepChecked(1)
        viewModel.toggleStepChecked(2)
        advanceUntilIdle()
        assertEquals(setOf(0, 1, 2), viewModel.uiState.value.checkedSteps)

        // Simulate editing recipe instructions shorter (from 3 steps down to 2 steps)
        repository.setDetail(
            1,
            sampleCocktailDetail(1).copy(instructions = "1. Stir with ice.\n2. Strain."),
        )
        advanceUntilIdle()
        assertEquals(setOf(0, 1), viewModel.uiState.value.checkedSteps)

        viewModel.toggleStepChecked(0)
        advanceUntilIdle()
        assertEquals(setOf(1), viewModel.uiState.value.checkedSteps)
    }

    @Test
    fun `guards concurrent favorite, delete, and refresh taps and consumes deleted event`() = runTest(testDispatcher) {
        val prefs = AppPreferences(InMemoryPreferencesDataStore())
        val favGate = CompletableDeferred<Unit>()
        val deleteGate = CompletableDeferred<Unit>()
        val repository = FakeCocktailRepository(
            initialDetails = mapOf(1 to sampleCocktailDetail(1)),
        ).apply {
            toggleFavoriteSuspendHook = { favGate.await() }
            deleteSuspendHook = { deleteGate.await() }
        }

        val viewModel = CocktailDetailViewModel(1, repository, prefs)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        // Concurrent favorite double tap
        viewModel.toggleFavorite()
        viewModel.toggleFavorite()
        testDispatcher.scheduler.runCurrent()
        assertEquals(listOf(1), repository.toggledFavoriteIds)
        favGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.cocktail?.isFavorite == true)

        // Concurrent delete double tap
        viewModel.deleteCocktail()
        viewModel.deleteCocktail()
        testDispatcher.scheduler.runCurrent()
        assertEquals(listOf(1), repository.deletedCocktailIds)
        deleteGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.deleted)
        viewModel.consumeDeletedEvent()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.deleted)
    }

    @Test
    fun `blocks duplicate concurrent refresh calls and exposes isRefreshing`() = runTest(testDispatcher) {
        val prefs = AppPreferences(InMemoryPreferencesDataStore())
        val refreshGate = CompletableDeferred<Result<com.ryanvidal.barfriend.core.model.CocktailDetail>>()
        val repository = FakeCocktailRepository(
            initialDetails = mapOf(1 to sampleCocktailDetail(1)),
        ).apply {
            refreshSuspendOverride = { refreshGate.await() }
        }

        val viewModel = CocktailDetailViewModel(1, repository, prefs)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value.isRefreshing)
        viewModel.refresh()
        testDispatcher.scheduler.runCurrent()
        assertEquals(listOf(1), repository.refreshedCocktailIds)

        refreshGate.complete(Result.success(sampleCocktailDetail(1)))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isRefreshing)
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
