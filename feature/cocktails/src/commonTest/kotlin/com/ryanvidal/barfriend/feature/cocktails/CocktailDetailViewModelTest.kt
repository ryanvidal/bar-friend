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
import kotlin.test.assertNotNull
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
            assertEquals(setOf(3, 4), viewModel.uiState.value.shoppingListIngredientIds)
            val addedEvent = assertNotNull(viewModel.uiState.value.snackbarEvent)
            assertEquals("Added Sweet Vermouth to shopping list", addedEvent.message)

            viewModel.consumeSnackbar(addedEvent.id)
            testDispatcher.scheduler.runCurrent()
            assertNull(viewModel.uiState.value.snackbarEvent)
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
        assertEquals("Network unavailable", viewModel.uiState.value.snackbarMessage)
        assertNull(viewModel.uiState.value.errorMessage)
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
            val batchEvent = assertNotNull(viewModel.uiState.value.snackbarEvent)
            assertEquals("Added 2 ingredients to shopping list", batchEvent.message)

            // Calling again when all required missing ingredients are already on list skips repo call
            viewModel.consumeSnackbar(batchEvent.id)
            viewModel.addMissingIngredientsToShoppingList()
            advanceUntilIdle()

            assertEquals(1, inventoryRepository.addToShoppingListCalls.size)
            assertEquals(
                "All missing ingredients are already on your shopping list",
                viewModel.uiState.value.snackbarMessage,
            )
        }

    @Test
    fun `blocks addMissingIngredientsToShoppingList while single toggle is in flight and vice versa`() =
        runTest(testDispatcher) {
            val prefs = AppPreferences(InMemoryPreferencesDataStore())
            val detail = sampleCocktailDetail(1).copy(
                ingredients = sampleCocktailDetail(1).ingredients + missingIngredient(
                    id = 4,
                    ingredientId = 4,
                    slug = "lime-juice",
                    name = "Lime Juice",
                    sort = 4,
                ),
            )
            val repository = FakeCocktailRepository(initialDetails = mapOf(1 to detail))
            val singleGate = CompletableDeferred<Unit>()
            val batchGate = CompletableDeferred<Unit>()
            val inventoryRepository = FakeInventoryRepository().apply {
                addToShoppingListSuspendHook = { ids, _ ->
                    if (ids == listOf(3)) {
                        singleGate.await()
                    } else if (ids == listOf(4)) {
                        batchGate.await()
                    }
                }
            }

            val viewModel = CocktailDetailViewModel(1, repository, inventoryRepository, prefs)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect {}
            }
            advanceUntilIdle()

            // Single toggle in flight blocks batch add-missing
            viewModel.toggleIngredientInShoppingList(3, "Sweet Vermouth")
            testDispatcher.scheduler.runCurrent()
            viewModel.addMissingIngredientsToShoppingList()
            testDispatcher.scheduler.runCurrent()

            assertEquals(setOf(3), viewModel.uiState.value.shoppingListInFlightIds)
            assertFalse(viewModel.uiState.value.isAddingMissingToShoppingList)
            assertEquals(
                listOf(AddToShoppingListCall(listOf(3), 1)),
                inventoryRepository.addToShoppingListCalls,
            )

            singleGate.complete(Unit)
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.shoppingListInFlightIds.isEmpty())

            // Batch add-missing in flight blocks single toggles
            viewModel.addMissingIngredientsToShoppingList()
            testDispatcher.scheduler.runCurrent()
            viewModel.toggleIngredientInShoppingList(3, "Sweet Vermouth")
            viewModel.toggleIngredientInShoppingList(4, "Lime Juice")
            testDispatcher.scheduler.runCurrent()

            assertTrue(viewModel.uiState.value.isAddingMissingToShoppingList)
            assertTrue(viewModel.uiState.value.shoppingListInFlightIds.isEmpty())
            assertTrue(inventoryRepository.removeFromShoppingListCalls.isEmpty())
            assertEquals(
                listOf(
                    AddToShoppingListCall(listOf(3), 1),
                    AddToShoppingListCall(listOf(4), 1),
                ),
                inventoryRepository.addToShoppingListCalls,
            )

            batchGate.complete(Unit)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isAddingMissingToShoppingList)
        }

    @Test
    fun `blocks shopping actions while isDeleting or deleted is true`() = runTest(testDispatcher) {
        val prefs = AppPreferences(InMemoryPreferencesDataStore())
        val deleteGate = CompletableDeferred<Unit>()
        val repository = FakeCocktailRepository(
            initialDetails = mapOf(1 to sampleCocktailDetail(1)),
        ).apply {
            deleteSuspendHook = { deleteGate.await() }
        }
        val inventoryRepository = FakeInventoryRepository()

        val viewModel = CocktailDetailViewModel(1, repository, inventoryRepository, prefs)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.deleteCocktail()
        testDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.uiState.value.isDeleting)

        viewModel.toggleIngredientInShoppingList(3, "Sweet Vermouth")
        viewModel.addMissingIngredientsToShoppingList()
        testDispatcher.scheduler.runCurrent()

        assertTrue(inventoryRepository.addToShoppingListCalls.isEmpty())
        assertTrue(viewModel.uiState.value.shoppingListInFlightIds.isEmpty())
        assertFalse(viewModel.uiState.value.isAddingMissingToShoppingList)

        deleteGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.deleted)

        viewModel.toggleIngredientInShoppingList(3, "Sweet Vermouth")
        viewModel.addMissingIngredientsToShoppingList()
        testDispatcher.scheduler.runCurrent()

        assertTrue(inventoryRepository.addToShoppingListCalls.isEmpty())
        assertTrue(viewModel.uiState.value.shoppingListInFlightIds.isEmpty())
        assertFalse(viewModel.uiState.value.isAddingMissingToShoppingList)
    }

    @Test
    fun `batch add, remove, favorite, and delete failures roll back state and fall back from blank error messages`() =
        runTest(testDispatcher) {
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

            // Batch add failure rolls back optimistic update and emits message
            inventoryRepository.addToShoppingListFailure = IllegalStateException("Batch failed")
            viewModel.addMissingIngredientsToShoppingList()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.isAddingMissingToShoppingList)
            assertTrue(viewModel.uiState.value.shoppingListIngredientIds.isEmpty())
            assertEquals("Batch failed", viewModel.uiState.value.snackbarMessage)
            assertNull(viewModel.uiState.value.errorMessage)

            // Blank batch add failure uses readable fallback
            inventoryRepository.addToShoppingListFailure = IllegalStateException("   ")
            viewModel.addMissingIngredientsToShoppingList()
            advanceUntilIdle()
            assertEquals(
                "Unable to add missing ingredients to shopping list.",
                viewModel.uiState.value.snackbarMessage,
            )

            // Blank single add failure uses readable fallback
            viewModel.toggleIngredientInShoppingList(3, "Sweet Vermouth")
            advanceUntilIdle()
            assertEquals("Unable to add Sweet Vermouth to shopping list.", viewModel.uiState.value.snackbarMessage)

            // Remove failure rolls back optimistic removal
            inventoryRepository.addToShoppingListFailure = null
            inventoryRepository.setShoppingListIds(setOf(3))
            advanceUntilIdle()
            inventoryRepository.removeFromShoppingListFailure = IllegalStateException("Remove failed")
            viewModel.toggleIngredientInShoppingList(3, "Sweet Vermouth")
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.shoppingListInFlightIds.isEmpty())
            assertEquals(setOf(3), viewModel.uiState.value.shoppingListIngredientIds)
            assertEquals("Remove failed", viewModel.uiState.value.snackbarMessage)
            assertNull(viewModel.uiState.value.errorMessage)

            // Blank remove failure uses readable fallback
            inventoryRepository.removeFromShoppingListFailure = IllegalStateException("")
            viewModel.toggleIngredientInShoppingList(3, "Sweet Vermouth")
            advanceUntilIdle()
            assertEquals(setOf(3), viewModel.uiState.value.shoppingListIngredientIds)
            assertEquals(
                "Unable to remove Sweet Vermouth from shopping list.",
                viewModel.uiState.value.snackbarMessage,
            )

            // Blank favorite and delete failures use readable fallbacks
            repository.toggleFavoriteFailure = IllegalStateException(" ")
            viewModel.toggleFavorite()
            advanceUntilIdle()
            assertEquals("Unable to update favorite status.", viewModel.uiState.value.snackbarMessage)
            assertNull(viewModel.uiState.value.errorMessage)

            repository.deleteFailure = IllegalStateException("")
            viewModel.deleteCocktail()
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.isDeleting)
            assertFalse(viewModel.uiState.value.deleted)
            assertEquals("Unable to delete recipe.", viewModel.uiState.value.snackbarMessage)
            assertNull(viewModel.uiState.value.errorMessage)
        }

    @Test
    fun `addMissingIngredientsToShoppingList deduplicates duplicate ingredientId entries`() = runTest(testDispatcher) {
        val prefs = AppPreferences(InMemoryPreferencesDataStore())
        val duplicateDetail = sampleCocktailDetail(1).copy(
            ingredients = listOf(
                missingIngredient(id = 10, ingredientId = 7, slug = "maraschino", name = "Maraschino", sort = 1),
                missingIngredient(id = 11, ingredientId = 7, slug = "maraschino", name = "Maraschino", sort = 2),
            ),
        )
        val repository = FakeCocktailRepository(initialDetails = mapOf(1 to duplicateDetail))
        val inventoryRepository = FakeInventoryRepository()

        val viewModel = CocktailDetailViewModel(1, repository, inventoryRepository, prefs)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.addMissingIngredientsToShoppingList()
        advanceUntilIdle()

        assertEquals(listOf(AddToShoppingListCall(listOf(7), 1)), inventoryRepository.addToShoppingListCalls)
        assertEquals("Added 1 ingredient to shopping list", viewModel.uiState.value.snackbarMessage)
    }

    @Test
    fun `reads fresh cocktail and shopping list state even without an active uiState collector`() =
        runTest(testDispatcher) {
            val prefs = AppPreferences(InMemoryPreferencesDataStore())
            val detail = sampleCocktailDetail(1).copy(
                ingredients = sampleCocktailDetail(1).ingredients + missingIngredient(
                    id = 7,
                    ingredientId = 7,
                    slug = "maraschino",
                    name = "Maraschino",
                    sort = 4,
                ),
            )
            val repository = FakeCocktailRepository(initialDetails = mapOf(1 to detail))
            val inventoryRepository = FakeInventoryRepository(initialShoppingListIds = setOf(3))

            val viewModel = CocktailDetailViewModel(1, repository, inventoryRepository, prefs)
            advanceUntilIdle()

            // uiState was never collected so its cached snapshot stays at initialValue
            assertNull(viewModel.uiState.value.cocktail)
            assertTrue(viewModel.uiState.value.shoppingListIngredientIds.isEmpty())

            // Single toggle observes fresh shopping list and removes ingredient 3 instead of adding it
            viewModel.toggleIngredientInShoppingList(3, "Sweet Vermouth")
            advanceUntilIdle()

            assertEquals(listOf(listOf(3)), inventoryRepository.removeFromShoppingListCalls)
            assertTrue(inventoryRepository.addToShoppingListCalls.isEmpty())

            // Reset list to {3} and call batch add without collecting uiState
            inventoryRepository.setShoppingListIds(setOf(3))
            viewModel.addMissingIngredientsToShoppingList()
            advanceUntilIdle()

            assertEquals(listOf(AddToShoppingListCall(listOf(7), 1)), inventoryRepository.addToShoppingListCalls)
        }

    @Test
    fun `emits singular copy and distinct monotonic snackbar IDs for repeated identical errors`() =
        runTest(testDispatcher) {
            val prefs = AppPreferences(InMemoryPreferencesDataStore())
            val repository = FakeCocktailRepository(initialDetails = mapOf(1 to sampleCocktailDetail(1)))
            val gate = CompletableDeferred<Unit>()
            val inventoryRepository = FakeInventoryRepository().apply {
                addToShoppingListFailure = IllegalStateException("Network error")
                addToShoppingListSuspendHook = { ids, _ ->
                    if (ids == listOf(4)) {
                        gate.await()
                    }
                }
            }

            val viewModel = CocktailDetailViewModel(1, repository, inventoryRepository, prefs)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.uiState.collect {}
            }
            advanceUntilIdle()

            // First error
            viewModel.toggleIngredientInShoppingList(3, "Sweet Vermouth")
            advanceUntilIdle()
            val firstEvent = assertNotNull(viewModel.uiState.value.snackbarEvent)
            assertEquals("Network error", firstEvent.message)

            // Starting another shopping mutation does not clear the existing snackbarEvent
            viewModel.toggleIngredientInShoppingList(4, "Lime Juice")
            testDispatcher.scheduler.runCurrent()
            assertEquals(firstEvent, viewModel.uiState.value.snackbarEvent)

            // Second identical error gets a strictly greater ID
            gate.complete(Unit)
            advanceUntilIdle()
            val secondEvent = assertNotNull(viewModel.uiState.value.snackbarEvent)
            assertEquals("Network error", secondEvent.message)
            assertTrue(secondEvent.id > firstEvent.id)

            // Consuming stale first event ID does not dismiss the second event
            viewModel.consumeSnackbar(firstEvent.id)
            testDispatcher.scheduler.runCurrent()
            assertEquals(secondEvent, viewModel.uiState.value.snackbarEvent)

            // Consuming current second event ID dismisses it
            viewModel.consumeSnackbar(secondEvent.id)
            testDispatcher.scheduler.runCurrent()
            assertNull(viewModel.uiState.value.snackbarEvent)

            // Singular copy when adding 1 missing ingredient
            inventoryRepository.addToShoppingListFailure = null
            viewModel.addMissingIngredientsToShoppingList()
            advanceUntilIdle()
            assertEquals("Added 1 ingredient to shopping list", viewModel.uiState.value.snackbarMessage)
        }

    private fun missingIngredient(
        id: Int,
        ingredientId: Int,
        slug: String,
        name: String,
        sort: Int,
    ): CocktailIngredient = CocktailIngredient(
        id = id,
        ingredientId = ingredientId,
        ingredientSlug = slug,
        name = name,
        sort = sort,
        amount = 15.0,
        amountMax = null,
        units = "ml",
        optional = false,
        note = null,
        inBarShelf = false,
        substitutes = emptyList(),
    )
}
