package com.ryanvidal.barfriend.feature.inventory

import com.ryanvidal.barfriend.core.data.BarSyncCoordinator
import com.ryanvidal.barfriend.core.data.InventoryRepository
import com.ryanvidal.barfriend.core.data.SyncStatus
import com.ryanvidal.barfriend.core.model.IngredientSummary
import com.ryanvidal.barfriend.core.model.RecommendedIngredient
import com.ryanvidal.barfriend.core.model.ShoppingListItem
import com.ryanvidal.barfriend.core.model.UnlockedCocktail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InventoryViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sampleIngredients(): List<IngredientSummary> = listOf(
        IngredientSummary(
            id = 1,
            slug = "london-dry-gin",
            name = "London Dry Gin",
            strength = 40.0,
            categoryName = "Gin",
            mainImageThumbUrl = null,
            inBarShelf = true,
            inShoppingList = false,
        ),
        IngredientSummary(
            id = 2,
            slug = "campari",
            name = "Campari",
            strength = 25.0,
            categoryName = "Amaro",
            mainImageThumbUrl = null,
            inBarShelf = true,
            inShoppingList = false,
        ),
        IngredientSummary(
            id = 3,
            slug = "sweet-vermouth",
            name = "Sweet Vermouth",
            strength = 16.5,
            categoryName = "Vermouth",
            mainImageThumbUrl = "https://example.com/vermouth.jpg",
            inBarShelf = false,
            inShoppingList = true,
        ),
        IngredientSummary(
            id = 4,
            slug = "lime-juice",
            name = "Fresh Lime Juice",
            strength = 0.0,
            categoryName = "Citrus",
            mainImageThumbUrl = null,
            inBarShelf = false,
            inShoppingList = false,
        ),
    )

    @Test
    fun `filters by search, stock status, category, and prunes stale category on bar switch`() = runTest(testDispatcher) {
        val ingredients = sampleIngredients()
        val syncCoordinator = FakeBarSyncCoordinator()
        val inventoryRepo = FakeInventoryRepository(initialIngredients = ingredients)
        val viewModel = InventoryViewModel(inventoryRepo, syncCoordinator)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        assertEquals(2, viewModel.uiState.value.inStockCount)
        assertEquals(4, viewModel.uiState.value.totalIngredientsCount)
        assertEquals(listOf("Amaro", "Citrus", "Gin", "Vermouth"), viewModel.uiState.value.categories)

        viewModel.onSearchQueryChanged("vermouth")
        advanceUntilIdle()
        assertEquals(listOf("Sweet Vermouth"), viewModel.uiState.value.filteredIngredients.map { it.name })

        viewModel.onSearchQueryChanged("")
        viewModel.selectStockFilter(ShelfStockFilter.IN_STOCK)
        advanceUntilIdle()
        assertEquals(listOf("London Dry Gin", "Campari"), viewModel.uiState.value.filteredIngredients.map { it.name })

        viewModel.selectStockFilter(ShelfStockFilter.OUT_OF_STOCK)
        advanceUntilIdle()
        assertEquals(listOf("Sweet Vermouth", "Fresh Lime Juice"), viewModel.uiState.value.filteredIngredients.map { it.name })

        viewModel.selectStockFilter(ShelfStockFilter.ALL)
        viewModel.selectCategory("Amaro")
        advanceUntilIdle()
        assertEquals(listOf("Campari"), viewModel.uiState.value.filteredIngredients.map { it.name })

        // Switch bar catalog: keep Gin, drop Amaro
        val switchedIngredients = listOf(
            IngredientSummary(1, "london-dry-gin", "London Dry Gin", 40.0, "Gin", null, true, false),
            IngredientSummary(10, "bourbon", "Bourbon", 45.0, "Whiskey", null, false, false),
        )
        inventoryRepo.replaceCatalogForBar(switchedIngredients)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.selectedCategory)
        assertEquals(listOf("Gin", "Whiskey"), viewModel.uiState.value.categories)
        assertEquals(listOf("London Dry Gin", "Bourbon"), viewModel.uiState.value.filteredIngredients.map { it.name })
    }

    @Test
    fun `toggleBarShelf applies optimistic updates, guards double taps, and reloads recommendations`() = runTest(testDispatcher) {
        val ingredients = sampleIngredients()
        val gates = mapOf(
            3 to CompletableDeferred<Result<Unit>>(),
            4 to CompletableDeferred<Result<Unit>>(),
        )
        val syncCoordinator = FakeBarSyncCoordinator()
        val inventoryRepo = FakeInventoryRepository(initialIngredients = ingredients).apply {
            recommendedResult = Result.success(
                listOf(
                    RecommendedIngredient(3, "sweet-vermouth", "Sweet Vermouth", 4, "Vermouth"),
                    RecommendedIngredient(4, "lime-juice", "Fresh Lime Juice", 2, "Citrus"),
                ),
            )
            setInBarShelfHook = { id, _ -> gates.getValue(id).await() }
        }
        val viewModel = InventoryViewModel(inventoryRepo, syncCoordinator)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        assertEquals(1, inventoryRepo.getRecommendedCalls)
        assertTrue(viewModel.uiState.value.recommendations.first { it.id == 3 }.inShoppingList)

        // Tap 3 twice quickly and tap 4 concurrently
        viewModel.toggleBarShelf(3, true)
        viewModel.toggleBarShelf(3, false)
        viewModel.toggleBarShelf(4, true)
        testDispatcher.scheduler.runCurrent()

        // Optimistic updates applied immediately while suspended
        assertEquals(setOf(3, 4), viewModel.uiState.value.inFlightShelfIds)
        assertTrue(viewModel.uiState.value.allIngredients.first { it.id == 3 }.inBarShelf)
        assertTrue(viewModel.uiState.value.allIngredients.first { it.id == 4 }.inBarShelf)
        assertTrue(viewModel.uiState.value.recommendations.none { it.id == 3 || it.id == 4 })
        assertEquals(4, viewModel.uiState.value.inStockCount)
        assertEquals(listOf(listOf(3) to true, listOf(4) to true), inventoryRepo.setInBarShelfCalls)

        // Fail 3 -> rolls back optimistic shelf state and shows snackbar
        gates.getValue(3).complete(Result.failure(IllegalStateException("Network unavailable")))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.allIngredients.first { it.id == 3 }.inBarShelf)
        assertTrue(viewModel.uiState.value.recommendations.any { it.id == 3 })
        val event = assertNotNull(viewModel.uiState.value.snackbarEvent)
        assertEquals("Network unavailable", event.message)
        viewModel.consumeSnackbar(event.id)
        testDispatcher.scheduler.runCurrent()
        assertNull(viewModel.uiState.value.snackbarEvent)

        // Succeed 4 -> stays in bar shelf and triggers recommendations reload
        gates.getValue(4).complete(Result.success(Unit))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.inFlightShelfIds.isEmpty())
        assertTrue(viewModel.uiState.value.allIngredients.first { it.id == 4 }.inBarShelf)
        assertTrue(viewModel.uiState.value.recommendations.none { it.id == 4 })
        assertEquals(3, viewModel.uiState.value.inStockCount)
        assertEquals(2, inventoryRepo.getRecommendedCalls)

        // Toggling shopping list on a shelf item updates recommendation badge without reloading recommendations
        viewModel.toggleShoppingList(1, true)
        advanceUntilIdle()
        assertEquals(2, inventoryRepo.getRecommendedCalls)
    }

    @Test
    fun `shopping list add, quantity stepper with decrement-to-remove, remove, markPurchased, and bulk actions`() = runTest(testDispatcher) {
        val ingredients = sampleIngredients()
        val syncCoordinator = FakeBarSyncCoordinator()
        val inventoryRepo = FakeInventoryRepository(initialIngredients = ingredients)
        val viewModel = InventoryViewModel(inventoryRepo, syncCoordinator)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        assertEquals(listOf(3), viewModel.uiState.value.shoppingList.map { it.ingredientId })

        // Add ingredient 4
        viewModel.addToShoppingList(4)
        advanceUntilIdle()
        assertEquals(setOf(3, 4), viewModel.uiState.value.shoppingList.map { it.ingredientId }.toSet())
        assertEquals(1, viewModel.uiState.value.shoppingList.first { it.ingredientId == 4 }.quantity)

        // Increment quantity of 4 -> 2
        viewModel.updateQuantity(4, 2)
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.shoppingList.first { it.ingredientId == 4 }.quantity)

        // Decrement quantity of 4 -> 1
        viewModel.updateQuantity(4, 1)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.shoppingList.first { it.ingredientId == 4 }.quantity)

        // Decrement from 1 -> 0 removes item from shopping list
        viewModel.updateQuantity(4, 0)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.shoppingList.any { it.ingredientId == 4 })

        // Add 4 back and remove explicitly
        viewModel.addToShoppingList(4)
        advanceUntilIdle()
        viewModel.removeFromShoppingList(4)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.shoppingList.any { it.ingredientId == 4 })

        // 1-tap markPurchased moves 3 to Bar Shelf and removes from Shopping List
        viewModel.markPurchased(3)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.shoppingList.any { it.ingredientId == 3 })
        assertTrue(viewModel.uiState.value.allIngredients.first { it.id == 3 }.inBarShelf)
        val purchasedEvent = assertNotNull(viewModel.uiState.value.snackbarEvent)
        assertEquals("Moved Sweet Vermouth to Bar Shelf", purchasedEvent.message)
        viewModel.consumeSnackbar(purchasedEvent.id)

        // Bulk markAllPurchased with guard
        viewModel.addToShoppingList(4)
        advanceUntilIdle()
        val bulkGate = CompletableDeferred<Result<Unit>>()
        inventoryRepo.markPurchasedHook = { bulkGate.await() }

        viewModel.markAllPurchased()
        viewModel.markAllPurchased()
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value.isBulkUpdating)
        assertEquals(1, inventoryRepo.bulkMarkPurchasedCalls)

        bulkGate.complete(Result.success(Unit))
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isBulkUpdating)
        assertTrue(viewModel.uiState.value.shoppingList.isEmpty())
        assertTrue(viewModel.uiState.value.allIngredients.first { it.id == 4 }.inBarShelf)

        // Clear shopping list
        inventoryRepo.markPurchasedHook = null
        viewModel.addToShoppingList(1)
        viewModel.addToShoppingList(2)
        advanceUntilIdle()
        assertEquals(2, viewModel.uiState.value.shoppingList.size)

        viewModel.clearShoppingList()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.shoppingList.isEmpty())
    }

    @Test
    fun `openUnlockedCocktails handles success, live shelf sync, and error state`() = runTest(testDispatcher) {
        val ingredients = sampleIngredients()
        val syncCoordinator = FakeBarSyncCoordinator()
        val inventoryRepo = FakeInventoryRepository(initialIngredients = ingredients).apply {
            unlockedCocktailsMap[3] = Result.success(
                listOf(
                    UnlockedCocktail(101, "negroni", "Negroni"),
                    UnlockedCocktail(102, "manhattan", "Manhattan"),
                ),
            )
            unlockedCocktailsMap[4] = Result.failure(IllegalStateException("Server timeout"))
        }
        val viewModel = InventoryViewModel(inventoryRepo, syncCoordinator)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        // Success case
        viewModel.openUnlockedCocktails(3, "Sweet Vermouth")
        advanceUntilIdle()

        val successSheet = assertNotNull(viewModel.uiState.value.unlockedSheet)
        assertEquals(3, successSheet.ingredientId)
        assertEquals("Sweet Vermouth", successSheet.ingredientName)
        assertFalse(successSheet.isLoading)
        assertNull(successSheet.errorMessage)
        assertEquals(listOf("Negroni", "Manhattan"), successSheet.cocktails.map { it.name })
        assertFalse(successSheet.inBarShelf)
        assertTrue(successSheet.inShoppingList)

        // Live sync inside sheet when toggling shelf and shopping list
        viewModel.toggleBarShelf(3, true)
        viewModel.toggleShoppingList(3, false)
        advanceUntilIdle()

        val updatedSheet = assertNotNull(viewModel.uiState.value.unlockedSheet)
        assertTrue(updatedSheet.inBarShelf)
        assertFalse(updatedSheet.inShoppingList)

        viewModel.dismissUnlockedCocktails()
        testDispatcher.scheduler.runCurrent()
        assertNull(viewModel.uiState.value.unlockedSheet)

        // Error case
        viewModel.openUnlockedCocktails(4, "Fresh Lime Juice")
        advanceUntilIdle()

        val errorSheet = assertNotNull(viewModel.uiState.value.unlockedSheet)
        assertFalse(errorSheet.isLoading)
        assertTrue(errorSheet.cocktails.isEmpty())
        assertEquals("Server timeout", errorSheet.errorMessage)

        // refreshAll invokes syncAll, syncShoppingList, and recommendations reload
        viewModel.refreshAll()
        advanceUntilIdle()
        assertEquals(1, syncCoordinator.syncAllCalls)
        assertEquals(1, inventoryRepo.syncShoppingListCalls)
    }

    @Test
    fun `updateQuantity coalesces rapid taps per id while allowing concurrent updates on distinct ids`() = runTest(testDispatcher) {
        val ingredients = sampleIngredients()
        val gates = mapOf(
            3 to CompletableDeferred<Result<Unit>>(),
            4 to CompletableDeferred<Result<Unit>>(),
        )
        val syncCoordinator = FakeBarSyncCoordinator()
        val inventoryRepo = FakeInventoryRepository(initialIngredients = ingredients).apply {
            updateQuantityHook = { id, _ -> gates.getValue(id).await() }
        }
        val viewModel = InventoryViewModel(inventoryRepo, syncCoordinator)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.addToShoppingList(4)
        advanceUntilIdle()

        viewModel.updateQuantity(3, 2)
        viewModel.updateQuantity(3, 3)
        viewModel.updateQuantity(4, 5)
        testDispatcher.scheduler.runCurrent()

        assertEquals(setOf(3, 4), viewModel.uiState.value.pendingQuantityIds)
        assertTrue(viewModel.uiState.value.inFlightQuantityIds.isEmpty())
        assertTrue(inventoryRepo.updateQuantityCalls.isEmpty())

        testDispatcher.scheduler.advanceTimeBy(250L)
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value.pendingQuantityIds.isEmpty())
        assertEquals(setOf(3, 4), viewModel.uiState.value.inFlightQuantityIds)
        assertEquals(listOf(3 to 3, 4 to 5), inventoryRepo.updateQuantityCalls)

        gates.getValue(3).complete(Result.success(Unit))
        gates.getValue(4).complete(Result.success(Unit))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.inFlightQuantityIds.isEmpty())
        assertEquals(3, viewModel.uiState.value.shoppingList.first { it.ingredientId == 3 }.quantity)
        assertEquals(5, viewModel.uiState.value.shoppingList.first { it.ingredientId == 4 }.quantity)
    }

    @Test
    fun `deduplicates recommendations and unlocked cocktails and enriches missing recommendation metadata`() = runTest(testDispatcher) {
        val ingredients = sampleIngredients()
        val syncCoordinator = FakeBarSyncCoordinator()
        val inventoryRepo = FakeInventoryRepository(initialIngredients = ingredients).apply {
            recommendedResult = Result.success(
                listOf(
                    RecommendedIngredient(
                        id = 3,
                        slug = "sweet-vermouth",
                        name = "Sweet Vermouth",
                        potentialCocktails = 4,
                        categoryName = null,
                        mainImageThumbUrl = null,
                    ),
                    RecommendedIngredient(
                        id = 3,
                        slug = "sweet-vermouth",
                        name = "Sweet Vermouth Duplicate",
                        potentialCocktails = 4,
                    ),
                    RecommendedIngredient(
                        id = 4,
                        slug = "lime-juice",
                        name = "Fresh Lime Juice",
                        potentialCocktails = 2,
                        categoryName = "Citrus",
                    ),
                ),
            )
            unlockedCocktailsMap[3] = Result.success(
                listOf(
                    UnlockedCocktail(101, "negroni", "Negroni"),
                    UnlockedCocktail(101, "negroni", "Negroni"),
                    UnlockedCocktail(102, "manhattan", "Manhattan"),
                ),
            )
        }
        val viewModel = InventoryViewModel(inventoryRepo, syncCoordinator)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        assertEquals(listOf(3, 4), viewModel.uiState.value.recommendations.map { it.id })
        val vermouthRec = viewModel.uiState.value.recommendations.first { it.id == 3 }
        assertEquals("Vermouth", vermouthRec.categoryName)
        assertEquals("https://example.com/vermouth.jpg", vermouthRec.mainImageThumbUrl)

        viewModel.openUnlockedCocktails(3, "Sweet Vermouth")
        advanceUntilIdle()
        val sheet = assertNotNull(viewModel.uiState.value.unlockedSheet)
        assertEquals(listOf(101, 102), sheet.cocktails.map { it.id })
    }

    @Test
    fun `isRefreshing stays false when only syncStatus isSyncing is true`() = runTest(testDispatcher) {
        val ingredients = sampleIngredients()
        val syncCoordinator = FakeBarSyncCoordinator()
        val inventoryRepo = FakeInventoryRepository(initialIngredients = ingredients)
        val viewModel = InventoryViewModel(inventoryRepo, syncCoordinator)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        syncCoordinator.setSyncStatus(SyncStatus(isSyncing = true))
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value.syncStatus.isSyncing)
        assertFalse(viewModel.uiState.value.isRefreshing)

        val syncGate = CompletableDeferred<Result<Unit>>()
        syncCoordinator.syncAllHook = { syncGate.await() }
        syncCoordinator.setSyncStatus(SyncStatus(isSyncing = false))

        viewModel.refreshAll()
        testDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.uiState.value.isRefreshing)

        syncGate.complete(Result.success(Unit))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun `markAllPurchased and clearShoppingList reject while per-item ops or pending quantities are in flight`() = runTest(testDispatcher) {
        val ingredients = sampleIngredients()
        val syncCoordinator = FakeBarSyncCoordinator()
        val shelfGate = CompletableDeferred<Result<Unit>>()
        val shoppingGate = CompletableDeferred<Result<Unit>>()
        val purchaseGate = CompletableDeferred<Result<Unit>>()
        val quantityGate = CompletableDeferred<Result<Unit>>()
        val inventoryRepo = FakeInventoryRepository(initialIngredients = ingredients)
        val viewModel = InventoryViewModel(inventoryRepo, syncCoordinator)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        // 1. Shelf mutation in flight
        inventoryRepo.setInBarShelfHook = { _, _ -> shelfGate.await() }
        viewModel.toggleBarShelf(4, true)
        testDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.uiState.value.hasInFlightItemMutations)

        viewModel.markAllPurchased()
        viewModel.clearShoppingList()
        testDispatcher.scheduler.runCurrent()
        assertFalse(viewModel.uiState.value.isBulkUpdating)
        assertEquals(0, inventoryRepo.bulkMarkPurchasedCalls)
        assertTrue(inventoryRepo.removeFromShoppingListCalls.isEmpty())

        shelfGate.complete(Result.success(Unit))
        advanceUntilIdle()
        inventoryRepo.setInBarShelfHook = null

        // 2. Shopping list mutation in flight
        inventoryRepo.addToShoppingListHook = { _, _ -> shoppingGate.await() }
        viewModel.addToShoppingList(1)
        testDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.uiState.value.hasInFlightItemMutations)

        viewModel.markAllPurchased()
        viewModel.clearShoppingList()
        testDispatcher.scheduler.runCurrent()
        assertFalse(viewModel.uiState.value.isBulkUpdating)

        shoppingGate.complete(Result.success(Unit))
        advanceUntilIdle()
        inventoryRepo.addToShoppingListHook = null

        // 3. Purchase mutation in flight
        inventoryRepo.markPurchasedHook = { purchaseGate.await() }
        viewModel.markPurchased(1)
        testDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.uiState.value.hasInFlightItemMutations)

        viewModel.markAllPurchased()
        viewModel.clearShoppingList()
        testDispatcher.scheduler.runCurrent()
        assertEquals(1, inventoryRepo.bulkMarkPurchasedCalls) // only the single markPurchased(1) call

        purchaseGate.complete(Result.success(Unit))
        advanceUntilIdle()
        inventoryRepo.markPurchasedHook = null

        // 4. Pending debounced quantity update
        inventoryRepo.updateQuantityHook = { _, _ -> quantityGate.await() }
        viewModel.updateQuantity(3, 2)
        testDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.uiState.value.pendingQuantityIds.contains(3))
        assertTrue(viewModel.uiState.value.hasInFlightItemMutations)

        viewModel.markAllPurchased()
        viewModel.clearShoppingList()
        testDispatcher.scheduler.runCurrent()
        assertFalse(viewModel.uiState.value.isBulkUpdating)

        // 5. In-flight quantity update
        testDispatcher.scheduler.advanceTimeBy(250L)
        testDispatcher.scheduler.runCurrent()
        assertTrue(viewModel.uiState.value.inFlightQuantityIds.contains(3))
        assertTrue(viewModel.uiState.value.hasInFlightItemMutations)

        viewModel.markAllPurchased()
        viewModel.clearShoppingList()
        testDispatcher.scheduler.runCurrent()
        assertFalse(viewModel.uiState.value.isBulkUpdating)

        quantityGate.complete(Result.success(Unit))
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.hasInFlightItemMutations)

        // Once all per-item mutations finish, bulk actions succeed
        viewModel.clearShoppingList()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.shoppingList.isEmpty())
    }

    @Test
    fun `rapid coalesced updateQuantity taps send only final quantity and decrement to zero hides immediately`() = runTest(testDispatcher) {
        val ingredients = sampleIngredients()
        val syncCoordinator = FakeBarSyncCoordinator()
        val removeGate = CompletableDeferred<Result<Unit>>()
        val inventoryRepo = FakeInventoryRepository(initialIngredients = ingredients).apply {
            removeFromShoppingListHook = { removeGate.await() }
        }
        val viewModel = InventoryViewModel(inventoryRepo, syncCoordinator)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.updateQuantity(3, 2)
        testDispatcher.scheduler.advanceTimeBy(50L)
        viewModel.updateQuantity(3, 3)
        testDispatcher.scheduler.advanceTimeBy(50L)
        viewModel.updateQuantity(3, 4)
        testDispatcher.scheduler.runCurrent()

        assertEquals(4, viewModel.uiState.value.shoppingList.first { it.ingredientId == 3 }.quantity)
        assertEquals(setOf(3), viewModel.uiState.value.pendingQuantityIds)
        assertTrue(inventoryRepo.updateQuantityCalls.isEmpty())

        testDispatcher.scheduler.advanceTimeBy(250L)
        advanceUntilIdle()

        assertEquals(listOf(3 to 4), inventoryRepo.updateQuantityCalls)
        assertTrue(viewModel.uiState.value.pendingQuantityIds.isEmpty())
        assertTrue(viewModel.uiState.value.inFlightQuantityIds.isEmpty())

        // Decrementing 1 -> 0 hides item optimistically right away while remove is in flight
        viewModel.updateQuantity(3, 0)
        testDispatcher.scheduler.runCurrent()

        assertTrue(viewModel.uiState.value.shoppingList.none { it.ingredientId == 3 })
        assertEquals(setOf(3), viewModel.uiState.value.inFlightShoppingIds)
        assertEquals(listOf(listOf(3)), inventoryRepo.removeFromShoppingListCalls)

        removeGate.complete(Result.success(Unit))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.inFlightShoppingIds.isEmpty())
        assertTrue(viewModel.uiState.value.shoppingList.isEmpty())
    }

    @Test
    fun `superseding an in-flight updateQuantity does not clobber newer optimistic quantity or emit cancellation error`() = runTest(testDispatcher) {
        val ingredients = sampleIngredients()
        val firstGate = CompletableDeferred<Result<Unit>>()
        val secondGate = CompletableDeferred<Result<Unit>>()
        var callCount = 0
        val syncCoordinator = FakeBarSyncCoordinator()
        val inventoryRepo = FakeInventoryRepository(initialIngredients = ingredients).apply {
            updateQuantityHook = { _, _ ->
                callCount += 1
                if (callCount == 1) firstGate.await() else secondGate.await()
            }
        }
        val viewModel = InventoryViewModel(inventoryRepo, syncCoordinator)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.updateQuantity(3, 4)
        testDispatcher.scheduler.advanceTimeBy(250L)
        testDispatcher.scheduler.runCurrent()
        assertEquals(setOf(3), viewModel.uiState.value.inFlightQuantityIds)

        // Supersede while first call is in flight
        viewModel.updateQuantity(3, 5)
        testDispatcher.scheduler.runCurrent()

        assertEquals(5, viewModel.uiState.value.shoppingList.first { it.ingredientId == 3 }.quantity)
        assertEquals(setOf(3), viewModel.uiState.value.pendingQuantityIds)
        assertTrue(viewModel.uiState.value.inFlightQuantityIds.isEmpty())
        assertNull(viewModel.uiState.value.snackbarEvent)

        // Complete second debounce + request
        testDispatcher.scheduler.advanceTimeBy(250L)
        testDispatcher.scheduler.runCurrent()
        assertEquals(listOf(3 to 4, 3 to 5), inventoryRepo.updateQuantityCalls)

        secondGate.complete(Result.success(Unit))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.snackbarEvent)
        assertEquals(5, viewModel.uiState.value.shoppingList.first { it.ingredientId == 3 }.quantity)
    }

    @Test
    fun `converged optimistic overrides do not snap back when Room emission lags after success`() = runTest(testDispatcher) {
        val ingredients = sampleIngredients()
        val syncCoordinator = FakeBarSyncCoordinator()
        val inventoryRepo = FakeInventoryRepository(initialIngredients = ingredients)
        val viewModel = InventoryViewModel(inventoryRepo, syncCoordinator)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        // 1. Shelf toggle succeeds while Room emission lags
        val shelfGate = CompletableDeferred<Unit>()
        inventoryRepo.delayRoomEmissionGate = shelfGate
        viewModel.toggleBarShelf(4, true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.inFlightShelfIds.isEmpty())
        assertTrue(viewModel.uiState.value.allIngredients.first { it.id == 4 }.inBarShelf)

        shelfGate.complete(Unit)
        inventoryRepo.delayRoomEmissionGate = null
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.allIngredients.first { it.id == 4 }.inBarShelf)

        // 2. Quantity update succeeds while Room emission lags
        val qtyGate = CompletableDeferred<Unit>()
        inventoryRepo.delayRoomEmissionGate = qtyGate
        viewModel.updateQuantity(3, 4)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.inFlightQuantityIds.isEmpty())
        assertEquals(4, viewModel.uiState.value.shoppingList.first { it.ingredientId == 3 }.quantity)

        qtyGate.complete(Unit)
        inventoryRepo.delayRoomEmissionGate = null
        advanceUntilIdle()
        assertEquals(4, viewModel.uiState.value.shoppingList.first { it.ingredientId == 3 }.quantity)

        // 3. Shopping toggle / markPurchased succeeds while Room emission lags
        val purchaseGate = CompletableDeferred<Unit>()
        inventoryRepo.delayRoomEmissionGate = purchaseGate
        viewModel.markPurchased(3)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.inFlightPurchaseIds.isEmpty())
        assertTrue(viewModel.uiState.value.allIngredients.first { it.id == 3 }.inBarShelf)
        assertFalse(viewModel.uiState.value.allIngredients.first { it.id == 3 }.inShoppingList)
        assertTrue(viewModel.uiState.value.shoppingList.none { it.ingredientId == 3 })

        purchaseGate.complete(Unit)
        inventoryRepo.delayRoomEmissionGate = null
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.allIngredients.first { it.id == 3 }.inBarShelf)
        assertFalse(viewModel.uiState.value.allIngredients.first { it.id == 3 }.inShoppingList)

        // 4. Shopping toggle add succeeds while Room emission lags
        val addShoppingGate = CompletableDeferred<Unit>()
        inventoryRepo.delayRoomEmissionGate = addShoppingGate
        viewModel.toggleShoppingList(1, true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.inFlightShoppingIds.isEmpty())
        assertTrue(viewModel.uiState.value.allIngredients.first { it.id == 1 }.inShoppingList)

        addShoppingGate.complete(Unit)
        inventoryRepo.delayRoomEmissionGate = null
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.allIngredients.first { it.id == 1 }.inShoppingList)
    }

    @Test
    fun `failure paths emit snackbars, set recommendation errors, and roll back optimistic states`() = runTest(testDispatcher) {
        val ingredients = sampleIngredients()
        val syncCoordinator = FakeBarSyncCoordinator()
        val inventoryRepo = FakeInventoryRepository(initialIngredients = ingredients).apply {
            recommendedResult = Result.failure(IllegalStateException("Recommendations down"))
        }
        val viewModel = InventoryViewModel(inventoryRepo, syncCoordinator)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        assertEquals("Recommendations down", viewModel.uiState.value.recommendationsError)

        // toggleShoppingList failure rolls back
        inventoryRepo.addToShoppingListHook = { _, _ -> Result.failure(IllegalStateException("Add toggle failed")) }
        viewModel.toggleShoppingList(1, true)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.allIngredients.first { it.id == 1 }.inShoppingList)
        assertEquals("Add toggle failed", viewModel.uiState.value.snackbarMessage)

        // addToShoppingList failure rolls back
        inventoryRepo.addToShoppingListHook = { _, _ -> Result.failure(IllegalStateException("Add sheet failed")) }
        viewModel.addToShoppingList(4)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.allIngredients.first { it.id == 4 }.inShoppingList)
        assertEquals("Add sheet failed", viewModel.uiState.value.snackbarMessage)
        inventoryRepo.addToShoppingListHook = null

        // updateQuantity failure rolls back quantity
        inventoryRepo.updateQuantityHook = { _, _ -> Result.failure(IllegalStateException("Quantity failed")) }
        viewModel.updateQuantity(3, 5)
        advanceUntilIdle()
        assertEquals(1, viewModel.uiState.value.shoppingList.first { it.ingredientId == 3 }.quantity)
        assertEquals("Quantity failed", viewModel.uiState.value.snackbarMessage)
        inventoryRepo.updateQuantityHook = null

        // updateQuantity <= 0 failure keeps item on shopping list
        inventoryRepo.removeFromShoppingListHook = { Result.failure(IllegalStateException("Remove failed")) }
        viewModel.updateQuantity(3, 0)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.shoppingList.any { it.ingredientId == 3 })
        assertEquals("Remove failed", viewModel.uiState.value.snackbarMessage)

        // removeFromShoppingList failure rolls back
        viewModel.removeFromShoppingList(3)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.shoppingList.any { it.ingredientId == 3 })
        assertEquals("Remove failed", viewModel.uiState.value.snackbarMessage)

        // clearShoppingList failure
        viewModel.clearShoppingList()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isBulkUpdating)
        assertEquals("Remove failed", viewModel.uiState.value.snackbarMessage)
        inventoryRepo.removeFromShoppingListHook = null

        // markPurchased failure rolls back both shelf and shopping list
        inventoryRepo.markPurchasedHook = { Result.failure(IllegalStateException("Purchase failed")) }
        viewModel.markPurchased(3)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.allIngredients.first { it.id == 3 }.inBarShelf)
        assertTrue(viewModel.uiState.value.shoppingList.any { it.ingredientId == 3 })
        assertEquals("Purchase failed", viewModel.uiState.value.snackbarMessage)

        // markAllPurchased failure
        viewModel.markAllPurchased()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isBulkUpdating)
        assertEquals("Purchase failed", viewModel.uiState.value.snackbarMessage)
        inventoryRepo.markPurchasedHook = null

        // refreshAll syncAll failure
        syncCoordinator.syncAllResult = Result.failure(IllegalStateException("Server offline"))
        viewModel.refreshAll()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isRefreshing)
        assertEquals("Server offline", viewModel.uiState.value.snackbarMessage)
    }

    @Test
    fun `openUnlockedCocktails ignores stale and dismissed responses`() = runTest(testDispatcher) {
        val ingredients = sampleIngredients()
        val gate3 = CompletableDeferred<Result<List<UnlockedCocktail>>>()
        val gate4 = CompletableDeferred<Result<List<UnlockedCocktail>>>()
        val syncCoordinator = FakeBarSyncCoordinator()
        val inventoryRepo = FakeInventoryRepository(initialIngredients = ingredients).apply {
            getUnlockedCocktailsHook = { id ->
                if (id == 3) gate3.await() else gate4.await()
            }
        }
        val viewModel = InventoryViewModel(inventoryRepo, syncCoordinator)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        // Request 3, then supersede with 4 before 3 returns
        viewModel.openUnlockedCocktails(3, "Sweet Vermouth")
        testDispatcher.scheduler.runCurrent()
        viewModel.openUnlockedCocktails(4, "Fresh Lime Juice")
        testDispatcher.scheduler.runCurrent()

        gate4.complete(Result.success(listOf(UnlockedCocktail(201, "daiquiri", "Daiquiri"))))
        gate3.complete(Result.success(listOf(UnlockedCocktail(101, "negroni", "Negroni"))))
        advanceUntilIdle()

        val activeSheet = assertNotNull(viewModel.uiState.value.unlockedSheet)
        assertEquals(4, activeSheet.ingredientId)
        assertEquals(listOf("Daiquiri"), activeSheet.cocktails.map { it.name })

        // Dismiss while a request is in flight -> late response does not reopen sheet
        val lateGate = CompletableDeferred<Result<List<UnlockedCocktail>>>()
        inventoryRepo.getUnlockedCocktailsHook = { lateGate.await() }
        viewModel.openUnlockedCocktails(3, "Sweet Vermouth")
        testDispatcher.scheduler.runCurrent()
        viewModel.dismissUnlockedCocktails()
        lateGate.complete(Result.success(listOf(UnlockedCocktail(101, "negroni", "Negroni"))))
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.unlockedSheet)
    }

    @Test
    fun `empty catalog defers recommendations until populated and refreshAll executes syncAll then syncShoppingList sequentially`() = runTest(testDispatcher) {
        val syncCoordinator = FakeBarSyncCoordinator()
        val inventoryRepo = FakeInventoryRepository(initialIngredients = emptyList())
        val viewModel = InventoryViewModel(inventoryRepo, syncCoordinator)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        // Cold start with empty catalog does not fire premature recommendations load
        assertEquals(0, inventoryRepo.getRecommendedCalls)

        // Populating catalog triggers initial load once
        inventoryRepo.replaceCatalogForBar(sampleIngredients())
        advanceUntilIdle()
        assertEquals(1, inventoryRepo.getRecommendedCalls)

        // Emptying catalog resets initial load flag so re-populating loads again
        inventoryRepo.replaceCatalogForBar(emptyList())
        advanceUntilIdle()
        inventoryRepo.replaceCatalogForBar(sampleIngredients())
        advanceUntilIdle()
        assertEquals(2, inventoryRepo.getRecommendedCalls)

        // Sequential refreshAll verification
        val syncGate = CompletableDeferred<Result<Unit>>()
        val shoppingGate = CompletableDeferred<Result<Unit>>()
        syncCoordinator.syncAllHook = { syncGate.await() }
        inventoryRepo.syncShoppingListHook = { shoppingGate.await() }

        viewModel.refreshAll()
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, syncCoordinator.syncAllCalls)
        assertEquals(0, inventoryRepo.syncShoppingListCalls)
        assertEquals(2, inventoryRepo.getRecommendedCalls)

        syncGate.complete(Result.success(Unit))
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, inventoryRepo.syncShoppingListCalls)
        assertEquals(2, inventoryRepo.getRecommendedCalls)

        shoppingGate.complete(Result.success(Unit))
        advanceUntilIdle()

        assertEquals(3, inventoryRepo.getRecommendedCalls)
        assertFalse(viewModel.uiState.value.isRefreshing)

        // syncAll failure skips syncShoppingList and recommendations
        syncCoordinator.syncAllHook = { Result.failure(IllegalStateException("Auth expired")) }
        viewModel.refreshAll()
        advanceUntilIdle()

        assertEquals(1, inventoryRepo.syncShoppingListCalls)
        assertEquals(3, inventoryRepo.getRecommendedCalls)
        assertEquals("Auth expired", viewModel.uiState.value.snackbarMessage)
    }

    @Test
    fun `monotonic snackbar events emit distinct ids for identical messages and consumeSnackbar checks id`() = runTest(testDispatcher) {
        val ingredients = sampleIngredients()
        val syncCoordinator = FakeBarSyncCoordinator()
        val inventoryRepo = FakeInventoryRepository(initialIngredients = ingredients).apply {
            setInBarShelfHook = { _, _ -> Result.failure(IllegalStateException("Network error")) }
        }
        val viewModel = InventoryViewModel(inventoryRepo, syncCoordinator)

        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        viewModel.toggleBarShelf(3, true)
        advanceUntilIdle()
        val firstEvent = assertNotNull(viewModel.uiState.value.snackbarEvent)
        assertEquals("Network error", firstEvent.message)

        viewModel.toggleBarShelf(4, true)
        advanceUntilIdle()
        val secondEvent = assertNotNull(viewModel.uiState.value.snackbarEvent)
        assertEquals("Network error", secondEvent.message)
        assertNotEquals(firstEvent.id, secondEvent.id)
        assertTrue(secondEvent.id > firstEvent.id)

        // Consuming an older event id must not clear the newer event
        viewModel.consumeSnackbar(firstEvent.id)
        testDispatcher.scheduler.runCurrent()
        assertEquals(secondEvent, viewModel.uiState.value.snackbarEvent)

        viewModel.consumeSnackbar(secondEvent.id)
        testDispatcher.scheduler.runCurrent()
        assertNull(viewModel.uiState.value.snackbarEvent)
    }
}

private class FakeInventoryRepository(
    initialIngredients: List<IngredientSummary>,
) : InventoryRepository {
    private val catalogIngredients = MutableStateFlow(initialIngredients)
    private val shelfIds: MutableStateFlow<Set<Int>> = MutableStateFlow(
        initialIngredients.filter { it.inBarShelf }.mapTo(LinkedHashSet()) { it.id },
    )
    private val shoppingMap = MutableStateFlow(
        initialIngredients
            .filter { it.inShoppingList }
            .associate { item ->
                item.id to ShoppingListItem(
                    ingredientId = item.id,
                    ingredientSlug = item.slug,
                    ingredientName = item.name,
                    categoryName = item.categoryName,
                    quantity = 1,
                    mainImageThumbUrl = item.mainImageThumbUrl,
                )
            },
    )

    var delayRoomEmissionGate: CompletableDeferred<Unit>? = null
    var recommendedResult: Result<List<RecommendedIngredient>> = Result.success(emptyList())
    val unlockedCocktailsMap = mutableMapOf<Int, Result<List<UnlockedCocktail>>>()
    var syncShoppingListResult: Result<Unit> = Result.success(Unit)
    var setInBarShelfHook: (suspend (Int, Boolean) -> Result<Unit>)? = null
    var addToShoppingListHook: (suspend (List<Int>, Int) -> Result<Unit>)? = null
    var updateQuantityHook: (suspend (Int, Int) -> Result<Unit>)? = null
    var removeFromShoppingListHook: (suspend (List<Int>) -> Result<Unit>)? = null
    var markPurchasedHook: (suspend () -> Result<Unit>)? = null
    var syncShoppingListHook: (suspend () -> Result<Unit>)? = null
    var getUnlockedCocktailsHook: (suspend (Int) -> Result<List<UnlockedCocktail>>)? = null

    val setInBarShelfCalls = mutableListOf<Pair<List<Int>, Boolean>>()
    val updateQuantityCalls = mutableListOf<Pair<Int, Int>>()
    val removeFromShoppingListCalls = mutableListOf<List<Int>>()
    var getRecommendedCalls = 0
    var syncShoppingListCalls = 0
    var bulkMarkPurchasedCalls = 0

    fun replaceCatalogForBar(ingredients: List<IngredientSummary>) {
        catalogIngredients.value = ingredients
        shelfIds.value = ingredients.filter { it.inBarShelf }.mapTo(LinkedHashSet()) { it.id }
        shoppingMap.value = ingredients
            .filter { it.inShoppingList }
            .associate { item ->
                item.id to ShoppingListItem(
                    ingredientId = item.id,
                    ingredientSlug = item.slug,
                    ingredientName = item.name,
                    categoryName = item.categoryName,
                    quantity = 1,
                    mainImageThumbUrl = item.mainImageThumbUrl,
                )
            }
    }

    override fun observeBarShelfIngredients(): Flow<List<IngredientSummary>> =
        combine(catalogIngredients, shelfIds, shoppingMap) { catalog, shelf, shopping ->
            catalog.map { item ->
                item.copy(
                    inBarShelf = item.id in shelf,
                    inShoppingList = item.id in shopping,
                )
            }
        }.onEach {
            delayRoomEmissionGate?.await()
        }

    override fun observeShoppingList(): Flow<List<ShoppingListItem>> =
        shoppingMap.map { map ->
            map.values.sortedBy { it.ingredientName.lowercase() }
        }.onEach {
            delayRoomEmissionGate?.await()
        }

    override fun observeShoppingListIds(): Flow<Set<Int>> =
        shoppingMap.map { it.keys }

    override suspend fun syncShoppingList(): Result<Unit> {
        syncShoppingListCalls += 1
        syncShoppingListHook?.let { hook -> return hook() }
        return syncShoppingListResult
    }

    override suspend fun setInBarShelf(ingredientIds: List<Int>, inStock: Boolean): Result<Unit> {
        setInBarShelfCalls += ingredientIds to inStock
        val previousShelf = shelfIds.value
        shelfIds.update { current ->
            val updated = current.toMutableSet()
            if (inStock) updated.addAll(ingredientIds) else updated.removeAll(ingredientIds.toSet())
            updated
        }
        try {
            setInBarShelfHook?.let { hook ->
                val result = hook(ingredientIds.first(), inStock)
                if (result.isFailure) {
                    shelfIds.value = previousShelf
                    return result
                }
            }
        } catch (e: CancellationException) {
            shelfIds.value = previousShelf
            throw e
        }
        return Result.success(Unit)
    }

    override suspend fun addToShoppingList(ingredientIds: List<Int>, quantity: Int): Result<Unit> {
        val previousShopping = shoppingMap.value
        val byId = catalogIngredients.value.associateBy { it.id }
        val preserveExisting = quantity <= 1
        shoppingMap.update { current ->
            val updated = current.toMutableMap()
            ingredientIds.forEach { id ->
                val info = byId[id]
                val existing = updated[id]
                val resolvedQty = if (preserveExisting && existing != null && existing.quantity > 0) {
                    existing.quantity
                } else {
                    quantity.coerceAtLeast(1)
                }
                updated[id] = ShoppingListItem(
                    ingredientId = id,
                    ingredientSlug = info?.slug ?: existing?.ingredientSlug ?: "item-$id",
                    ingredientName = info?.name ?: existing?.ingredientName ?: "Ingredient $id",
                    categoryName = info?.categoryName ?: existing?.categoryName,
                    quantity = resolvedQty,
                    mainImageThumbUrl = info?.mainImageThumbUrl ?: existing?.mainImageThumbUrl,
                )
            }
            updated
        }
        try {
            addToShoppingListHook?.let { hook ->
                val result = hook(ingredientIds, quantity)
                if (result.isFailure) {
                    shoppingMap.value = previousShopping
                    return result
                }
            }
        } catch (e: CancellationException) {
            shoppingMap.value = previousShopping
            throw e
        }
        return Result.success(Unit)
    }

    override suspend fun updateShoppingListQuantity(ingredientId: Int, quantity: Int): Result<Unit> {
        if (quantity <= 0) return removeFromShoppingList(listOf(ingredientId))
        updateQuantityCalls += ingredientId to quantity
        val previousShopping = shoppingMap.value
        shoppingMap.update { current ->
            val existing = current[ingredientId] ?: return@update current
            current + (ingredientId to existing.copy(quantity = quantity))
        }
        try {
            updateQuantityHook?.let { hook ->
                val result = hook(ingredientId, quantity)
                if (result.isFailure) {
                    shoppingMap.value = previousShopping
                    return result
                }
            }
        } catch (e: CancellationException) {
            shoppingMap.value = previousShopping
            throw e
        }
        return Result.success(Unit)
    }

    override suspend fun removeFromShoppingList(ingredientIds: List<Int>): Result<Unit> {
        removeFromShoppingListCalls += ingredientIds
        val previousShopping = shoppingMap.value
        val removeSet = ingredientIds.toSet()
        shoppingMap.update { current ->
            current.filterKeys { it !in removeSet }
        }
        try {
            removeFromShoppingListHook?.let { hook ->
                val result = hook(ingredientIds)
                if (result.isFailure) {
                    shoppingMap.value = previousShopping
                    return result
                }
            }
        } catch (e: CancellationException) {
            shoppingMap.value = previousShopping
            throw e
        }
        return Result.success(Unit)
    }

    override suspend fun markPurchasedToBarShelf(ingredientIds: List<Int>): Result<Unit> {
        if (ingredientIds.size > 1 || markPurchasedHook != null) {
            bulkMarkPurchasedCalls += 1
        }
        val previousShelf = shelfIds.value
        val previousShopping = shoppingMap.value
        val moveSet = ingredientIds.toSet()
        shelfIds.update { it + moveSet }
        shoppingMap.update { current -> current.filterKeys { it !in moveSet } }
        try {
            markPurchasedHook?.let { hook ->
                val result = hook()
                if (result.isFailure) {
                    shelfIds.value = previousShelf
                    shoppingMap.value = previousShopping
                    return result
                }
            }
        } catch (e: CancellationException) {
            shelfIds.value = previousShelf
            shoppingMap.value = previousShopping
            throw e
        }
        return Result.success(Unit)
    }

    override suspend fun getRecommendedIngredients(): Result<List<RecommendedIngredient>> {
        getRecommendedCalls += 1
        val shoppingIds = shoppingMap.value.keys
        return recommendedResult.map { list ->
            list.map { it.copy(inShoppingList = it.id in shoppingIds) }
        }
    }

    override suspend fun getUnlockedCocktails(ingredientId: Int): Result<List<UnlockedCocktail>> {
        getUnlockedCocktailsHook?.let { hook -> return hook(ingredientId) }
        return unlockedCocktailsMap[ingredientId] ?: Result.success(emptyList())
    }
}

private class FakeBarSyncCoordinator : BarSyncCoordinator {
    private val _syncStatus = MutableStateFlow(SyncStatus())
    override val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()

    var syncAllCalls = 0
    var syncAllResult: Result<Unit> = Result.success(Unit)
    var syncAllHook: (suspend () -> Result<Unit>)? = null

    fun setSyncStatus(status: SyncStatus) {
        _syncStatus.value = status
    }

    override suspend fun syncAll(force: Boolean): Result<Unit> {
        syncAllCalls += 1
        syncAllHook?.let { hook -> return hook() }
        return syncAllResult
    }
}
