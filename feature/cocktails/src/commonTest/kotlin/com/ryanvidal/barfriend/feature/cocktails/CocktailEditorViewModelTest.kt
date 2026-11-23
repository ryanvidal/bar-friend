package com.ryanvidal.barfriend.feature.cocktails

import com.ryanvidal.barfriend.core.model.ScrapedCocktail
import com.ryanvidal.barfriend.core.model.ScrapedCocktailIngredient
import com.ryanvidal.barfriend.core.model.ScrapedImage
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
    fun `validates blank name, blank instructions, and unmatched or invalid ingredients before saving`() = runTest(testDispatcher) {
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

        // Matched ingredient but invalid amount (<= 0)
        viewModel.updateIngredientSelection(rowKey, sampleCatalog().ingredients.first())
        viewModel.updateIngredientRow(rowKey, amountText = "0")
        viewModel.save()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(repository.saveCalls.isEmpty())
    }

    @Test
    fun `URL scrape auto matches glass, method, and catalog ingredients while flagging unmatched ones`() = runTest(testDispatcher) {
        val repository = FakeCocktailRepository().apply {
            scrapeResult = Result.success(
                ScrapedCocktail(
                    name = "Last Word",
                    instructions = "Shake all ingredients with ice and double strain.",
                    garnish = "Brandied cherry",
                    source = "https://example.com/last-word",
                    tags = listOf("classic", "Herbal"),
                    glassName = "  coupe ",
                    methodName = "SHAKE",
                    images = listOf(ScrapedImage("https://example.com/last-word.jpg", "Example Photo")),
                    ingredients = listOf(
                        ScrapedCocktailIngredient(
                            name = "  london dry gin ",
                            amount = 22.5,
                            units = "ml",
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
            initialImportUrl = "https://example.com/last-word",
            cocktailRepository = repository,
        )
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("https://example.com/last-word"), repository.scrapedUrls)
        assertEquals("Last Word", state.name)
        assertEquals(11, state.glassId)
        assertEquals(21, state.methodId)
        assertEquals(listOf("Classic", "Herbal"), state.tags)
        assertEquals(2, state.ingredients.size)

        val matchedRow = state.ingredients[0]
        assertEquals(1, matchedRow.ingredientId)
        assertEquals("London Dry Gin", matchedRow.ingredientName)
        assertNull(matchedRow.unmatchedScrapedHint)

        val unmatchedRow = state.ingredients[1]
        assertNull(unmatchedRow.ingredientId)
        assertEquals("Green Chartreuse", unmatchedRow.ingredientName)
        assertEquals("3/4 oz Green Chartreuse", unmatchedRow.unmatchedScrapedHint)

        assertEquals(1, state.photos.size)
        assertTrue(state.photos.first() is EditorPhotoItem.PendingRemote)

        // Saving while unmatched row exists must fail validation
        viewModel.save()
        advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.errorMessage)
        assertTrue(repository.saveCalls.isEmpty())

        // Matching the unmatched row clears the hint
        viewModel.updateIngredientSelection(unmatchedRow.localKey, sampleCatalog().ingredients[3])
        assertNull(viewModel.uiState.value.ingredients[1].unmatchedScrapedHint)
        assertEquals(4, viewModel.uiState.value.ingredients[1].ingredientId)
    }

    @Test
    fun `save uploads local and remote photos in order and calls saveCocktail`() = runTest(testDispatcher) {
        val repository = FakeCocktailRepository()
        val viewModel = CocktailEditorViewModel(
            cocktailId = null,
            initialImportUrl = null,
            cocktailRepository = repository,
        )
        advanceUntilIdle()

        viewModel.onNameChanged("Gimlet")
        viewModel.onInstructionsChanged("1. Shake with ice.\n2. Strain into a chilled coupe.")
        viewModel.onGlassSelected(11)
        viewModel.onMethodSelected(21)
        viewModel.toggleTag("Classic")
        viewModel.addCustomTag("House Favorite")

        val ginKey = viewModel.addIngredientRow(sampleCatalog().ingredients[0])
        viewModel.updateIngredientRow(ginKey, amountText = "60", units = "ml")
        val limeKey = viewModel.addIngredientRow(sampleCatalog().ingredients[3])
        viewModel.updateIngredientRow(limeKey, amountText = "22.5", units = "ml", note = "Freshly squeezed")

        val sampleBytes = byteArrayOf(1, 2, 3, 4)
        viewModel.addLocalPhoto(sampleBytes, "gimlet.jpg", "image/jpeg")

        viewModel.save()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNull(state.errorMessage)
        assertNotNull(state.savedCocktailId)
        assertEquals(1, repository.localUploads.size)
        assertEquals("gimlet.jpg", repository.localUploads.first().fileName)
        assertEquals("image/jpeg", repository.localUploads.first().mimeType)
        assertEquals(1, repository.localUploads.first().sort)

        assertEquals(1, repository.saveCalls.size)
        val savedCall = repository.saveCalls.first()
        assertNull(savedCall.cocktailId)
        assertEquals("Gimlet", savedCall.request.name)
        assertEquals(11, savedCall.request.glassId)
        assertEquals(21, savedCall.request.methodId)
        assertEquals(listOf("Classic", "House Favorite"), savedCall.request.tags)
        assertEquals(listOf(500), savedCall.request.imageIds)
        assertEquals(2, savedCall.request.ingredients.size)
        assertEquals(60.0, savedCall.request.ingredients[0].amount)
        assertEquals(22.5, savedCall.request.ingredients[1].amount)
        assertEquals("Freshly squeezed", savedCall.request.ingredients[1].note)
    }

    @Test
    fun `editing existing cocktail pre populates fields and preserves existing image ids`() = runTest(testDispatcher) {
        val existing = sampleCocktailDetail(42)
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
        assertEquals("Negroni", state.name)
        assertEquals(10, state.glassId)
        assertEquals(20, state.methodId)
        assertEquals(3, state.ingredients.size)
        assertEquals(1, state.photos.size)
        assertTrue(state.photos.first() is EditorPhotoItem.Existing)

        viewModel.onNameChanged("Negroni Sbagliato")
        viewModel.save()
        advanceUntilIdle()

        assertEquals(1, repository.saveCalls.size)
        assertEquals(42, repository.saveCalls.first().cocktailId)
        assertEquals("Negroni Sbagliato", repository.saveCalls.first().request.name)
        assertEquals(listOf(101), repository.saveCalls.first().request.imageIds)
    }
}
