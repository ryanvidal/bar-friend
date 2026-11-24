package com.ryanvidal.barfriend.feature.inventory

import com.ryanvidal.barfriend.core.data.CocktailRepository
import com.ryanvidal.barfriend.core.data.InventoryRepository
import com.ryanvidal.barfriend.core.data.SyncStatus
import com.ryanvidal.barfriend.core.model.BarCatalog
import com.ryanvidal.barfriend.core.model.CocktailDetail
import com.ryanvidal.barfriend.core.model.CocktailFilter
import com.ryanvidal.barfriend.core.model.CocktailSummary
import com.ryanvidal.barfriend.core.model.CocktailUpsertRequest
import com.ryanvidal.barfriend.core.model.IngredientSummary
import com.ryanvidal.barfriend.core.model.RecommendedIngredient
import com.ryanvidal.barfriend.core.model.ScrapedCocktail
import com.ryanvidal.barfriend.core.model.ShoppingListItem
import com.ryanvidal.barfriend.core.model.UnlockedCocktail
import com.ryanvidal.barfriend.core.model.UploadedImage
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
            mainImageThumbUrl = null,
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
        val cocktailRepo = FakeCocktailRepository(initialCatalog = BarCatalog(ingredients = ingredients))
        val inventoryRepo = FakeInventoryRepository(initialIngredients = ingredients)
        val viewModel = InventoryViewModel(inventoryRepo, cocktailRepo)

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
        cocktailRepo.setCatalog(BarCatalog(ingredients = switchedIngredients))
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
        val cocktailRepo = FakeCocktailRepository(initialCatalog = BarCatalog(ingredients = ingredients))
        val inventoryRepo = FakeInventoryRepository(initialIngredients = ingredients).apply {
            recommendedResult = Result.success(
                listOf(
                    RecommendedIngredient(3, "sweet-vermouth", "Sweet Vermouth", 4, "Vermouth"),
                    RecommendedIngredient(4, "lime-juice", "Fresh Lime Juice", 2, "Citrus"),
                ),
            )
            setInBarShelfHook = { id, _ -> gates.getValue(id).await() }
        }
        val viewModel = InventoryViewModel(inventoryRepo, cocktailRepo)

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
        assertEquals("Network unavailable", viewModel.uiState.value.snackbarMessage)
        viewModel.consumeSnackbar()
        testDispatcher.scheduler.runCurrent()
        assertNull(viewModel.uiState.value.snackbarMessage)

        // Succeed 4 -> stays in bar shelf and triggers recommendations reload
        gates.getValue(4).complete(Result.success(Unit))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.inFlightShelfIds.isEmpty())
        assertTrue(viewModel.uiState.value.allIngredients.first { it.id == 4 }.inBarShelf)
        assertTrue(viewModel.uiState.value.recommendations.none { it.id == 4 })
        assertEquals(3, viewModel.uiState.value.inStockCount)
        assertEquals(2, inventoryRepo.getRecommendedCalls)

        // Toggling shopping list on a shelf item updates recommendation badge without reloading recommendations
        viewModel.toggleShoppingList(1)
        advanceUntilIdle()
        assertEquals(2, inventoryRepo.getRecommendedCalls)
    }

    @Test
    fun `shopping list add, quantity stepper with decrement-to-remove, remove, markPurchased, and bulk actions`() = runTest(testDispatcher) {
        val ingredients = sampleIngredients()
        val cocktailRepo = FakeCocktailRepository(initialCatalog = BarCatalog(ingredients = ingredients))
        val inventoryRepo = FakeInventoryRepository(initialIngredients = ingredients)
        val viewModel = InventoryViewModel(inventoryRepo, cocktailRepo)

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
        assertEquals("Moved Sweet Vermouth to Bar Shelf", viewModel.uiState.value.snackbarMessage)
        viewModel.consumeSnackbar()

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
        val cocktailRepo = FakeCocktailRepository(initialCatalog = BarCatalog(ingredients = ingredients))
        val inventoryRepo = FakeInventoryRepository(initialIngredients = ingredients).apply {
            unlockedCocktailsMap[3] = Result.success(
                listOf(
                    UnlockedCocktail(101, "negroni", "Negroni"),
                    UnlockedCocktail(102, "manhattan", "Manhattan"),
                ),
            )
            unlockedCocktailsMap[4] = Result.failure(IllegalStateException("Server timeout"))
        }
        val viewModel = InventoryViewModel(inventoryRepo, cocktailRepo)

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
        viewModel.toggleShoppingList(3)
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
        assertEquals(1, cocktailRepo.syncAllCalls)
        assertEquals(1, inventoryRepo.syncShoppingListCalls)
    }

    @Test
    fun `updateQuantity guards per-id re-entrancy while allowing concurrent updates on distinct ids`() = runTest(testDispatcher) {
        val ingredients = sampleIngredients()
        val gates = mapOf(
            3 to CompletableDeferred<Result<Unit>>(),
            4 to CompletableDeferred<Result<Unit>>(),
        )
        val cocktailRepo = FakeCocktailRepository(initialCatalog = BarCatalog(ingredients = ingredients))
        val inventoryRepo = FakeInventoryRepository(initialIngredients = ingredients).apply {
            updateQuantityHook = { id, _ -> gates.getValue(id).await() }
        }
        val viewModel = InventoryViewModel(inventoryRepo, cocktailRepo)

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

        assertEquals(setOf(3, 4), viewModel.uiState.value.inFlightShoppingIds)
        assertEquals(listOf(3 to 2, 4 to 5), inventoryRepo.updateQuantityCalls)

        gates.getValue(3).complete(Result.success(Unit))
        gates.getValue(4).complete(Result.success(Unit))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.inFlightShoppingIds.isEmpty())
        assertEquals(2, viewModel.uiState.value.shoppingList.first { it.ingredientId == 3 }.quantity)
        assertEquals(5, viewModel.uiState.value.shoppingList.first { it.ingredientId == 4 }.quantity)
    }
}

private class FakeInventoryRepository(
    initialIngredients: List<IngredientSummary>,
) : InventoryRepository {
    private val catalogIngredients = MutableStateFlow(initialIngredients)
    private val shelfIds: MutableStateFlow<Set<Int>> = MutableStateFlow(
        initialIngredients.filter { it.inBarShelf }.map { it.id }.toSet(),
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

    var recommendedResult: Result<List<RecommendedIngredient>> = Result.success(emptyList())
    val unlockedCocktailsMap = mutableMapOf<Int, Result<List<UnlockedCocktail>>>()
    var syncShoppingListResult: Result<Unit> = Result.success(Unit)
    var setInBarShelfHook: (suspend (Int, Boolean) -> Result<Unit>)? = null
    var updateQuantityHook: (suspend (Int, Int) -> Result<Unit>)? = null
    var markPurchasedHook: (suspend () -> Result<Unit>)? = null

    val setInBarShelfCalls = mutableListOf<Pair<List<Int>, Boolean>>()
    val updateQuantityCalls = mutableListOf<Pair<Int, Int>>()
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
            catalog.filter { it.id in shelf }.map { item ->
                item.copy(
                    inBarShelf = true,
                    inShoppingList = item.id in shopping,
                )
            }
        }

    override fun observeShoppingList(): Flow<List<ShoppingListItem>> =
        shoppingMap.map { map ->
            map.values.sortedBy { it.ingredientName.lowercase() }
        }

    override fun observeShoppingListIds(): Flow<Set<Int>> =
        shoppingMap.map { it.keys }

    override suspend fun syncShoppingList(): Result<Unit> {
        syncShoppingListCalls += 1
        return syncShoppingListResult
    }

    override suspend fun setInBarShelf(ingredientIds: List<Int>, inStock: Boolean): Result<Unit> {
        setInBarShelfCalls += ingredientIds to inStock
        setInBarShelfHook?.let { hook ->
            val result = hook(ingredientIds.first(), inStock)
            if (result.isFailure) return result
        }
        shelfIds.update { current ->
            val updated = current.toMutableSet()
            if (inStock) updated.addAll(ingredientIds) else updated.removeAll(ingredientIds.toSet())
            updated
        }
        return Result.success(Unit)
    }

    override suspend fun addToShoppingList(ingredientIds: List<Int>, quantity: Int): Result<Unit> {
        val byId = catalogIngredients.value.associateBy { it.id }
        shoppingMap.update { current ->
            val updated = current.toMutableMap()
            ingredientIds.forEach { id ->
                val info = byId[id]
                val existing = updated[id]
                updated[id] = ShoppingListItem(
                    ingredientId = id,
                    ingredientSlug = info?.slug ?: existing?.ingredientSlug ?: "item-$id",
                    ingredientName = info?.name ?: existing?.ingredientName ?: "Ingredient $id",
                    categoryName = info?.categoryName ?: existing?.categoryName,
                    quantity = quantity.coerceAtLeast(1),
                    mainImageThumbUrl = info?.mainImageThumbUrl ?: existing?.mainImageThumbUrl,
                )
            }
            updated
        }
        return Result.success(Unit)
    }

    override suspend fun updateShoppingListQuantity(ingredientId: Int, quantity: Int): Result<Unit> {
        updateQuantityCalls += ingredientId to quantity
        updateQuantityHook?.let { hook ->
            val result = hook(ingredientId, quantity)
            if (result.isFailure) return result
        }
        shoppingMap.update { current ->
            val existing = current[ingredientId] ?: return@update current
            current + (ingredientId to existing.copy(quantity = quantity))
        }
        return Result.success(Unit)
    }

    override suspend fun removeFromShoppingList(ingredientIds: List<Int>): Result<Unit> {
        val removeSet = ingredientIds.toSet()
        shoppingMap.update { current ->
            current.filterKeys { it !in removeSet }
        }
        return Result.success(Unit)
    }

    override suspend fun markPurchasedToBarShelf(ingredientIds: List<Int>): Result<Unit> {
        if (ingredientIds.size > 1 || markPurchasedHook != null) {
            bulkMarkPurchasedCalls += 1
        }
        markPurchasedHook?.let { hook ->
            val result = hook()
            if (result.isFailure) return result
        }
        val moveSet = ingredientIds.toSet()
        shelfIds.update { it + moveSet }
        shoppingMap.update { current -> current.filterKeys { it !in moveSet } }
        return Result.success(Unit)
    }

    override suspend fun getRecommendedIngredients(): Result<List<RecommendedIngredient>> {
        getRecommendedCalls += 1
        val shoppingIds = shoppingMap.value.keys
        return recommendedResult.map { list ->
            list.map { it.copy(inShoppingList = it.id in shoppingIds) }
        }
    }

    override suspend fun getUnlockedCocktails(ingredientId: Int): Result<List<UnlockedCocktail>> =
        unlockedCocktailsMap[ingredientId] ?: Result.success(emptyList())
}

private class FakeCocktailRepository(
    initialCatalog: BarCatalog = BarCatalog(),
) : CocktailRepository {
    private val _syncStatus = MutableStateFlow(SyncStatus())
    override val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()
    private val catalogFlow = MutableStateFlow(initialCatalog)

    var syncAllCalls = 0

    fun setCatalog(catalog: BarCatalog) {
        catalogFlow.value = catalog
    }

    override fun observeCocktails(filter: CocktailFilter): Flow<List<CocktailSummary>> =
        flowOf(emptyList())

    override fun observeCocktailDetail(cocktailId: Int): Flow<CocktailDetail?> =
        flowOf(null)

    override fun observeCatalog(): Flow<BarCatalog> = catalogFlow

    override suspend fun syncAll(force: Boolean): Result<Unit> {
        syncAllCalls += 1
        return Result.success(Unit)
    }

    override suspend fun refreshCocktail(cocktailId: Int): Result<CocktailDetail> =
        Result.failure(UnsupportedOperationException())

    override suspend fun toggleFavorite(cocktailId: Int): Result<Boolean> =
        Result.failure(UnsupportedOperationException())

    override suspend fun saveCocktail(
        cocktailId: Int?,
        request: CocktailUpsertRequest,
    ): Result<CocktailDetail> = Result.failure(UnsupportedOperationException())

    override suspend fun deleteCocktail(cocktailId: Int): Result<Unit> =
        Result.failure(UnsupportedOperationException())

    override suspend fun uploadImage(
        bytes: ByteArray,
        fileName: String,
        mimeType: String?,
        sort: Int,
    ): Result<UploadedImage> = Result.failure(UnsupportedOperationException())

    override suspend fun uploadImageFromUrl(
        imageUrl: String,
        copyright: String?,
        sort: Int,
    ): Result<UploadedImage> = Result.failure(UnsupportedOperationException())

    override suspend fun scrapeRecipeFromUrl(url: String): Result<ScrapedCocktail> =
        Result.failure(UnsupportedOperationException())
}
