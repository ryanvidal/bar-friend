package com.ryanvidal.barfriend.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.ryanvidal.barfriend.core.database.dao.CatalogDao
import com.ryanvidal.barfriend.core.database.dao.CocktailAvailabilityUpdate
import com.ryanvidal.barfriend.core.database.dao.CocktailDao
import com.ryanvidal.barfriend.core.database.dao.CocktailIngredientAvailabilityUpdate
import com.ryanvidal.barfriend.core.database.dao.CocktailWithIngredients
import com.ryanvidal.barfriend.core.database.dao.InventoryDao
import com.ryanvidal.barfriend.core.database.dao.SyncDao
import com.ryanvidal.barfriend.core.database.entity.BarShelfEntity
import com.ryanvidal.barfriend.core.database.entity.CocktailEntity
import com.ryanvidal.barfriend.core.database.entity.CocktailIngredientEntity
import com.ryanvidal.barfriend.core.database.entity.CocktailMethodEntity
import com.ryanvidal.barfriend.core.database.entity.GlassEntity
import com.ryanvidal.barfriend.core.database.entity.IngredientEntity
import com.ryanvidal.barfriend.core.database.entity.ShoppingListEntity
import com.ryanvidal.barfriend.core.database.entity.SyncMetadataEntity
import com.ryanvidal.barfriend.core.database.entity.TagEntity
import com.ryanvidal.barfriend.core.database.entity.UtensilEntity
import com.ryanvidal.barfriend.core.model.BarCatalog
import com.ryanvidal.barfriend.core.model.CocktailDetail
import com.ryanvidal.barfriend.core.model.CocktailFilter
import com.ryanvidal.barfriend.core.model.CocktailSummary
import com.ryanvidal.barfriend.core.model.CocktailUpsertRequest
import com.ryanvidal.barfriend.core.model.RecommendedIngredient
import com.ryanvidal.barfriend.core.model.ScrapedCocktail
import com.ryanvidal.barfriend.core.model.UnlockedCocktail
import com.ryanvidal.barfriend.core.model.UploadedImage
import com.ryanvidal.barfriend.core.network.BarAssistantApi
import com.ryanvidal.barfriend.core.network.BarAssistantApiException
import com.ryanvidal.barfriend.core.network.dto.BarDto
import com.ryanvidal.barfriend.core.network.dto.CocktailBasicDto
import com.ryanvidal.barfriend.core.network.dto.CocktailDto
import com.ryanvidal.barfriend.core.network.dto.CocktailIngredientDto
import com.ryanvidal.barfriend.core.network.dto.CocktailIngredientSubstituteDto
import com.ryanvidal.barfriend.core.network.dto.CocktailMethodDto
import com.ryanvidal.barfriend.core.network.dto.CocktailWriteRequestDto
import com.ryanvidal.barfriend.core.network.dto.GlassDto
import com.ryanvidal.barfriend.core.network.dto.ImageDto
import com.ryanvidal.barfriend.core.network.dto.IngredientBasicDto
import com.ryanvidal.barfriend.core.network.dto.IngredientDto
import com.ryanvidal.barfriend.core.network.dto.IngredientRecommendDto
import com.ryanvidal.barfriend.core.network.dto.PaginatedEnvelope
import com.ryanvidal.barfriend.core.network.dto.ProfileDto
import com.ryanvidal.barfriend.core.network.dto.ScrapeResponseDto
import com.ryanvidal.barfriend.core.network.dto.ServerVersionDto
import com.ryanvidal.barfriend.core.network.dto.ShoppingListIngredientInputDto
import com.ryanvidal.barfriend.core.network.dto.ShoppingListItemDto
import com.ryanvidal.barfriend.core.network.dto.TagDto
import com.ryanvidal.barfriend.core.network.dto.TokenDto
import com.ryanvidal.barfriend.core.network.dto.UtensilDto
import com.ryanvidal.barfriend.core.platform.SecureTokenStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InventoryAvailabilityAndRepositoryTest {

    @Test
    fun `recalculateCocktailAvailability updates shelf, substitute, optional, variant, and complex flags`() {
        val negroniDto = CocktailDto(
            id = 1,
            slug = "negroni",
            name = "Negroni",
            instructions = "Stir.",
            ingredients = listOf(
                CocktailIngredientDto(
                    sort = 1,
                    amount = 30.0,
                    units = "ml",
                    ingredient = IngredientBasicDto(1, "gin", "Gin"),
                    inBarShelf = false,
                ),
                CocktailIngredientDto(
                    sort = 2,
                    amount = 30.0,
                    units = "ml",
                    ingredient = IngredientBasicDto(2, "sweet-vermouth", "Sweet Vermouth"),
                    inBarShelf = false,
                    substitutes = listOf(
                        CocktailIngredientSubstituteDto(
                            ingredient = IngredientBasicDto(20, "punt-e-mes", "Punt e Mes"),
                            inBarShelf = false,
                        ),
                    ),
                ),
                CocktailIngredientDto(
                    sort = 3,
                    amount = 30.0,
                    units = "ml",
                    ingredient = IngredientBasicDto(3, "campari", "Campari"),
                    inBarShelf = false,
                    inBarShelfAsVariant = true,
                ),
                CocktailIngredientDto(
                    sort = 4,
                    amount = 1.0,
                    units = "dash",
                    optional = true,
                    ingredient = IngredientBasicDto(4, "orange-bitters", "Orange Bitters"),
                    inBarShelf = false,
                ),
            ),
        )
        val daiquiriDto = CocktailDto(
            id = 2,
            slug = "daiquiri",
            name = "Daiquiri",
            instructions = "Shake.",
            ingredients = listOf(
                CocktailIngredientDto(
                    sort = 1,
                    amount = 60.0,
                    units = "ml",
                    ingredient = IngredientBasicDto(5, "rum", "White Rum"),
                    inBarShelf = false,
                ),
                CocktailIngredientDto(
                    sort = 2,
                    amount = 30.0,
                    units = "ml",
                    ingredient = IngredientBasicDto(6, "lime", "Lime Juice"),
                    inBarShelfAsComplexIngredient = true,
                ),
            ),
        )

        val initial = listOf(negroniDto, daiquiriDto).map { dto ->
            val (entity, ings) = dto.toEntityAndIngredients(barId = 7)
            CocktailWithIngredients(entity, ings)
        }

        val firstRecalc = recalculateCocktailAvailability(
            items = initial,
            shelfIngredientIds = setOf(1, 20),
        )
        val afterFirst = applyAvailabilityRecalculation(initial, firstRecalc)

        val negroni = afterFirst.first { it.cocktail.id == 1 }
        assertTrue(negroni.cocktail.inBarShelf)
        assertEquals(0, negroni.cocktail.missingIngredientsCount)
        assertEquals("[]", negroni.cocktail.missingIngredientNamesJson)

        val negroniIngredients = negroni.ingredients.sortedBy { it.sort }
        assertTrue(negroniIngredients[0].inBarShelf)
        assertFalse(negroniIngredients[1].inBarShelf)
        assertTrue(negroniIngredients[1].inBarShelfAsSubstitute)
        assertTrue(negroniIngredients[1].toModel().substitutes.single().inBarShelf)
        assertTrue(negroniIngredients[2].inBarShelfAsVariant)
        assertFalse(negroniIngredients[3].inBarShelf)

        val daiquiri = afterFirst.first { it.cocktail.id == 2 }
        assertFalse(daiquiri.cocktail.inBarShelf)
        assertEquals(1, daiquiri.cocktail.missingIngredientsCount)
        assertEquals("""["White Rum"]""", daiquiri.cocktail.missingIngredientNamesJson)
        assertTrue(daiquiri.ingredients.first { it.ingredientId == 6 }.inBarShelfAsComplex)

        val revertRecalc = recalculateCocktailAvailability(
            items = listOf(negroni),
            shelfIngredientIds = setOf(1),
        )
        val afterRevert = applyAvailabilityRecalculation(listOf(negroni), revertRecalc)
        val revertedNegroni = afterRevert.single()
        assertFalse(revertedNegroni.cocktail.inBarShelf)
        assertEquals(1, revertedNegroni.cocktail.missingIngredientsCount)
        assertEquals("""["Sweet Vermouth"]""", revertedNegroni.cocktail.missingIngredientNamesJson)
        val vermouth = revertedNegroni.ingredients.first { it.ingredientId == 2 }
        assertFalse(vermouth.inBarShelfAsSubstitute)
        assertFalse(vermouth.toModel().substitutes.single().inBarShelf)

        // Variant matching via catalog parentIngredientId, isSpecified protection, empty shelf clearing,
        // all-optional exclusion, and preserving unaffected list-synced inBarShelf cocktails
        val catalog = listOf(
            IngredientEntity(1, 7, "gin", "Gin", 40.0, null, null, null, null, null),
            IngredientEntity(101, 7, "beefeater-gin", "Beefeater Gin", 40.0, null, null, null, parentIngredientId = 1, null),
            IngredientEntity(99, 7, "water", "Water", 0.0, null, null, null, null, null),
        )
        val variantCocktail = CocktailDto(
            id = 3,
            slug = "gimlet",
            name = "Gin Cocktail",
            instructions = "Stir.",
            ingredients = listOf(
                CocktailIngredientDto(sort = 1, amount = 60.0, units = "ml", ingredient = IngredientBasicDto(1, "gin", "Gin"), isSpecified = false),
            ),
        ).let { CocktailWithIngredients(it.toEntityAndIngredients(7).first, it.toEntityAndIngredients(7).second) }
        val specifiedCocktail = CocktailDto(
            id = 4,
            slug = "specific-gin",
            name = "Specific Gin Drink",
            instructions = "Stir.",
            ingredients = listOf(
                CocktailIngredientDto(sort = 1, amount = 60.0, units = "ml", ingredient = IngredientBasicDto(1, "gin", "Gin"), isSpecified = true),
            ),
        ).let { CocktailWithIngredients(it.toEntityAndIngredients(7).first, it.toEntityAndIngredients(7).second) }
        val optionalOnlyCocktail = CocktailDto(
            id = 5,
            slug = "bitters-splash",
            name = "Bitters Only",
            instructions = "Dash.",
            ingredients = listOf(
                CocktailIngredientDto(sort = 1, amount = 1.0, units = "dash", optional = true, ingredient = IngredientBasicDto(4, "orange-bitters", "Orange Bitters")),
            ),
        ).let { CocktailWithIngredients(it.toEntityAndIngredients(7).first, it.toEntityAndIngredients(7).second) }
        val listSyncedSubCocktail = CocktailDto(
            id = 6,
            slug = "list-sub",
            name = "List Synced Sub",
            instructions = "Stir.",
            inBarShelf = true,
            ingredients = listOf(
                CocktailIngredientDto(sort = 1, amount = 60.0, units = "ml", ingredient = IngredientBasicDto(5, "rum", "White Rum"), inBarShelf = false),
            ),
        ).let { CocktailWithIngredients(it.toEntityAndIngredients(7).first, it.toEntityAndIngredients(7).second) }

        val hierarchyItems = listOf(variantCocktail, specifiedCocktail, optionalOnlyCocktail, listSyncedSubCocktail, daiquiri)
        val hierarchyRecalc = recalculateCocktailAvailability(
            items = hierarchyItems,
            shelfIngredientIds = setOf(101, 4),
            catalogIngredients = catalog,
            toggledIngredientIds = setOf(101),
        )
        val afterHierarchy = applyAvailabilityRecalculation(hierarchyItems, hierarchyRecalc)
        assertTrue(afterHierarchy.first { it.cocktail.id == 3 }.cocktail.inBarShelf)
        assertTrue(afterHierarchy.first { it.cocktail.id == 3 }.ingredients.single().inBarShelfAsVariant)
        assertFalse(afterHierarchy.first { it.cocktail.id == 4 }.cocktail.inBarShelf)
        assertFalse(afterHierarchy.first { it.cocktail.id == 4 }.ingredients.single().inBarShelfAsVariant)
        assertFalse(afterHierarchy.first { it.cocktail.id == 5 }.cocktail.inBarShelf)
        assertTrue(afterHierarchy.first { it.cocktail.id == 6 }.cocktail.inBarShelf)

        // Emptying shelf clears variant and complex flags completely
        val emptyShelfRecalc = recalculateCocktailAvailability(
            items = afterHierarchy,
            shelfIngredientIds = emptySet(),
            catalogIngredients = catalog,
        )
        val afterEmpty = applyAvailabilityRecalculation(afterHierarchy, emptyShelfRecalc)
        assertFalse(afterEmpty.first { it.cocktail.id == 3 }.ingredients.single().inBarShelfAsVariant)
        assertFalse(afterEmpty.first { it.cocktail.id == 3 }.cocktail.inBarShelf)
        assertFalse(afterEmpty.first { it.cocktail.id == 2 }.ingredients.first { it.ingredientId == 6 }.inBarShelfAsComplex)
    }

    @Test
    fun `setInBarShelf updates optimistically, triggers background sync on success, and rolls back on failure or cancellation`() = runTest {
        val db = FakeBarDatabase()
        db.seedIngredient(barId = 7, id = 1, slug = "gin", name = "Gin")
        db.seedCocktail(
            barId = 7,
            CocktailDto(
                id = 10,
                slug = "gin-tonic",
                name = "Gin & Tonic",
                instructions = "Build.",
                ingredients = listOf(
                    CocktailIngredientDto(
                        sort = 1,
                        amount = 60.0,
                        units = "ml",
                        ingredient = IngredientBasicDto(1, "gin", "Gin"),
                        inBarShelf = false,
                    ),
                ),
            ),
        )

        var sawOptimisticBeforeApiReturn = false
        val api = FakeBarAssistantApi().apply {
            onAddBarInventory = { _, _ ->
                sawOptimisticBeforeApiReturn = 1 in db.inventoryDao.getBarShelfIngredientIds(7) &&
                    db.cocktailDao.getCocktailsWithIngredientsForBar(7).single().cocktail.inBarShelf
            }
        }
        val cocktailRepo = FakeCocktailRepository()
        val sessionRepo = FakeSessionRepository(
            SessionState.Authenticated("https://bar.example.com", activeBarId = 7, activeBarName = "Home"),
        )
        val repo = DefaultInventoryRepository(
            api = api,
            sessionRepository = sessionRepo,
            cocktailRepository = cocktailRepo,
            cocktailDao = db.cocktailDao,
            inventoryDao = db.inventoryDao,
            catalogDao = db.catalogDao,
            syncDao = db.syncDao,
            mutationCoordinator = BarMutationCoordinator(),
        )

        val addResult = repo.setInBarShelf(listOf(1, 1, -5), inStock = true)
        assertTrue(addResult.isSuccess)
        assertTrue(sawOptimisticBeforeApiReturn)
        assertEquals(listOf(7 to listOf(1)), api.addedBarInventoryCalls)
        assertEquals(listOf(1), db.inventoryDao.getBarShelfIngredientIds(7))
        assertTrue(db.cocktailDao.getCocktailsWithIngredientsForBar(7).single().cocktail.inBarShelf)
        assertTrue(repo.observeBarShelfIngredients().first().single { it.id == 1 }.inBarShelf)
        repo.lastBackgroundSyncJob?.join()
        assertEquals(listOf(true), cocktailRepo.silentSyncForceCalls)

        api.onRemoveBarInventory = { _, _ ->
            assertFalse(1 in db.inventoryDao.getBarShelfIngredientIds(7))
            assertFalse(db.cocktailDao.getCocktailsWithIngredientsForBar(7).single().cocktail.inBarShelf)
            throw IllegalStateException("Server offline")
        }
        val removeFail = repo.setInBarShelf(listOf(1), inStock = false)
        assertTrue(removeFail.isFailure)
        assertEquals(listOf(1), db.inventoryDao.getBarShelfIngredientIds(7))
        assertTrue(db.cocktailDao.getCocktailsWithIngredientsForBar(7).single().cocktail.inBarShelf)

        api.onRemoveBarInventory = { _, _ ->
            throw CancellationException("Cancelled")
        }
        assertFailsWith<CancellationException> {
            repo.setInBarShelf(listOf(1), inStock = false)
        }
        assertEquals(listOf(1), db.inventoryDao.getBarShelfIngredientIds(7))
        assertTrue(db.cocktailDao.getCocktailsWithIngredientsForBar(7).single().cocktail.inBarShelf)
    }

    @Test
    fun `shopping list lazily resolves userId once, preserves quantity on add, handles decrement to zero, and rolls back errors`() = runTest {
        val db = FakeBarDatabase()
        db.seedIngredient(barId = 7, id = 10, slug = "gin", name = "London Dry Gin", categoryName = "Spirit")
        db.seedIngredient(barId = 7, id = 20, slug = "campari", name = "Campari", categoryName = "Liqueur")
        val api = FakeBarAssistantApi(profile = ProfileDto(id = 42, name = "Ryan", email = "ryan@example.com"))
        val sessionRepo = FakeSessionRepository(
            SessionState.Authenticated("https://bar.example.com", activeBarId = 7, activeBarName = "Home"),
        )
        val repo = DefaultInventoryRepository(
            api = api,
            sessionRepository = sessionRepo,
            cocktailRepository = FakeCocktailRepository(),
            cocktailDao = db.cocktailDao,
            inventoryDao = db.inventoryDao,
            catalogDao = db.catalogDao,
            syncDao = db.syncDao,
            mutationCoordinator = BarMutationCoordinator(),
        )

        assertTrue(repo.addToShoppingList(listOf(20), quantity = 3).isSuccess)
        assertEquals(1, api.getProfileCallCount)
        assertEquals(42, sessionRepo.currentUserId())
        assertEquals(
            listOf(42 to listOf(ShoppingListIngredientInputDto(20, 3))),
            api.addedShoppingListCalls,
        )

        // Adding with default quantity = 1 preserves existing quantity 3 and reuses cached userId
        assertTrue(repo.addToShoppingList(listOf(20, 10)).isSuccess)
        assertEquals(1, api.getProfileCallCount)
        assertEquals(
            listOf(ShoppingListIngredientInputDto(20, 3), ShoppingListIngredientInputDto(10, 1)),
            api.addedShoppingListCalls.last().second,
        )
        assertEquals(setOf(10, 20), repo.observeShoppingListIds().first())
        assertEquals(
            listOf("Campari" to 3, "London Dry Gin" to 1),
            repo.observeShoppingList().first().map { it.ingredientName to it.quantity },
        )

        // Explicit quantity update can decrement down to 1
        assertTrue(repo.updateShoppingListQuantity(20, 1).isSuccess)
        assertEquals(1, db.inventoryDao.getShoppingListSnapshot(7).first { it.ingredientId == 20 }.quantity)

        // Quantity <= 0 deletes item
        assertTrue(repo.updateShoppingListQuantity(20, 0).isSuccess)
        assertEquals(listOf(42 to listOf(20)), api.removedShoppingListCalls)
        assertEquals(setOf(10), repo.observeShoppingListIds().first())

        // Failed update rolls back quantity
        api.onAddShoppingList = { _, _ -> throw IllegalStateException("Write failed") }
        val failedUpdate = repo.updateShoppingListQuantity(10, 5)
        assertTrue(failedUpdate.isFailure)
        assertEquals(1, db.inventoryDao.getShoppingListSnapshot(7).single().quantity)
    }

    @Test
    fun `markPurchasedToBarShelf updates shelf and shopping list and keeps shelf if shopping list removal fails`() = runTest {
        val db = FakeBarDatabase()
        db.seedIngredient(barId = 7, id = 1, slug = "gin", name = "Gin")
        db.seedCocktail(
            barId = 7,
            CocktailDto(
                id = 10,
                slug = "gin-tonic",
                name = "Gin & Tonic",
                instructions = "Build.",
                ingredients = listOf(
                    CocktailIngredientDto(
                        sort = 1,
                        amount = 60.0,
                        units = "ml",
                        ingredient = IngredientBasicDto(1, "gin", "Gin"),
                        inBarShelf = false,
                    ),
                ),
            ),
        )
        db.inventoryDao.upsertShoppingListItems(listOf(ShoppingListEntity(barId = 7, ingredientId = 1, quantity = 2)))

        val api = FakeBarAssistantApi(profile = ProfileDto(id = 42, name = "Ryan", email = "ryan@example.com"))
        val cocktailRepo = FakeCocktailRepository()
        val sessionRepo = FakeSessionRepository(
            SessionState.Authenticated("https://bar.example.com", activeBarId = 7, activeBarName = "Home"),
        )
        val repo = DefaultInventoryRepository(
            api = api,
            sessionRepository = sessionRepo,
            cocktailRepository = cocktailRepo,
            cocktailDao = db.cocktailDao,
            inventoryDao = db.inventoryDao,
            catalogDao = db.catalogDao,
            syncDao = db.syncDao,
            mutationCoordinator = BarMutationCoordinator(),
        )

        // Partial failure: shelf add succeeds on server, shopping list removal fails
        api.onRemoveShoppingList = { _, _ -> throw IllegalStateException("Shopping list API error") }
        val partialFail = repo.markPurchasedToBarShelf(listOf(1))
        assertTrue(partialFail.isFailure)
        assertEquals(listOf(1), db.inventoryDao.getBarShelfIngredientIds(7))
        assertTrue(db.cocktailDao.getCocktailsWithIngredientsForBar(7).single().cocktail.inBarShelf)
        assertEquals(listOf(ShoppingListEntity(barId = 7, ingredientId = 1, quantity = 2)), db.inventoryDao.getShoppingListSnapshot(7))
        repo.lastBackgroundSyncJob?.join()
        assertEquals(listOf(true), cocktailRepo.silentSyncForceCalls)

        // Full success flow
        api.onRemoveShoppingList = { _, _ -> }
        val success = repo.markPurchasedToBarShelf(listOf(1))
        assertTrue(success.isSuccess)
        assertEquals(listOf(1), db.inventoryDao.getBarShelfIngredientIds(7))
        assertTrue(db.inventoryDao.getShoppingListSnapshot(7).isEmpty())
        repo.lastBackgroundSyncJob?.join()
        assertEquals(listOf(true, true), cocktailRepo.silentSyncForceCalls)
    }

    @Test
    fun `getRecommendedIngredients and getUnlockedCocktails enrich catalog metadata and live shopping list flags`() = runTest {
        val db = FakeBarDatabase()
        db.seedIngredient(
            barId = 7,
            id = 5,
            slug = "chartreuse",
            name = "Green Chartreuse",
            categoryName = "Liqueur",
            thumbUrl = "https://bar.example.com/chartreuse.jpg",
        )
        db.inventoryDao.upsertShoppingListItems(listOf(ShoppingListEntity(barId = 7, ingredientId = 5, quantity = 1)))

        val api = FakeBarAssistantApi(
            recommendedIngredients = listOf(
                IngredientRecommendDto(5, "chartreuse", "Green Chartreuse", potentialCocktails = 9),
                IngredientRecommendDto(6, "maraschino", "Maraschino", potentialCocktails = 4),
            ),
            extraCocktails = mapOf(
                5 to listOf(CocktailBasicDto(101, "last-word", "Last Word")),
            ),
        )
        val repo = DefaultInventoryRepository(
            api = api,
            sessionRepository = FakeSessionRepository(
                SessionState.Authenticated("https://bar.example.com", activeBarId = 7, activeBarName = "Home"),
            ),
            cocktailRepository = FakeCocktailRepository(),
            cocktailDao = db.cocktailDao,
            inventoryDao = db.inventoryDao,
            catalogDao = db.catalogDao,
            syncDao = db.syncDao,
            mutationCoordinator = BarMutationCoordinator(),
        )

        val recommended = repo.getRecommendedIngredients().getOrThrow()
        assertEquals(
            listOf(
                RecommendedIngredient(
                    id = 5,
                    slug = "chartreuse",
                    name = "Green Chartreuse",
                    potentialCocktails = 9,
                    categoryName = "Liqueur",
                    mainImageThumbUrl = "https://bar.example.com/chartreuse.jpg",
                    inShoppingList = true,
                ),
                RecommendedIngredient(
                    id = 6,
                    slug = "maraschino",
                    name = "Maraschino",
                    potentialCocktails = 4,
                    categoryName = null,
                    mainImageThumbUrl = null,
                    inShoppingList = false,
                ),
            ),
            recommended,
        )

        val unlocked = repo.getUnlockedCocktails(5).getOrThrow()
        assertEquals(listOf(UnlockedCocktail(101, "last-word", "Last Word")), unlocked)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `getRecommendedIngredients waits for in-flight bar shelf mutation before querying server`() = runTest {
        val db = FakeBarDatabase()
        db.seedIngredient(barId = 7, id = 5, slug = "chartreuse", name = "Green Chartreuse", categoryName = "Liqueur")
        val shelfAddStarted = CompletableDeferred<Unit>()
        val allowShelfAddFinish = CompletableDeferred<Unit>()
        var recommendCalls = 0
        var serverHasBottle5 = false

        val api = FakeBarAssistantApi().apply {
            onAddBarInventory = { _, ids ->
                shelfAddStarted.complete(Unit)
                allowShelfAddFinish.await()
                if (5 in ids) serverHasBottle5 = true
            }
            onGetRecommendedBarIngredients = { _ ->
                recommendCalls += 1
                if (serverHasBottle5) {
                    emptyList()
                } else {
                    listOf(IngredientRecommendDto(5, "chartreuse", "Green Chartreuse", potentialCocktails = 9))
                }
            }
        }
        val repo = DefaultInventoryRepository(
            api = api,
            sessionRepository = FakeSessionRepository(
                SessionState.Authenticated("https://bar.example.com", activeBarId = 7, activeBarName = "Home"),
            ),
            cocktailRepository = FakeCocktailRepository(),
            cocktailDao = db.cocktailDao,
            inventoryDao = db.inventoryDao,
            catalogDao = db.catalogDao,
            syncDao = db.syncDao,
            mutationCoordinator = BarMutationCoordinator(),
        )

        val addDeferred = async { repo.setInBarShelf(listOf(5), inStock = true) }
        shelfAddStarted.await()
        assertTrue(5 in db.inventoryDao.getBarShelfIngredientIds(7))

        val recommendDeferred = async { repo.getRecommendedIngredients() }
        runCurrent()
        assertEquals(0, recommendCalls)

        allowShelfAddFinish.complete(Unit)
        assertTrue(addDeferred.await().isSuccess)
        val recommended = recommendDeferred.await().getOrThrow()
        assertEquals(1, recommendCalls)
        assertTrue(recommended.isEmpty())
        repo.lastBackgroundSyncJob?.join()
    }

    @Test
    fun `DefaultSessionRepository and DefaultAuthRepository persist and clear activeUserId across restarts and server switches`() = runTest {
        val dataStore = FakePreferencesDataStore()
        val preferences = AppPreferences(dataStore)
        val tokenStore = FakeTokenStore()
        val syncDao = FakeSyncDao(FakeBarDatabase())
        val sessionRepo = DefaultSessionRepository(
            tokenStore = tokenStore,
            appPreferences = preferences,
            syncDao = syncDao,
            imageCacheCleaner = lazy { ImageCacheCleaner {} },
        )

        val authRepo = DefaultAuthRepository(
            api = FakeBarAssistantApi(profile = ProfileDto(77, "Ryan", "ryan@example.com")),
            sessionRepository = sessionRepo,
        )
        val barResult = authRepo.signIn("https://bar.example.com", "ryan@example.com", "password")
        assertTrue(barResult.isSuccess)
        assertEquals(77, sessionRepo.currentUserId())
        assertEquals(77, preferences.currentSettings().activeUserId)

        // New instance restores cachedUserId from DataStore
        val restoredSessionRepo = DefaultSessionRepository(
            tokenStore = tokenStore,
            appPreferences = preferences,
            syncDao = syncDao,
            imageCacheCleaner = lazy { ImageCacheCleaner {} },
        )
        assertEquals(77, restoredSessionRepo.currentUserId())

        // Changing server clears activeUserId
        restoredSessionRepo.saveSession("https://other.example.com", "2|token", barId = 2, barName = "Other")
        assertNull(restoredSessionRepo.currentUserId())
        assertNull(preferences.currentSettings().activeUserId)

        // Setting and clearing session clears activeUserId
        restoredSessionRepo.setActiveUserId(88)
        assertEquals(88, restoredSessionRepo.currentUserId())
        restoredSessionRepo.clearSession()
        assertNull(restoredSessionRepo.currentUserId())
        assertNull(preferences.currentSettings().activeUserId)

        // signIn still succeeds if getProfile() fails transiently
        val failingProfileAuthRepo = DefaultAuthRepository(
            api = FakeBarAssistantApi(profile = ProfileDto(99, "Ryan", "ryan@example.com")).apply {
                onGetProfile = { throw IllegalStateException("Profile endpoint unavailable") }
            },
            sessionRepository = restoredSessionRepo,
        )
        val resilientSignIn = failingProfileAuthRepo.signIn("https://bar.example.com", "ryan@example.com", "password")
        assertTrue(resilientSignIn.isSuccess)
        assertNull(restoredSessionRepo.currentUserId())
    }

    @Test
    fun `DefaultCocktailRepository syncAll persists shopping list and preserves cached items on transient shopping list failure`() = runTest {
        val db = FakeBarDatabase()
        val api = FakeBarAssistantApi(
            profile = ProfileDto(id = 42, name = "Ryan", email = "ryan@example.com"),
            shoppingListItems = listOf(
                ShoppingListItemDto(
                    ingredient = IngredientBasicDto(10, "gin", "Gin"),
                    quantity = 2,
                ),
            ),
        )
        val sessionRepo = FakeSessionRepository(SessionState.Unauthenticated())
        val cocktailRepo = DefaultCocktailRepository(
            api = api,
            sessionRepository = sessionRepo,
            cocktailDao = db.cocktailDao,
            inventoryDao = db.inventoryDao,
            catalogDao = db.catalogDao,
            syncDao = db.syncDao,
            mutationCoordinator = BarMutationCoordinator(),
        )

        sessionRepo.setAuthenticatedWithoutEmittingFirst(
            SessionState.Authenticated("https://bar.example.com", activeBarId = 7, activeBarName = "Home"),
        )
        assertTrue(cocktailRepo.syncAll(force = true).isSuccess)
        assertEquals(42, sessionRepo.currentUserId())
        assertEquals(
            listOf(ShoppingListEntity(barId = 7, ingredientId = 10, quantity = 2)),
            db.inventoryDao.getShoppingListSnapshot(7),
        )

        // Transient error fetching shopping list must preserve cached items instead of wiping them
        api.onGetShoppingList = { throw IllegalStateException("Timeout") }
        assertTrue(cocktailRepo.syncAll(force = true).isSuccess)
        assertEquals(
            listOf(ShoppingListEntity(barId = 7, ingredientId = 10, quantity = 2)),
            db.inventoryDao.getShoppingListSnapshot(7),
        )
    }

    @Test
    fun `shared coordinator blocks stale syncAll overwrite and preserves favorites and deletions across shelf toggles`() = runTest {
        val db = FakeBarDatabase()
        val coordinator = BarMutationCoordinator()
        val syncEnteredFetch = CompletableDeferred<Unit>()
        val allowSyncFetchFinish = CompletableDeferred<Unit>()
        var armGate = false
        var gatedShoppingListGetCount = 0

        val ginDto = CocktailDto(
            id = 10,
            slug = "martini",
            name = "Dry Martini",
            instructions = "Stir.",
            ingredients = listOf(
                CocktailIngredientDto(sort = 1, amount = 60.0, units = "ml", ingredient = IngredientBasicDto(1, "gin", "Gin"), inBarShelf = false),
            ),
            isFavorited = false,
            inBarShelf = false,
        )
        val negroniDto = CocktailDto(
            id = 11,
            slug = "negroni",
            name = "Negroni",
            instructions = "Stir.",
            ingredients = listOf(
                CocktailIngredientDto(sort = 1, amount = 30.0, units = "ml", ingredient = IngredientBasicDto(2, "campari", "Campari"), inBarShelf = false),
            ),
            isFavorited = false,
            inBarShelf = false,
        )
        db.seedCocktail(7, ginDto)
        db.seedCocktail(7, negroniDto)

        var serverCocktails = listOf(ginDto, negroniDto)
        val api = FakeBarAssistantApi(profile = ProfileDto(42, "Ryan", "ryan@example.com")).apply {
            onGetCocktails = { serverCocktails }
            onGetShoppingList = {
                if (!armGate) {
                    emptyList()
                } else {
                    gatedShoppingListGetCount += 1
                    if (gatedShoppingListGetCount == 1) {
                        syncEnteredFetch.complete(Unit)
                        allowSyncFetchFinish.await()
                        emptyList()
                    } else {
                        listOf(ShoppingListItemDto(IngredientBasicDto(9, "vermouth", "Vermouth"), quantity = 2))
                    }
                }
            }
            onToggleFavorite = { id ->
                serverCocktails = serverCocktails.map { dto ->
                    if (dto.id == id) dto.copy(isFavorited = true) else dto
                }
                true
            }
            onDeleteCocktail = { id ->
                serverCocktails = serverCocktails.filterNot { it.id == id }
            }
            onAddBarInventory = { _, ids ->
                serverCocktails = serverCocktails.map { dto ->
                    val updatedIngredients = dto.ingredients.map { ing ->
                        if (ing.ingredient.id in ids) ing.copy(inBarShelf = true) else ing
                    }
                    dto.copy(
                        ingredients = updatedIngredients,
                        inBarShelf = dto.inBarShelf || updatedIngredients.all { it.optional || it.inBarShelf },
                    )
                }
            }
        }
        val sessionRepo = FakeSessionRepository(
            SessionState.Authenticated("https://bar.example.com", activeBarId = 7, activeBarName = "Home"),
        )
        val cocktailRepo = DefaultCocktailRepository(
            api = api,
            sessionRepository = sessionRepo,
            cocktailDao = db.cocktailDao,
            inventoryDao = db.inventoryDao,
            catalogDao = db.catalogDao,
            syncDao = db.syncDao,
            mutationCoordinator = coordinator,
        )
        val inventoryRepo = DefaultInventoryRepository(
            api = api,
            sessionRepository = sessionRepo,
            cocktailRepository = cocktailRepo,
            cocktailDao = db.cocktailDao,
            inventoryDao = db.inventoryDao,
            catalogDao = db.catalogDao,
            syncDao = db.syncDao,
            mutationCoordinator = coordinator,
        )

        // Let initial auto-sync settle before gating the explicit syncAll(force = true) call
        assertTrue(cocktailRepo.syncAll(force = false).isSuccess)
        armGate = true

        val syncDeferred = async { cocktailRepo.syncAll(force = true) }
        syncEnteredFetch.await()

        // Shopping list write happens while syncAll() is fetching the old empty shopping list
        assertTrue(inventoryRepo.addToShoppingList(listOf(9), quantity = 2).isSuccess)
        assertEquals(listOf(ShoppingListEntity(barId = 7, ingredientId = 9, quantity = 2)), db.inventoryDao.getShoppingListSnapshot(7))

        allowSyncFetchFinish.complete(Unit)
        assertTrue(syncDeferred.await().isSuccess)
        // Because shopping mutation bumped shared generation, syncAll retried and kept/synced quantity=2
        assertEquals(listOf(ShoppingListEntity(barId = 7, ingredientId = 9, quantity = 2)), db.inventoryDao.getShoppingListSnapshot(7))
        assertEquals(2, gatedShoppingListGetCount)

        // Favorite update + delete during shelf toggle must not be clobbered or resurrected
        assertTrue(cocktailRepo.toggleFavorite(10).isSuccess)
        assertTrue(cocktailRepo.deleteCocktail(11).isSuccess)
        assertTrue(inventoryRepo.setInBarShelf(listOf(1), inStock = true).isSuccess)

        val cocktailsImmediately = db.cocktailDao.getCocktailsWithIngredientsForBar(7)
        assertEquals(listOf(10), cocktailsImmediately.map { it.cocktail.id })
        assertTrue(cocktailsImmediately.single().cocktail.isFavorite)
        assertTrue(cocktailsImmediately.single().cocktail.inBarShelf)

        inventoryRepo.lastBackgroundSyncJob?.join()
        val cocktailsAfter = db.cocktailDao.getCocktailsWithIngredientsForBar(7)
        assertEquals(listOf(10), cocktailsAfter.map { it.cocktail.id })
        assertTrue(cocktailsAfter.single().cocktail.isFavorite)
        assertTrue(cocktailsAfter.single().cocktail.inBarShelf)
    }

    @Test
    fun `DefaultSessionRepository clears userId on same-server same-bar token change and rejects stale token setActiveUserId`() = runTest {
        val dataStore = FakePreferencesDataStore()
        val preferences = AppPreferences(dataStore)
        val tokenStore = FakeTokenStore()
        val db = FakeBarDatabase()
        val syncDao = FakeSyncDao(db)
        val sessionRepo = DefaultSessionRepository(
            tokenStore = tokenStore,
            appPreferences = preferences,
            syncDao = syncDao,
            imageCacheCleaner = lazy { ImageCacheCleaner {} },
        )

        // User A signs in on bar 7
        sessionRepo.saveSession("https://bar.example.com", "tokenA", barId = 7, barName = "Home", userId = 10)
        val epochA = sessionRepo.currentSessionEpoch()
        assertEquals(10, sessionRepo.currentUserId())
        db.shoppingListFlow.value = listOf(ShoppingListEntity(barId = 7, ingredientId = 1, quantity = 1))

        // User B signs in on same server & bar 7 -> must reset DB & activeUserId
        sessionRepo.saveSession("https://bar.example.com", "tokenB", barId = 7, barName = "Home", userId = null)
        assertTrue(sessionRepo.currentSessionEpoch() > epochA)
        assertNull(sessionRepo.currentUserId())
        assertNull(preferences.currentSettings().activeUserId)
        assertTrue(db.shoppingListFlow.value.isEmpty())

        // Stale setActiveUserId from User A's token is rejected; User B's token succeeds
        sessionRepo.setActiveUserId(10, expectedToken = "tokenA")
        assertNull(sessionRepo.currentUserId())
        sessionRepo.setActiveUserId(20, expectedToken = "tokenB")
        assertEquals(20, sessionRepo.currentUserId())
    }

    @Test
    fun `DefaultInventoryRepository retries 403 userId, pins barId, cancels bg sync on logout, and deduplicates results`() = runTest {
        val db = FakeBarDatabase()
        val sessionRepo = FakeSessionRepository(
            SessionState.Authenticated("https://bar.example.com", activeBarId = 7, activeBarName = "Home"),
        )
        sessionRepo.setActiveUserId(10) // Stale cached userId

        var addAttempts = 0
        val api = FakeBarAssistantApi(
            profile = ProfileDto(42, "Ryan", "ryan@example.com"),
            recommendedIngredients = listOf(
                IngredientRecommendDto(5, "chartreuse", "Green Chartreuse", 3),
                IngredientRecommendDto(5, "chartreuse", "Green Chartreuse", 3),
            ),
            extraCocktails = mapOf(
                5 to listOf(
                    CocktailBasicDto(101, "last-word", "Last Word"),
                    CocktailBasicDto(101, "last-word", "Last Word"),
                ),
            ),
        ).apply {
            onAddShoppingList = { userId, _ ->
                addAttempts += 1
                if (userId == 10) {
                    throw BarAssistantApiException(403, "Forbidden")
                }
            }
        }
        val cocktailRepo = FakeCocktailRepository()
        val repo = DefaultInventoryRepository(
            api = api,
            sessionRepository = sessionRepo,
            cocktailRepository = cocktailRepo,
            cocktailDao = db.cocktailDao,
            inventoryDao = db.inventoryDao,
            catalogDao = db.catalogDao,
            syncDao = db.syncDao,
            mutationCoordinator = BarMutationCoordinator(),
        )

        assertTrue(repo.addToShoppingList(listOf(5), quantity = 1).isSuccess)
        assertEquals(2, addAttempts)
        assertEquals(1, api.getProfileCallCount)
        assertEquals(42, sessionRepo.currentUserId())
        assertEquals(listOf(Triple("add", 7, 10), Triple("add", 7, 42)), api.shoppingListBarUserLog)

        // Duplicate recommendation & extra cocktail IDs are deduplicated
        assertEquals(listOf(5), repo.getRecommendedIngredients().getOrThrow().map { it.id })
        assertEquals(listOf(101), repo.getUnlockedCocktails(5).getOrThrow().map { it.id })

        // Background sync uses syncAllSilent and cancels on logout
        assertTrue(repo.setInBarShelf(listOf(5), inStock = true).isSuccess)
        sessionRepo.clearSession()
        repo.lastBackgroundSyncJob?.join()
        assertEquals(null, cocktailRepo.syncStatus.value.errorMessage)
    }

    private fun applyAvailabilityRecalculation(
        items: List<CocktailWithIngredients>,
        recalc: CocktailAvailabilityRecalculation,
    ): List<CocktailWithIngredients> {
        val cocktailMap = recalc.cocktailUpdates.associateBy { it.cocktailId }
        val ingMap = recalc.ingredientUpdates.associateBy { Triple(it.cocktailId, it.ingredientId, it.sort) }
        return items.map { item ->
            val updatedCocktail = cocktailMap[item.cocktail.id]?.let { u ->
                item.cocktail.copy(
                    inBarShelf = u.inBarShelf,
                    missingIngredientsCount = u.missingIngredientsCount,
                    missingIngredientNamesJson = u.missingIngredientNamesJson,
                )
            } ?: item.cocktail
            val updatedIngs = item.ingredients.map { ing ->
                ingMap[Triple(ing.cocktailId, ing.ingredientId, ing.sort)]?.let { u ->
                    ing.copy(
                        inBarShelf = u.inBarShelf,
                        inBarShelfAsSubstitute = u.inBarShelfAsSubstitute,
                        inBarShelfAsVariant = u.inBarShelfAsVariant,
                        inBarShelfAsComplex = u.inBarShelfAsComplex,
                        substitutesJson = u.substitutesJson,
                    )
                } ?: ing
            }
            CocktailWithIngredients(updatedCocktail, updatedIngs)
        }
    }

    private class FakePreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    private class FakeTokenStore : SecureTokenStore {
        private var token: String? = null
        override suspend fun getToken(): String? = token
        override suspend fun saveToken(token: String) {
            this.token = token
        }

        override suspend fun clearToken() {
            token = null
        }
    }

    private class FakeSessionRepository(
        initialState: SessionState = SessionState.Unauthenticated(),
    ) : SessionRepository {
        private val _sessionState = MutableStateFlow(initialState)
        override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()
        private var userId: Int? = null
        private var epoch: Long = 1L

        fun setAuthenticatedWithoutEmittingFirst(state: SessionState.Authenticated) {
            _sessionState.value = state
        }

        override suspend fun saveSession(serverUrl: String, token: String, barId: Int?, barName: String?) =
            saveSession(serverUrl, token, barId, barName, userId = null)

        override suspend fun saveSession(
            serverUrl: String,
            token: String,
            barId: Int?,
            barName: String?,
            userId: Int?,
        ) {
            epoch += 1L
            this.userId = userId
            _sessionState.value = SessionState.Authenticated(serverUrl, barId, barName)
        }

        override suspend fun clearSession() {
            epoch += 1L
            userId = null
            val lastUrl = (_sessionState.value as? SessionState.Authenticated)?.serverUrl
            _sessionState.value = SessionState.Unauthenticated(lastServerUrl = lastUrl)
        }

        override suspend fun currentServerUrl(): String? =
            (_sessionState.value as? SessionState.Authenticated)?.serverUrl

        override suspend fun currentToken(): String? = "1|token"

        override suspend fun currentBarId(): Int? =
            (_sessionState.value as? SessionState.Authenticated)?.activeBarId

        override suspend fun currentUserId(): Int? = userId

        override fun currentSessionEpoch(): Long = epoch

        override suspend fun setActiveUserId(userId: Int) {
            this.userId = userId
        }

        override suspend fun setActiveUserId(userId: Int?, expectedToken: String?) {
            this.userId = userId
        }

        override suspend fun onUnauthorized(failedToken: String) {
            clearSession()
        }
    }

    private class FakeCocktailRepository : CocktailRepository {
        private val _syncStatus = MutableStateFlow(SyncStatus())
        override val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()
        val syncForceCalls = mutableListOf<Boolean>()
        val silentSyncForceCalls = mutableListOf<Boolean>()

        override fun observeCocktails(filter: CocktailFilter): Flow<List<CocktailSummary>> = flowOf(emptyList())
        override fun observeCocktailDetail(cocktailId: Int): Flow<CocktailDetail?> = flowOf(null)
        override fun observeCatalog(): Flow<BarCatalog> = flowOf(BarCatalog())

        override suspend fun syncAll(force: Boolean): Result<Unit> {
            syncForceCalls += force
            return Result.success(Unit)
        }

        override suspend fun syncAllSilent(force: Boolean): Result<Unit> {
            silentSyncForceCalls += force
            return Result.success(Unit)
        }

        override suspend fun refreshCocktail(cocktailId: Int): Result<CocktailDetail> =
            Result.failure(IllegalStateException("Unused"))

        override suspend fun toggleFavorite(cocktailId: Int): Result<Boolean> =
            Result.failure(IllegalStateException("Unused"))

        override suspend fun saveCocktail(cocktailId: Int?, request: CocktailUpsertRequest): Result<CocktailDetail> =
            Result.failure(IllegalStateException("Unused"))

        override suspend fun deleteCocktail(cocktailId: Int): Result<Unit> =
            Result.failure(IllegalStateException("Unused"))

        override suspend fun uploadImage(
            bytes: ByteArray,
            fileName: String,
            mimeType: String?,
            sort: Int,
        ): Result<UploadedImage> = Result.failure(IllegalStateException("Unused"))

        override suspend fun uploadImageFromUrl(
            imageUrl: String,
            copyright: String?,
            sort: Int,
        ): Result<UploadedImage> = Result.failure(IllegalStateException("Unused"))

        override suspend fun scrapeRecipeFromUrl(url: String): Result<ScrapedCocktail> =
            Result.failure(IllegalStateException("Unused"))
    }

    private class FakeBarAssistantApi(
        private val profile: ProfileDto = ProfileDto(1, "User", "user@example.com"),
        private val recommendedIngredients: List<IngredientRecommendDto> = emptyList(),
        private val extraCocktails: Map<Int, List<CocktailBasicDto>> = emptyMap(),
        private val shoppingListItems: List<ShoppingListItemDto> = emptyList(),
    ) : BarAssistantApi {
        var getProfileCallCount = 0
        val addedBarInventoryCalls = mutableListOf<Pair<Int, List<Int>>>()
        val removedBarInventoryCalls = mutableListOf<Pair<Int, List<Int>>>()
        val addedShoppingListCalls = mutableListOf<Pair<Int, List<ShoppingListIngredientInputDto>>>()
        val removedShoppingListCalls = mutableListOf<Pair<Int, List<Int>>>()
        val shoppingListBarUserLog = mutableListOf<Triple<String, Int, Int>>()

        var onAddBarInventory: suspend (Int, List<Int>) -> Unit = { _, _ -> }
        var onRemoveBarInventory: suspend (Int, List<Int>) -> Unit = { _, _ -> }
        var onGetProfile: suspend () -> ProfileDto = { profile }
        var onGetCocktails: suspend () -> List<CocktailDto> = { emptyList() }
        var onToggleFavorite: suspend (Int) -> Boolean = { true }
        var onDeleteCocktail: suspend (Int) -> Unit = {}
        var onGetRecommendedBarIngredients: suspend (Int) -> List<IngredientRecommendDto> = { recommendedIngredients }
        var onGetShoppingList: suspend (Int) -> List<ShoppingListItemDto> = { shoppingListItems }
        var onAddShoppingList: suspend (Int, List<ShoppingListIngredientInputDto>) -> Unit = { _, _ -> }
        var onRemoveShoppingList: suspend (Int, List<Int>) -> Unit = { _, _ -> }

        override suspend fun getServerVersion(serverUrl: String): ServerVersionDto =
            ServerVersionDto(version = "v6.12.1", isPasswordLoginEnabled = true)

        override suspend fun login(serverUrl: String, email: String, password: String): TokenDto =
            TokenDto(token = "1|token")

        override suspend fun logout() = Unit

        override suspend fun getBars(page: Int, perPage: Int): PaginatedEnvelope<BarDto> =
            PaginatedEnvelope(listOf(BarDto(id = 7, name = "Home", slug = "home", status = "active")))

        override suspend fun getBars(
            serverUrl: String,
            token: String,
            page: Int,
            perPage: Int,
        ): PaginatedEnvelope<BarDto> = getBars(page, perPage)

        override suspend fun getCocktails(page: Int, perPage: Int, query: String?): PaginatedEnvelope<CocktailDto> =
            PaginatedEnvelope(onGetCocktails())

        override suspend fun getCocktail(idOrSlug: String): CocktailDto =
            throw IllegalStateException("Unused")

        override suspend fun createCocktail(request: CocktailWriteRequestDto): CocktailDto =
            throw IllegalStateException("Unused")

        override suspend fun updateCocktail(cocktailId: Int, request: CocktailWriteRequestDto): CocktailDto =
            throw IllegalStateException("Unused")

        override suspend fun deleteCocktail(cocktailId: Int) = onDeleteCocktail(cocktailId)
        override suspend fun toggleFavorite(cocktailId: Int): Boolean = onToggleFavorite(cocktailId)

        override suspend fun uploadImage(
            bytes: ByteArray,
            fileName: String,
            mimeType: String?,
            sort: Int,
            copyright: String?,
        ): ImageDto = throw IllegalStateException("Unused")

        override suspend fun uploadImageFromUrl(imageUrl: String, copyright: String?, sort: Int): ImageDto =
            throw IllegalStateException("Unused")

        override suspend fun scrapeCocktail(url: String): ScrapeResponseDto =
            throw IllegalStateException("Unused")

        override suspend fun getIngredients(page: Int, perPage: Int): PaginatedEnvelope<IngredientDto> =
            PaginatedEnvelope(emptyList())

        override suspend fun getGlasses(page: Int, perPage: Int): PaginatedEnvelope<GlassDto> =
            PaginatedEnvelope(emptyList())

        override suspend fun getCocktailMethods(page: Int, perPage: Int): PaginatedEnvelope<CocktailMethodDto> =
            PaginatedEnvelope(emptyList())

        override suspend fun getTags(page: Int, perPage: Int): PaginatedEnvelope<TagDto> =
            PaginatedEnvelope(emptyList())

        override suspend fun getUtensils(page: Int, perPage: Int): PaginatedEnvelope<UtensilDto> =
            PaginatedEnvelope(emptyList())

        override suspend fun getBarInventoryIngredientIds(
            barId: Int,
            page: Int,
            perPage: Int,
        ): PaginatedEnvelope<IngredientBasicDto> = PaginatedEnvelope(emptyList())

        override suspend fun getProfile(): ProfileDto {
            getProfileCallCount += 1
            return onGetProfile()
        }

        override suspend fun getProfile(serverUrl: String, token: String): ProfileDto = getProfile()

        override suspend fun addBarInventoryIngredients(barId: Int, ingredientIds: List<Int>) {
            addedBarInventoryCalls += barId to ingredientIds
            onAddBarInventory(barId, ingredientIds)
        }

        override suspend fun removeBarInventoryIngredients(barId: Int, ingredientIds: List<Int>) {
            removedBarInventoryCalls += barId to ingredientIds
            onRemoveBarInventory(barId, ingredientIds)
        }

        override suspend fun getRecommendedBarIngredients(barId: Int): List<IngredientRecommendDto> =
            onGetRecommendedBarIngredients(barId)

        override suspend fun getExtraCocktailsForIngredient(barId: Int, ingredientId: Int): List<CocktailBasicDto> =
            extraCocktails[ingredientId].orEmpty()

        override suspend fun getShoppingList(barId: Int, userId: Int): List<ShoppingListItemDto> {
            shoppingListBarUserLog += Triple("get", barId, userId)
            return onGetShoppingList(userId)
        }

        override suspend fun addToShoppingList(
            barId: Int,
            userId: Int,
            items: List<ShoppingListIngredientInputDto>,
        ) {
            shoppingListBarUserLog += Triple("add", barId, userId)
            addedShoppingListCalls += userId to items
            onAddShoppingList(userId, items)
        }

        override suspend fun removeFromShoppingList(barId: Int, userId: Int, ingredientIds: List<Int>) {
            shoppingListBarUserLog += Triple("remove", barId, userId)
            removedShoppingListCalls += userId to ingredientIds
            onRemoveShoppingList(userId, ingredientIds)
        }
    }

    private class FakeBarDatabase {
        val cocktailsFlow = MutableStateFlow<List<CocktailEntity>>(emptyList())
        val cocktailIngredientsFlow = MutableStateFlow<List<CocktailIngredientEntity>>(emptyList())
        val ingredientsFlow = MutableStateFlow<List<IngredientEntity>>(emptyList())
        val shelfFlow = MutableStateFlow<List<BarShelfEntity>>(emptyList())
        val shoppingListFlow = MutableStateFlow<List<ShoppingListEntity>>(emptyList())
        val glassesFlow = MutableStateFlow<List<GlassEntity>>(emptyList())
        val methodsFlow = MutableStateFlow<List<CocktailMethodEntity>>(emptyList())
        val tagsFlow = MutableStateFlow<List<TagEntity>>(emptyList())
        val utensilsFlow = MutableStateFlow<List<UtensilEntity>>(emptyList())
        val syncTimestamps = mutableMapOf<String, Long>()

        val cocktailDao: CocktailDao = FakeCocktailDao(this)
        val inventoryDao: InventoryDao = FakeInventoryDao(this)
        val catalogDao: CatalogDao = FakeCatalogDao(this)
        val syncDao: SyncDao = FakeSyncDao(this)

        fun seedIngredient(
            barId: Int,
            id: Int,
            slug: String,
            name: String,
            categoryName: String? = null,
            thumbUrl: String? = null,
        ) {
            ingredientsFlow.value = ingredientsFlow.value.filterNot { it.id == id } + IngredientEntity(
                id = id,
                barId = barId,
                slug = slug,
                name = name,
                strength = 0.0,
                description = null,
                color = null,
                categoryName = categoryName,
                parentIngredientId = null,
                mainImageThumbUrl = thumbUrl,
            )
        }

        fun seedCocktail(barId: Int, dto: CocktailDto) {
            val (cocktail, ingredients) = dto.toEntityAndIngredients(barId)
            cocktailsFlow.value = cocktailsFlow.value.filterNot { it.id == cocktail.id } + cocktail
            cocktailIngredientsFlow.value =
                cocktailIngredientsFlow.value.filterNot { it.cocktailId == cocktail.id } + ingredients
        }
    }

    private class FakeCocktailDao(private val db: FakeBarDatabase) : CocktailDao {
        override fun observeCocktails(barId: Int): Flow<List<CocktailEntity>> =
            db.cocktailsFlow.map { list -> list.filter { it.barId == barId }.sortedBy { it.name.lowercase() } }

        override fun observeCocktailsWithIngredients(barId: Int): Flow<List<CocktailWithIngredients>> =
            observeCocktails(barId).map { cocktails ->
                cocktails.map { cocktail ->
                    CocktailWithIngredients(
                        cocktail = cocktail,
                        ingredients = db.cocktailIngredientsFlow.value.filter { it.cocktailId == cocktail.id },
                    )
                }
            }

        override suspend fun getCocktailsWithIngredientsForBar(barId: Int): List<CocktailWithIngredients> =
            observeCocktailsWithIngredients(barId).first()

        override fun searchCocktails(barId: Int, query: String): Flow<List<CocktailEntity>> =
            observeCocktails(barId)

        override fun observeCocktailWithIngredients(cocktailId: Int): Flow<CocktailWithIngredients?> =
            db.cocktailsFlow.map { list ->
                list.firstOrNull { it.id == cocktailId }?.let { c ->
                    CocktailWithIngredients(c, db.cocktailIngredientsFlow.value.filter { it.cocktailId == c.id })
                }
            }

        override fun observeCocktailWithIngredients(barId: Int, cocktailId: Int): Flow<CocktailWithIngredients?> =
            db.cocktailsFlow.map { list ->
                list.firstOrNull { it.barId == barId && it.id == cocktailId }?.let { c ->
                    CocktailWithIngredients(c, db.cocktailIngredientsFlow.value.filter { it.cocktailId == c.id })
                }
            }

        override fun observeCocktailIngredients(cocktailId: Int): Flow<List<CocktailIngredientEntity>> =
            db.cocktailIngredientsFlow.map { list -> list.filter { it.cocktailId == cocktailId }.sortedBy { it.sort } }

        override suspend fun upsertCocktails(cocktails: List<CocktailEntity>) {
            val incomingIds = cocktails.map { it.id }.toSet()
            db.cocktailsFlow.value = db.cocktailsFlow.value.filterNot { it.id in incomingIds } + cocktails
        }

        override suspend fun upsertCocktail(cocktail: CocktailEntity) = upsertCocktails(listOf(cocktail))

        override suspend fun deleteCocktailIngredients(cocktailId: Int) {
            db.cocktailIngredientsFlow.value = db.cocktailIngredientsFlow.value.filterNot { it.cocktailId == cocktailId }
        }

        override suspend fun upsertCocktailIngredients(ingredients: List<CocktailIngredientEntity>) {
            val keys = ingredients.map { Triple(it.cocktailId, it.ingredientId, it.sort) }.toSet()
            db.cocktailIngredientsFlow.value = db.cocktailIngredientsFlow.value.filterNot {
                Triple(it.cocktailId, it.ingredientId, it.sort) in keys
            } + ingredients
        }

        override suspend fun updateFavorite(cocktailId: Int, isFavorite: Boolean) {
            db.cocktailsFlow.value = db.cocktailsFlow.value.map {
                if (it.id == cocktailId) it.copy(isFavorite = isFavorite) else it
            }
        }

        override suspend fun updateFavorite(barId: Int, cocktailId: Int, isFavorite: Boolean) {
            db.cocktailsFlow.value = db.cocktailsFlow.value.map {
                if (it.barId == barId && it.id == cocktailId) it.copy(isFavorite = isFavorite) else it
            }
        }

        override suspend fun deleteCocktailById(cocktailId: Int) {
            db.cocktailsFlow.value = db.cocktailsFlow.value.filterNot { it.id == cocktailId }
            db.cocktailIngredientsFlow.value = db.cocktailIngredientsFlow.value.filterNot { it.cocktailId == cocktailId }
        }

        override suspend fun deleteCocktailById(barId: Int, cocktailId: Int) {
            val removedIds = db.cocktailsFlow.value.filter { it.barId == barId && it.id == cocktailId }.map { it.id }.toSet()
            db.cocktailsFlow.value = db.cocktailsFlow.value.filterNot { it.id in removedIds }
            db.cocktailIngredientsFlow.value = db.cocktailIngredientsFlow.value.filterNot { it.cocktailId in removedIds }
        }

        override suspend fun deleteCocktailsForBar(barId: Int) {
            val removedIds = db.cocktailsFlow.value.filter { it.barId == barId }.map { it.id }.toSet()
            db.cocktailsFlow.value = db.cocktailsFlow.value.filterNot { it.barId == barId }
            db.cocktailIngredientsFlow.value = db.cocktailIngredientsFlow.value.filterNot { it.cocktailId in removedIds }
        }
    }

    private class FakeInventoryDao(private val db: FakeBarDatabase) : InventoryDao {
        override fun observeBarShelfIngredientIds(barId: Int): Flow<List<Int>> =
            db.shelfFlow.map { list -> list.filter { it.barId == barId }.map { it.ingredientId } }

        override suspend fun getBarShelfIngredientIds(barId: Int): List<Int> =
            observeBarShelfIngredientIds(barId).first()

        override fun observeShoppingList(barId: Int): Flow<List<ShoppingListEntity>> =
            db.shoppingListFlow.map { list -> list.filter { it.barId == barId } }

        override suspend fun getShoppingListSnapshot(barId: Int): List<ShoppingListEntity> =
            observeShoppingList(barId).first()

        override suspend fun upsertBarShelfItems(items: List<BarShelfEntity>) {
            val keys = items.map { it.barId to it.ingredientId }.toSet()
            db.shelfFlow.value = db.shelfFlow.value.filterNot { (it.barId to it.ingredientId) in keys } + items
        }

        override suspend fun deleteBarShelfItem(barId: Int, ingredientId: Int) {
            db.shelfFlow.value = db.shelfFlow.value.filterNot { it.barId == barId && it.ingredientId == ingredientId }
        }

        override suspend fun deleteBarShelfItemsChunk(barId: Int, ingredientIds: List<Int>) {
            val idSet = ingredientIds.toSet()
            db.shelfFlow.value = db.shelfFlow.value.filterNot { it.barId == barId && it.ingredientId in idSet }
        }

        override suspend fun clearBarShelf(barId: Int) {
            db.shelfFlow.value = db.shelfFlow.value.filterNot { it.barId == barId }
        }

        override suspend fun upsertShoppingListItems(items: List<ShoppingListEntity>) {
            val keys = items.map { it.barId to it.ingredientId }.toSet()
            db.shoppingListFlow.value =
                db.shoppingListFlow.value.filterNot { (it.barId to it.ingredientId) in keys } + items
        }

        override suspend fun deleteShoppingListItem(barId: Int, ingredientId: Int) {
            db.shoppingListFlow.value =
                db.shoppingListFlow.value.filterNot { it.barId == barId && it.ingredientId == ingredientId }
        }

        override suspend fun deleteShoppingListItemsChunk(barId: Int, ingredientIds: List<Int>) {
            val idSet = ingredientIds.toSet()
            db.shoppingListFlow.value =
                db.shoppingListFlow.value.filterNot { it.barId == barId && it.ingredientId in idSet }
        }

        override suspend fun clearShoppingList(barId: Int) {
            db.shoppingListFlow.value = db.shoppingListFlow.value.filterNot { it.barId == barId }
        }
    }

    private class FakeCatalogDao(private val db: FakeBarDatabase) : CatalogDao {
        override fun observeIngredients(barId: Int): Flow<List<IngredientEntity>> =
            db.ingredientsFlow.map { list -> list.filter { it.barId == barId }.sortedBy { it.name.lowercase() } }

        override suspend fun getIngredientsForBar(barId: Int): List<IngredientEntity> =
            observeIngredients(barId).first()

        override fun observeGlasses(barId: Int): Flow<List<GlassEntity>> =
            db.glassesFlow.map { list -> list.filter { it.barId == barId } }

        override fun observeMethods(barId: Int): Flow<List<CocktailMethodEntity>> =
            db.methodsFlow.map { list -> list.filter { it.barId == barId } }

        override fun observeTags(barId: Int): Flow<List<TagEntity>> =
            db.tagsFlow.map { list -> list.filter { it.barId == barId } }

        override fun observeUtensils(barId: Int): Flow<List<UtensilEntity>> =
            db.utensilsFlow.map { list -> list.filter { it.barId == barId } }

        override suspend fun upsertIngredients(ingredients: List<IngredientEntity>) {
            val ids = ingredients.map { it.id }.toSet()
            db.ingredientsFlow.value = db.ingredientsFlow.value.filterNot { it.id in ids } + ingredients
        }

        override suspend fun upsertGlasses(glasses: List<GlassEntity>) {
            db.glassesFlow.value = glasses
        }

        override suspend fun upsertMethods(methods: List<CocktailMethodEntity>) {
            db.methodsFlow.value = methods
        }

        override suspend fun upsertTags(tags: List<TagEntity>) {
            db.tagsFlow.value = tags
        }

        override suspend fun upsertUtensils(utensils: List<UtensilEntity>) {
            db.utensilsFlow.value = utensils
        }

        override suspend fun clearIngredientsForBar(barId: Int) {
            db.ingredientsFlow.value = db.ingredientsFlow.value.filterNot { it.barId == barId }
        }

        override suspend fun clearGlassesForBar(barId: Int) {
            db.glassesFlow.value = db.glassesFlow.value.filterNot { it.barId == barId }
        }

        override suspend fun clearMethodsForBar(barId: Int) {
            db.methodsFlow.value = db.methodsFlow.value.filterNot { it.barId == barId }
        }

        override suspend fun clearTagsForBar(barId: Int) {
            db.tagsFlow.value = db.tagsFlow.value.filterNot { it.barId == barId }
        }

        override suspend fun clearUtensilsForBar(barId: Int) {
            db.utensilsFlow.value = db.utensilsFlow.value.filterNot { it.barId == barId }
        }

        override suspend fun getSyncTimestamp(key: String): Long? = db.syncTimestamps[key]

        override suspend fun setSyncTimestamp(metadata: SyncMetadataEntity) {
            db.syncTimestamps[metadata.key] = metadata.lastSyncedEpochMillis
        }
    }

    private class FakeSyncDao(private val db: FakeBarDatabase) : SyncDao {
        override suspend fun deleteCocktailsForBar(barId: Int) = db.cocktailDao.deleteCocktailsForBar(barId)
        override suspend fun deleteIngredientsForBar(barId: Int) = db.catalogDao.clearIngredientsForBar(barId)
        override suspend fun deleteGlassesForBar(barId: Int) = db.catalogDao.clearGlassesForBar(barId)
        override suspend fun deleteMethodsForBar(barId: Int) = db.catalogDao.clearMethodsForBar(barId)
        override suspend fun deleteTagsForBar(barId: Int) = db.catalogDao.clearTagsForBar(barId)
        override suspend fun deleteUtensilsForBar(barId: Int) = db.catalogDao.clearUtensilsForBar(barId)
        override suspend fun deleteBarShelfForBar(barId: Int) = db.inventoryDao.clearBarShelf(barId)
        override suspend fun deleteShoppingListForBar(barId: Int) = db.inventoryDao.clearShoppingList(barId)
        override suspend fun upsertCocktails(cocktails: List<CocktailEntity>) = db.cocktailDao.upsertCocktails(cocktails)
        override suspend fun upsertCocktailIngredients(ingredients: List<CocktailIngredientEntity>) =
            db.cocktailDao.upsertCocktailIngredients(ingredients)

        override suspend fun upsertIngredients(ingredients: List<IngredientEntity>) =
            db.catalogDao.upsertIngredients(ingredients)

        override suspend fun upsertGlasses(glasses: List<GlassEntity>) = db.catalogDao.upsertGlasses(glasses)
        override suspend fun upsertMethods(methods: List<CocktailMethodEntity>) = db.catalogDao.upsertMethods(methods)
        override suspend fun upsertTags(tags: List<TagEntity>) = db.catalogDao.upsertTags(tags)
        override suspend fun upsertUtensils(utensils: List<UtensilEntity>) = db.catalogDao.upsertUtensils(utensils)
        override suspend fun upsertBarShelfItems(items: List<BarShelfEntity>) = db.inventoryDao.upsertBarShelfItems(items)
        override suspend fun upsertShoppingListItems(items: List<ShoppingListEntity>) =
            db.inventoryDao.upsertShoppingListItems(items)

        override suspend fun upsertSyncMetadata(metadata: SyncMetadataEntity) = db.catalogDao.setSyncTimestamp(metadata)

        override suspend fun deleteAllCocktailIngredients() {
            db.cocktailIngredientsFlow.value = emptyList()
        }

        override suspend fun deleteAllCocktails() {
            db.cocktailsFlow.value = emptyList()
        }

        override suspend fun deleteAllBarShelf() {
            db.shelfFlow.value = emptyList()
        }

        override suspend fun deleteAllShoppingList() {
            db.shoppingListFlow.value = emptyList()
        }

        override suspend fun deleteAllIngredients() {
            db.ingredientsFlow.value = emptyList()
        }

        override suspend fun deleteAllGlasses() {
            db.glassesFlow.value = emptyList()
        }

        override suspend fun deleteAllMethods() {
            db.methodsFlow.value = emptyList()
        }

        override suspend fun deleteAllTags() {
            db.tagsFlow.value = emptyList()
        }

        override suspend fun deleteAllUtensils() {
            db.utensilsFlow.value = emptyList()
        }

        override suspend fun deleteAllSyncMetadata() {
            db.syncTimestamps.clear()
        }

        override suspend fun deleteBarShelfItemsChunk(barId: Int, ingredientIds: List<Int>) =
            db.inventoryDao.deleteBarShelfItemsChunk(barId, ingredientIds)

        override suspend fun updateCocktailAvailability(
            barId: Int,
            cocktailId: Int,
            inBarShelf: Boolean,
            missingIngredientsCount: Int,
            missingIngredientNamesJson: String,
        ) {
            db.cocktailsFlow.value = db.cocktailsFlow.value.map { cocktail ->
                if (cocktail.barId == barId && cocktail.id == cocktailId) {
                    cocktail.copy(
                        inBarShelf = inBarShelf,
                        missingIngredientsCount = missingIngredientsCount,
                        missingIngredientNamesJson = missingIngredientNamesJson,
                    )
                } else {
                    cocktail
                }
            }
        }

        override suspend fun updateCocktailIngredientAvailability(
            cocktailId: Int,
            ingredientId: Int,
            sort: Int,
            inBarShelf: Boolean,
            inBarShelfAsSubstitute: Boolean,
            inBarShelfAsVariant: Boolean,
            inBarShelfAsComplex: Boolean,
            substitutesJson: String,
        ) {
            db.cocktailIngredientsFlow.value = db.cocktailIngredientsFlow.value.map { ing ->
                if (ing.cocktailId == cocktailId && ing.ingredientId == ingredientId && ing.sort == sort) {
                    ing.copy(
                        inBarShelf = inBarShelf,
                        inBarShelfAsSubstitute = inBarShelfAsSubstitute,
                        inBarShelfAsVariant = inBarShelfAsVariant,
                        inBarShelfAsComplex = inBarShelfAsComplex,
                        substitutesJson = substitutesJson,
                    )
                } else {
                    ing
                }
            }
        }
    }
}
