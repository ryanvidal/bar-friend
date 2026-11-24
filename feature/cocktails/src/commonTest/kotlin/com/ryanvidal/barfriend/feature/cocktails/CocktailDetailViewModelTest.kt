package com.ryanvidal.barfriend.feature.cocktails

import com.ryanvidal.barfriend.core.data.AppPreferences
import com.ryanvidal.barfriend.core.model.CocktailIngredient
import com.ryanvidal.barfriend.core.model.IngredientSubstitute
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
        val inventoryRepository = FakeInventoryRepository()

        val viewModel = CocktailDetailViewModel(
            cocktailId = 1,
            cocktailRepository = repository,
            inventoryRepository = inventoryRepository,
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
        val inventoryRepository = FakeInventoryRepository()

        val viewModel = CocktailDetailViewModel(1, repository, inventoryRepository, prefs)
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
        val inventoryRepository = FakeInventoryRepository()

        val viewModel = CocktailDetailViewModel(1, repository, inventoryRepository, prefs)
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
        val inventoryRepository = FakeInventoryRepository()

        val viewModel = CocktailDetailViewModel(1, repository, inventoryRepository, prefs)
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
        val inventoryRepository = FakeInventoryRepository()

        val viewModel = CocktailDetailViewModel(1, repository, inventoryRepository, prefs)
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
        val inventoryRepository = FakeInventoryRepository()

        val viewModel = CocktailDetailViewModel(1, repository, inventoryRepository, prefs)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        assertEquals("Negroni", viewModel.uiState.value.cocktail?.name)
        assertFalse(viewModel.uiState.value.isLoading)
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `toggleIngredientInShoppingList adds missing bottle, removes if listed, and blocks concurrent duplicate taps per ID`() =
        runTest(testDispatcher) {
            val prefs = AppPreferences(InMemoryPreferencesDataStore())
            val repository = FakeCocktailRepository(
                initialDetails = mapOf(1 to sampleCocktailDetail(1)),
            )
            val addGateFor3 = CompletableDeferred<Unit>()
            val addGateFor4 = CompletableDeferred<Unit>()
            val removeGateFor3 = CompletableDeferred<Unit>()
            val inventoryRepository = FakeInventoryRepository().apply {
                addToShoppingListSuspendHook = { ids, _ ->
                    when (ids.singleOrNull()) {
                        3 -> addGateFor3.await()
                        4 -> addGateFor4.await()
                    }
                }
                removeFromShoppingListSuspendHook = { ids ->
                    if (ids == listOf(3)) {
                        removeGateFor3.await()
                    }
                }
            }

            val viewModel = CocktailDetailViewModel(1, repository, inventoryRepository, prefs)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect {}
            }
            advanceUntilIdle()

            // Duplicate taps for ingredient 3 while in-flight are blocked, while ingredient 4 runs concurrently
            viewModel.toggleIngredientInShoppingList(3, "Sweet Vermouth")
            viewModel.toggleIngredientInShoppingList(3, "Sweet Vermouth")
            viewModel.toggleIngredientInShoppingList(4, "Lime Juice")
            testDispatcher.scheduler.runCurrent()

            assertEquals(setOf(3, 4), viewModel.uiState.value.shoppingListInFlightIds)
            assertEquals(
                listOf(
                    AddToShoppingListCall(listOf(3), 1),
                    AddToShoppingListCall(listOf(4), 1),
                ),
                inventoryRepository.addToShoppingListCalls,
            )

            addGateFor3.complete(Unit)
            advanceUntilIdle()

            assertEquals(setOf(4), viewModel.uiState.value.shoppingListInFlightIds)
            assertEquals(setOf(3), viewModel.uiState.value.shoppingListIngredientIds)
            assertEquals("Added Sweet Vermouth to shopping list", viewModel.uiState.value.snackbarMessage)

            viewModel.consumeSnackbar()
            testDispatcher.scheduler.runCurrent()
            assertNull(viewModel.uiState.value.snackbarMessage)

            addGateFor4.complete(Unit)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.shoppingListInFlightIds.isEmpty())
            assertEquals(setOf(3, 4), viewModel.uiState.value.shoppingListIngredientIds)

            // Toggling an already-listed ingredient removes it and blocks concurrent duplicate taps
            viewModel.toggleIngredientInShoppingList(3, "Sweet Vermouth")
            viewModel.toggleIngredientInShoppingList(3, "Sweet Vermouth")
            testDispatcher.scheduler.runCurrent()

            assertEquals(setOf(3), viewModel.uiState.value.shoppingListInFlightIds)
            assertEquals(listOf(listOf(3)), inventoryRepository.removeFromShoppingListCalls)

            removeGateFor3.complete(Unit)
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.shoppingListInFlightIds.isEmpty())
            assertEquals(setOf(4), viewModel.uiState.value.shoppingListIngredientIds)
            assertEquals("Removed Sweet Vermouth from shopping list", viewModel.uiState.value.snackbarMessage)
        }

    @Test
    fun `toggleIngredientInShoppingList surfaces failure and clears in-flight state`() = runTest(testDispatcher) {
        val prefs = AppPreferences(InMemoryPreferencesDataStore())
        val repository = FakeCocktailRepository(
            initialDetails = mapOf(1 to sampleCocktailDetail(1)),
        )
        val inventoryRepository = FakeInventoryRepository().apply {
            addToShoppingListFailure = IllegalStateException("Network unavailable")
        }

        val viewModel = CocktailDetailViewModel(1, repository, inventoryRepository, prefs)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.toggleIngredientInShoppingList(3, "Sweet Vermouth")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.shoppingListInFlightIds.isEmpty())
        assertTrue(viewModel.uiState.value.shoppingListIngredientIds.isEmpty())
        assertNull(viewModel.uiState.value.snackbarMessage)
        assertEquals("Network unavailable", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `addMissingIngredientsToShoppingList adds only unlisted required missing ingredients and skips optional, in-stock, substitute, and already-listed items`() =
        runTest(testDispatcher) {
            val prefs = AppPreferences(InMemoryPreferencesDataStore())
            val detail = sampleCocktailDetail(1).copy(
                ingredients = listOf(
                    // In stock -> skip
                    CocktailIngredient(
                        id = 1,
                        ingredientId = 1,
                        ingredientSlug = "gin",
                        name = "London Dry Gin",
                        sort = 1,
                        amount = 30.0,
                        amountMax = null,
                        units = "ml",
                        optional = false,
                        note = null,
                        inBarShelf = true,
                        substitutes = emptyList(),
                    ),
                    // Substitute available -> skip
                    CocktailIngredient(
                        id = 2,
                        ingredientId = 2,
                        ingredientSlug = "campari",
                        name = "Campari",
                        sort = 2,
                        amount = 30.0,
                        amountMax = null,
                        units = "ml",
                        optional = false,
                        note = null,
                        inBarShelf = false,
                        inBarShelfAsSubstitute = true,
                        substitutes = listOf(
                            IngredientSubstitute(id = 9, slug = "aperitivo", name = "Aperitivo", inBarShelf = true),
                        ),
                    ),
                    // Variant available -> skip
                    CocktailIngredient(
                        id = 3,
                        ingredientId = 3,
                        ingredientSlug = "rye",
                        name = "Rye Whiskey",
                        sort = 3,
                        amount = 30.0,
                        amountMax = null,
                        units = "ml",
                        optional = false,
                        note = null,
                        inBarShelf = false,
                        inBarShelfAsVariant = true,
                        substitutes = emptyList(),
                    ),
                    // Complex homemade available -> skip
                    CocktailIngredient(
                        id = 4,
                        ingredientId = 4,
                        ingredientSlug = "syrup",
                        name = "Demerara Syrup",
                        sort = 4,
                        amount = 15.0,
                        amountMax = null,
                        units = "ml",
                        optional = false,
                        note = null,
                        inBarShelf = false,
                        inBarShelfAsComplex = true,
                        substitutes = emptyList(),
                    ),
                    // Optional missing -> skip
                    CocktailIngredient(
                        id = 5,
                        ingredientId = 5,
                        ingredientSlug = "orange-bitters",
                        name = "Orange Bitters",
                        sort = 5,
                        amount = 2.0,
                        amountMax = null,
                        units = "dash",
                        optional = true,
                        note = null,
                        inBarShelf = false,
                        substitutes = emptyList(),
                    ),
                    // Required missing, already on shopping list -> skip
                    CocktailIngredient(
                        id = 6,
                        ingredientId = 6,
                        ingredientSlug = "sweet-vermouth",
                        name = "Sweet Vermouth",
                        sort = 6,
                        amount = 30.0,
                        amountMax = null,
                        units = "ml",
                        optional = false,
                        note = null,
                        inBarShelf = false,
                        substitutes = emptyList(),
                    ),
                    // Required missing, NOT on list -> include
                    CocktailIngredient(
                        id = 7,
                        ingredientId = 7,
                        ingredientSlug = "maraschino",
                        name = "Maraschino Liqueur",
                        sort = 7,
                        amount = 15.0,
                        amountMax = null,
                        units = "ml",
                        optional = false,
                        note = null,
                        inBarShelf = false,
                        substitutes = emptyList(),
                    ),
                    // Required missing, NOT on list -> include
                    CocktailIngredient(
                        id = 8,
                        ingredientId = 8,
                        ingredientSlug = "chartreuse",
                        name = "Green Chartreuse",
                        sort = 8,
                        amount = 15.0,
                        amountMax = null,
                        units = "ml",
                        optional = false,
                        note = null,
                        inBarShelf = false,
                        substitutes = emptyList(),
                    ),
                ),
            )
            val repository = FakeCocktailRepository(
                initialDetails = mapOf(1 to detail),
            )
            val addMissingGate = CompletableDeferred<Unit>()
            val inventoryRepository = FakeInventoryRepository(
                initialShoppingListIds = setOf(6),
            ).apply {
                addToShoppingListSuspendHook = { _, _ -> addMissingGate.await() }
            }

            val viewModel = CocktailDetailViewModel(1, repository, inventoryRepository, prefs)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect {}
            }
            advanceUntilIdle()

            // Concurrent taps are guarded by isAddingMissingToShoppingList
            viewModel.addMissingIngredientsToShoppingList()
            viewModel.addMissingIngredientsToShoppingList()
            testDispatcher.scheduler.runCurrent()

            assertTrue(viewModel.uiState.value.isAddingMissingToShoppingList)
            assertEquals(
                listOf(AddToShoppingListCall(listOf(7, 8), 1)),
                inventoryRepository.addToShoppingListCalls,
            )

            addMissingGate.complete(Unit)
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isAddingMissingToShoppingList)
            assertEquals(setOf(6, 7, 8), viewModel.uiState.value.shoppingListIngredientIds)
            assertEquals("Added 2 ingredient(s) to shopping list", viewModel.uiState.value.snackbarMessage)

            // Calling again when all required missing ingredients are already on list skips repo call
            viewModel.consumeSnackbar()
            viewModel.addMissingIngredientsToShoppingList()
            advanceUntilIdle()

            assertEquals(1, inventoryRepository.addToShoppingListCalls.size)
            assertEquals(
                "All missing ingredients are already on your shopping list",
                viewModel.uiState.value.snackbarMessage,
            )
        }
}
