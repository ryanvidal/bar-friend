package com.ryanvidal.barfriend.feature.cocktails

import com.ryanvidal.barfriend.core.model.CocktailIngredient
import com.ryanvidal.barfriend.core.model.IngredientSubstitute
import com.ryanvidal.barfriend.core.model.IngredientSubstituteInput
import com.ryanvidal.barfriend.core.model.ScrapedCocktail
import com.ryanvidal.barfriend.core.model.ScrapedCocktailIngredient
import com.ryanvidal.barfriend.core.model.ScrapedImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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
class CocktailEditorViewModelTest {
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
    fun `formatting preserves 3 decimals for eighth-oz fractions, negative ABV signs, large numbers, and non-finite guards`() {
        assertEquals("0.125", formatNumericInput(0.125))
        assertEquals("0.375", formatNumericInput(0.375))
        assertEquals("0.625", formatNumericInput(0.625))
        assertEquals("0.875", formatNumericInput(0.875))
        assertEquals("22.5", formatNumericInput(22.5))
        assertEquals("30", formatNumericInput(30.0))
        assertEquals("0", formatNumericInput(-0.0))
        assertEquals("10000000", formatNumericInput(10_000_000.0))
        assertEquals("", formatNumericInput(Double.NaN))
        assertEquals("", formatNumericInput(Double.POSITIVE_INFINITY))
        assertEquals("", formatNumericInput(Double.NEGATIVE_INFINITY))

        assertEquals("-0.5% ABV", formatAbv(-0.5))
        assertEquals("0% ABV", formatAbv(-0.01))
        assertEquals("24% ABV", formatAbv(24.0))
        assertEquals("12.5% ABV", formatAbv(12.5))
        assertEquals("", formatAbv(Double.NaN))
        assertEquals("", formatAbv(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `validates blank fields, non-finite or negative amounts, and accepts zero or blank amount with unit`() = runTest(testDispatcher) {
        val repository = FakeCocktailRepository()
        val viewModel = CocktailEditorViewModel(
            cocktailId = null,
            initialImportUrl = null,
            cocktailRepository = repository,
        )
        advanceUntilIdle()

        // Blank name
        viewModel.save()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(repository.saveCalls.isEmpty())

        // Blank instructions
        viewModel.onNameChanged("Gimlet")
        viewModel.save()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(repository.saveCalls.isEmpty())

        // Unmatched ingredient row
        viewModel.onInstructionsChanged("Shake with ice and strain.")
        val rowKey = viewModel.addIngredientRow()
        viewModel.updateIngredientRow(rowKey, amountText = "60", units = "ml")
        viewModel.save()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(repository.saveCalls.isEmpty())

        // Matched ingredient with negative / NaN / Infinity amounts must be rejected
        viewModel.updateIngredientSelection(rowKey, sampleCatalog().ingredients.first())
        listOf("-1", "NaN", "Infinity", "-Infinity", "abc").forEach { badAmount ->
            viewModel.updateIngredientRow(rowKey, amountText = badAmount, units = "ml")
            viewModel.save()
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.errorMessage, "Expected error for amount=$badAmount")
            assertTrue(repository.saveCalls.isEmpty())
        }

        // Blank amount AND blank units must be rejected
        viewModel.updateIngredientRow(rowKey, amountText = "", units = "")
        viewModel.save()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(repository.saveCalls.isEmpty())

        // Invalid amountMax (NaN / negative / < amount) must be rejected
        viewModel.updateIngredientRow(rowKey, amountText = "30", amountMaxText = "20", units = "ml")
        viewModel.save()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(repository.saveCalls.isEmpty())

        viewModel.updateIngredientRow(rowKey, amountText = "30", amountMaxText = "NaN", units = "ml")
        viewModel.save()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(repository.saveCalls.isEmpty())

        // Blank amount with non-blank unit ("top up") and unitless ingredient with numeric amount are valid
        viewModel.updateIngredientRow(rowKey, amountText = "", amountMaxText = "", units = "top up")
        val unitlessKey = viewModel.addIngredientRow(sampleCatalog().ingredients[3])
        viewModel.updateIngredientRow(unitlessKey, amountText = "1", units = "")
        viewModel.save()
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.errorMessage)
        assertEquals(1, repository.saveCalls.size)
        val savedIngredients = repository.saveCalls.first().request.ingredients
        assertEquals(0.0, savedIngredients[0].amount)
        assertEquals("top up", savedIngredients[0].units)
        assertEquals(1.0, savedIngredients[1].amount)
        assertEquals("", savedIngredients[1].units)
    }

    @Test
    fun `URL scrape auto matches catalog and preserves unitless ingredients and existing description or garnish`() = runTest(testDispatcher) {
        val repository = FakeCocktailRepository().apply {
            scrapeResult = Result.success(
                ScrapedCocktail(
                    name = "Last Word",
                    instructions = "Shake all ingredients with ice and double strain.",
                    description = "   ",
                    garnish = "",
                    source = "https://example.com/last-word",
                    tags = listOf("classic", "Herbal"),
                    glassName = "  coupe ",
                    methodName = "SHAKE",
                    images = listOf(ScrapedImage("https://example.com/last-word.jpg", "Example Photo")),
                    ingredients = listOf(
                        ScrapedCocktailIngredient(
                            name = "  london dry gin ",
                            amount = 0.75,
                            units = "oz",
                        ),
                        ScrapedCocktailIngredient(
                            name = "Lime Juice",
                            amount = 1.0,
                            units = null,
                        ),
                        ScrapedCocktailIngredient(
                            name = "Green Chartreuse",
                            amount = 22.5,
                            units = "ml",
                            sourceLine = "3/4 oz Green Chartreuse",
                        ),
                    ),
                ),
            )
        }

        val viewModel = CocktailEditorViewModel(
            cocktailId = null,
            initialImportUrl = null,
            cocktailRepository = repository,
        )
        advanceUntilIdle()

        viewModel.onDescriptionChanged("Keep this existing description")
        viewModel.onGarnishChanged("Brandied cherry")
        viewModel.importFromUrl("https://example.com/last-word")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("https://example.com/last-word"), repository.scrapedUrls)
        assertEquals("Last Word", state.name)
        assertEquals("Keep this existing description", state.description)
        assertEquals("Brandied cherry", state.garnish)
        assertEquals(11, state.glassId)
        assertEquals(21, state.methodId)
        assertEquals(listOf("Classic", "Herbal"), state.tags)
        assertEquals(3, state.ingredients.size)
        assertTrue(state.hasUnsavedChanges)

        val matchedRow = state.ingredients[0]
        assertEquals(1, matchedRow.ingredientId)
        assertEquals("London Dry Gin", matchedRow.ingredientName)
        assertNull(matchedRow.unmatchedScrapedHint)

        val unitlessRow = state.ingredients[1]
        assertEquals(4, unitlessRow.ingredientId)
        assertEquals("", unitlessRow.units)

        val unmatchedRow = state.ingredients[2]
        assertNull(unmatchedRow.ingredientId)
        assertEquals("Green Chartreuse", unmatchedRow.ingredientName)
        assertEquals("3/4 oz Green Chartreuse", unmatchedRow.unmatchedScrapedHint)

        assertEquals(1, state.photos.size)
        assertTrue(state.photos.first() is EditorPhotoItem.PendingRemote)
    }

    @Test
    fun `save uploads photos once on double tap, preserves 0_125oz precision, and consumes saved event`() = runTest(testDispatcher) {
        val uploadGate = CompletableDeferred<Unit>()
        val repository = FakeCocktailRepository().apply {
            uploadLocalSuspendHook = { uploadGate.await() }
        }
        val viewModel = CocktailEditorViewModel(
            cocktailId = null,
            initialImportUrl = null,
            cocktailRepository = repository,
        )
        advanceUntilIdle()

        viewModel.onNameChanged("Gimlet")
        viewModel.onInstructionsChanged("1. Shake with ice.\n2. Strain into a chilled coupe.")
        val ginKey = viewModel.addIngredientRow(sampleCatalog().ingredients[0])
        viewModel.updateIngredientRow(ginKey, amountText = "0.125", units = "oz")

        val sampleBytes = byteArrayOf(1, 2, 3, 4)
        viewModel.addLocalPhoto(sampleBytes, "gimlet.jpg", "image/jpeg")
        assertTrue(viewModel.uiState.value.hasUnsavedChanges)

        // Double tap save synchronously before upload finishes
        viewModel.save()
        assertTrue(viewModel.uiState.value.isSaving)
        viewModel.save()

        uploadGate.complete(Unit)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSaving)
        assertFalse(state.hasUnsavedChanges)
        assertNull(state.errorMessage)
        assertNotNull(state.savedCocktailId)
        assertEquals(1, repository.localUploads.size)
        assertEquals(1, repository.saveCalls.size)
        assertEquals(0.125, repository.saveCalls.first().request.ingredients.first().amount)
        assertTrue(state.photos.first() is EditorPhotoItem.Existing)

        viewModel.consumeSavedEvent()
        assertNull(viewModel.uiState.value.savedCocktailId)
    }

    @Test
    fun `editing existing cocktail preserves isSpecified, substitutes, unitless rows, and 0_125oz amounts`() = runTest(testDispatcher) {
        val base = sampleCocktailDetail(42)
        val existing = base.copy(
            ingredients = listOf(
                CocktailIngredient(
                    id = 1,
                    ingredientId = 1,
                    ingredientSlug = "gin",
                    name = "London Dry Gin",
                    sort = 1,
                    amount = 0.125,
                    amountMax = null,
                    units = "oz",
                    optional = false,
                    note = null,
                    inBarShelf = true,
                    substitutes = listOf(
                        IngredientSubstitute(
                            id = 9,
                            slug = "plymouth-gin",
                            name = "Plymouth Gin",
                            inBarShelf = true,
                            amount = 0.125,
                            amountMax = 0.25,
                            units = "oz",
                        ),
                    ),
                    isSpecified = true,
                ),
                CocktailIngredient(
                    id = 2,
                    ingredientId = 4,
                    ingredientSlug = "lime-juice",
                    name = "Lime Juice",
                    sort = 2,
                    amount = 1.0,
                    amountMax = null,
                    units = null,
                    optional = false,
                    note = null,
                    inBarShelf = true,
                    substitutes = emptyList(),
                    isSpecified = false,
                ),
            ),
        )
        val repository = FakeCocktailRepository(
            initialDetails = mapOf(42 to existing),
        )
        val viewModel = CocktailEditorViewModel(
            cocktailId = 42,
            initialImportUrl = null,
            cocktailRepository = repository,
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.isEditing)
        assertTrue(state.hasLoadedExisting)
        assertFalse(state.hasUnsavedChanges)
        assertEquals("0.125", state.ingredients[0].amountText)
        assertTrue(state.ingredients[0].isSpecified)
        assertEquals(
            listOf(
                IngredientSubstituteInput(
                    ingredientId = 9,
                    ingredientName = "Plymouth Gin",
                    amount = 0.125,
                    amountMax = 0.25,
                    units = "oz",
                ),
            ),
            state.ingredients[0].substitutes,
        )
        assertEquals("", state.ingredients[1].units)

        viewModel.onNameChanged("Negroni Sbagliato")
        assertTrue(viewModel.uiState.value.hasUnsavedChanges)
        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, repository.saveCalls.size)
        val savedIngredients = repository.saveCalls.first().request.ingredients
        assertEquals(0.125, savedIngredients[0].amount)
        assertTrue(savedIngredients[0].isSpecified)
        assertEquals(1, savedIngredients[0].substitutes.size)
        assertEquals(0.125, savedIngredients[0].substitutes.first().amount)
        assertEquals("", savedIngredients[1].units)
    }

    @Test
    fun `fresher server response overwrites stale cache when clean, but preserves user edits when dirty`() = runTest(testDispatcher) {
        val cached = sampleCocktailDetail(42).copy(name = "Cached Negroni")
        val fresh = sampleCocktailDetail(42).copy(name = "Fresh Server Negroni")
        val refreshGate = CompletableDeferred<Result<com.ryanvidal.barfriend.core.model.CocktailDetail>>()
        val repository = FakeCocktailRepository(
            initialDetails = mapOf(42 to cached),
        ).apply {
            refreshSuspendOverride = { refreshGate.await() }
        }

        val cleanViewModel = CocktailEditorViewModel(42, null, repository)
        testDispatcher.scheduler.runCurrent()

        assertEquals("Cached Negroni", cleanViewModel.uiState.value.name)
        assertFalse(cleanViewModel.uiState.value.hasUnsavedChanges)

        refreshGate.complete(Result.success(fresh))
        advanceUntilIdle()

        assertEquals("Fresh Server Negroni", cleanViewModel.uiState.value.name)
        assertFalse(cleanViewModel.uiState.value.hasUnsavedChanges)

        // Now test dirty case where user edits before refresh finishes
        val dirtyGate = CompletableDeferred<Result<com.ryanvidal.barfriend.core.model.CocktailDetail>>()
        repository.refreshSuspendOverride = { dirtyGate.await() }
        val dirtyViewModel = CocktailEditorViewModel(42, null, repository)
        testDispatcher.scheduler.runCurrent()

        assertEquals("Cached Negroni", dirtyViewModel.uiState.value.name)
        dirtyViewModel.onNameChanged("User Edited Negroni")
        assertTrue(dirtyViewModel.uiState.value.hasUnsavedChanges)

        dirtyGate.complete(Result.success(fresh))
        advanceUntilIdle()

        assertEquals("User Edited Negroni", dirtyViewModel.uiState.value.name)
        assertTrue(dirtyViewModel.uiState.value.hasUnsavedChanges)
    }

    @Test
    fun `failed initial load cancels detail observer, disables save, and supports retry`() = runTest(testDispatcher) {
        val repository = FakeCocktailRepository().apply {
            refreshResultOverride = { Result.failure(IllegalStateException("Network offline")) }
        }
        val viewModel = CocktailEditorViewModel(
            cocktailId = 99,
            initialImportUrl = null,
            cocktailRepository = repository,
        )
        advanceUntilIdle()

        val failedState = viewModel.uiState.value
        assertFalse(failedState.isLoadingExisting)
        assertFalse(failedState.hasLoadedExisting)
        assertFalse(failedState.canSubmitSave)
        assertNotNull(failedState.errorMessage)
        assertEquals(0, repository.activeDetailObserverCount)

        // Attempting save while unloaded must be ignored
        viewModel.save()
        advanceUntilIdle()
        assertTrue(repository.saveCalls.isEmpty())

        // Retry recovers when cocktail becomes available
        repository.refreshResultOverride = null
        repository.setDetail(99, sampleCocktailDetail(99))
        viewModel.retryLoadExisting()
        advanceUntilIdle()

        val recoveredState = viewModel.uiState.value
        assertTrue(recoveredState.hasLoadedExisting)
        assertTrue(recoveredState.canSubmitSave)
        assertNull(recoveredState.errorMessage)
        assertEquals("Negroni", recoveredState.name)
    }

    @Test
    fun `PendingLocal equality uses localKey without comparing byte arrays`() {
        val first = EditorPhotoItem.PendingLocal(7L, byteArrayOf(1, 2, 3), "a.jpg", "image/jpeg")
        val second = EditorPhotoItem.PendingLocal(7L, byteArrayOf(9, 9, 9), "b.jpg", "image/png")
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }
}
