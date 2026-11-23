package com.ryanvidal.barfriend.core.data

import com.ryanvidal.barfriend.core.database.dao.CocktailWithIngredients
import com.ryanvidal.barfriend.core.model.CocktailFilter
import com.ryanvidal.barfriend.core.model.CocktailMethod
import com.ryanvidal.barfriend.core.model.CocktailSort
import com.ryanvidal.barfriend.core.model.Glass
import com.ryanvidal.barfriend.core.network.dto.CocktailDto
import com.ryanvidal.barfriend.core.network.dto.CocktailIngredientDto
import com.ryanvidal.barfriend.core.network.dto.CocktailIngredientSubstituteDto
import com.ryanvidal.barfriend.core.network.dto.CocktailMethodDto
import com.ryanvidal.barfriend.core.network.dto.GlassDto
import com.ryanvidal.barfriend.core.network.dto.ImageDto
import com.ryanvidal.barfriend.core.network.dto.IngredientBasicDto
import com.ryanvidal.barfriend.core.network.dto.ScrapeResponseDto
import com.ryanvidal.barfriend.core.network.dto.ScrapedImageDto
import com.ryanvidal.barfriend.core.network.dto.ScrapedIngredientDto
import com.ryanvidal.barfriend.core.network.dto.ScrapedSchemaDto
import com.ryanvidal.barfriend.core.network.dto.ScraperMetaDto
import com.ryanvidal.barfriend.core.network.dto.TagDto
import com.ryanvidal.barfriend.core.network.dto.UtensilDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CocktailFilterAndMapperTest {

    @Test
    fun `cocktail dto mapping excludes optional and substitute-satisfied ingredients from missing count`() {
        val dto = CocktailDto(
            id = 10,
            slug = "negroni-10",
            name = "Negroni",
            instructions = "Stir all ingredients with ice.",
            garnish = "Orange peel",
            description = "Classic bitter Italian aperitif.",
            abv = 24.5,
            images = listOf(
                ImageDto(
                    id = 2,
                    url = "/uploads/2.jpg",
                    thumbUrl = "/uploads/2_thumb.jpg",
                    sort = 2,
                ),
                ImageDto(
                    id = 1,
                    url = "/uploads/1.jpg",
                    thumbUrl = "/uploads/1_thumb.jpg",
                    sort = 1,
                    copyright = "Studio",
                ),
            ),
            tags = listOf(TagDto(1, "Classic"), TagDto(2, "Bitter")),
            utensils = listOf(UtensilDto(5, "Mixing Glass")),
            glass = GlassDto(id = 3, name = "Rocks", volume = 250.0, volumeUnits = "ml"),
            method = CocktailMethodDto(id = 4, name = "Stir", dilutionPercentage = 20),
            ingredients = listOf(
                CocktailIngredientDto(
                    sort = 1,
                    amount = 30.0,
                    units = "ml",
                    ingredient = IngredientBasicDto(1, "gin", "London Dry Gin"),
                    inBarShelf = true,
                ),
                CocktailIngredientDto(
                    sort = 2,
                    amount = 30.0,
                    units = "ml",
                    ingredient = IngredientBasicDto(2, "sweet-vermouth", "Sweet Vermouth"),
                    inBarShelf = false,
                    inBarShelfAsSubstitute = true,
                    substitutes = listOf(
                        CocktailIngredientSubstituteDto(
                            ingredient = IngredientBasicDto(20, "punt-e-mes", "Punt e Mes"),
                            inBarShelf = true,
                        ),
                    ),
                ),
                CocktailIngredientDto(
                    sort = 3,
                    amount = 30.0,
                    units = "ml",
                    ingredient = IngredientBasicDto(3, "campari", "Campari"),
                    inBarShelf = false,
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
            inBarShelf = false,
            isFavorited = true,
        )

        val (entity, ingredientEntities) = dto.toEntityAndIngredients(
            barId = 7,
            serverUrl = "https://bar.example.com",
        )
        assertEquals(1, entity.missingIngredientsCount)
        assertFalse(entity.inBarShelf)
        assertEquals("https://bar.example.com/uploads/1_thumb.jpg", entity.mainImageThumbUrl)
        assertTrue(entity.isFavorite)

        val relation = CocktailWithIngredients(entity, ingredientEntities)
        val summary = relation.toCocktailSummary()
        assertEquals(listOf("Campari"), summary.missingIngredientNames)
        assertEquals(1, summary.missingIngredientsCount)
        assertEquals(listOf("Classic", "Bitter"), summary.tags.map { it.name })

        val detail = relation.toCocktailDetail(
            glass = Glass(3, "Rocks", 250.0, "ml"),
            method = CocktailMethod(4, "Stir", 20),
        )
        assertEquals(4, detail.ingredients.size)
        assertEquals(listOf(1, 2), detail.images.map { it.sort })
        assertEquals("Mixing Glass", detail.utensils.single().name)
        assertEquals(20, detail.method?.dilutionPercentage)
        assertTrue(detail.ingredients[1].isAvailableInBar)
        assertEquals("Punt e Mes", detail.ingredients[1].substitutes.single().name)
    }

    @Test
    fun `cocktail dto with variant or complex shelf flags is counted as makeable`() {
        val dto = CocktailDto(
            id = 11,
            slug = "martini",
            name = "Dry Martini",
            instructions = "Stir.",
            ingredients = listOf(
                CocktailIngredientDto(
                    sort = 1,
                    amount = 60.0,
                    units = "ml",
                    ingredient = IngredientBasicDto(1, "gin", "Gin"),
                    inBarShelfAsVariant = true,
                ),
                CocktailIngredientDto(
                    sort = 2,
                    amount = 10.0,
                    units = "ml",
                    ingredient = IngredientBasicDto(2, "vermouth", "Dry Vermouth"),
                    inBarShelfAsComplexIngredient = true,
                ),
            ),
            inBarShelf = false,
        )

        val (entity, ingredients) = dto.toEntityAndIngredients(barId = 1)
        assertEquals(0, entity.missingIngredientsCount)
        assertTrue(entity.inBarShelf)
        assertEquals(emptyList(), CocktailWithIngredients(entity, ingredients).toCocktailSummary().missingIngredientNames)
    }

    @Test
    fun `filterAndSortCocktails filters across query, shelf, favorites, tags, glass, method, and abv`() {
        val negroni = relationItem(
            id = 1,
            name = "Negroni",
            shortDescription = "Bitter gin aperitif",
            abv = 24.0,
            glassId = 1,
            methodId = 1,
            isFavorite = true,
            missingNames = emptyList(),
            tags = listOf(TagDto(1, "Classic"), TagDto(2, "Bitter")),
            ingredientNames = listOf("Gin", "Campari", "Sweet Vermouth"),
        )
        val daiquiri = relationItem(
            id = 2,
            name = "Daiquiri",
            shortDescription = "Crisp lime sour",
            abv = 19.5,
            glassId = 2,
            methodId = 2,
            isFavorite = false,
            missingNames = listOf("White Rum"),
            tags = listOf(TagDto(1, "Classic"), TagDto(3, "Sour")),
            ingredientNames = listOf("White Rum", "Lime Juice", "Simple Syrup"),
        )
        val mocktail = relationItem(
            id = 3,
            name = "Citrus Fizz",
            shortDescription = "Refreshing zero proof",
            abv = null,
            glassId = 3,
            methodId = 2,
            isFavorite = true,
            missingNames = listOf("Grapefruit Soda", "Yuzu"),
            tags = listOf(TagDto(4, "Zero Proof")),
            ingredientNames = listOf("Grapefruit Soda", "Yuzu"),
        )
        val items = listOf(negroni, daiquiri, mocktail)

        // Query matches name, short description, tag name, or ingredient name
        assertEquals(
            listOf("Negroni"),
            filterAndSortCocktails(items, CocktailFilter(query = "campari")).map { it.name },
        )
        assertEquals(
            listOf("Daiquiri"),
            filterAndSortCocktails(items, CocktailFilter(query = "sour")).map { it.name },
        )
        assertEquals(
            listOf("Citrus Fizz"),
            filterAndSortCocktails(items, CocktailFilter(query = "zero proof")).map { it.name },
        )

        // Can make & favorites
        assertEquals(
            listOf("Negroni"),
            filterAndSortCocktails(items, CocktailFilter(canMakeOnly = true)).map { it.name },
        )
        assertEquals(
            listOf("Citrus Fizz", "Negroni"),
            filterAndSortCocktails(items, CocktailFilter(favoritesOnly = true)).map { it.name },
        )

        // Tags (requires all selected tags), glass, and method
        assertEquals(
            listOf("Negroni"),
            filterAndSortCocktails(items, CocktailFilter(tagIds = setOf(1, 2))).map { it.name },
        )
        assertEquals(
            listOf("Daiquiri"),
            filterAndSortCocktails(items, CocktailFilter(glassId = 2, methodId = 2)).map { it.name },
        )

        // ABV range excludes null ABV
        assertEquals(
            listOf("Daiquiri", "Negroni"),
            filterAndSortCocktails(items, CocktailFilter(abvMin = 15.0, abvMax = 25.0)).map { it.name },
        )
    }

    @Test
    fun `filterAndSortCocktails applies all sort orders deterministically`() {
        val a = relationItem(
            id = 1,
            name = "Aviation",
            abv = 21.0,
            missingNames = listOf("Creme de Violette"),
            ingredientNames = listOf("Gin", "Creme de Violette"),
        )
        val b = relationItem(
            id = 2,
            name = "Boulevardier",
            abv = 26.0,
            missingNames = emptyList(),
            ingredientNames = listOf("Bourbon", "Campari"),
        )
        val c = relationItem(
            id = 3,
            name = "cucumber cooler",
            abv = null,
            missingNames = listOf("Gin", "Cucumber"),
            ingredientNames = listOf("Gin", "Cucumber"),
        )
        val items = listOf(b, c, a)

        assertEquals(
            listOf("Aviation", "Boulevardier", "cucumber cooler"),
            filterAndSortCocktails(items, CocktailFilter(sort = CocktailSort.NAME_ASC)).map { it.name },
        )
        assertEquals(
            listOf("cucumber cooler", "Boulevardier", "Aviation"),
            filterAndSortCocktails(items, CocktailFilter(sort = CocktailSort.NAME_DESC)).map { it.name },
        )
        assertEquals(
            listOf("Boulevardier", "Aviation", "cucumber cooler"),
            filterAndSortCocktails(items, CocktailFilter(sort = CocktailSort.ABV_DESC)).map { it.name },
        )
        assertEquals(
            listOf("Aviation", "Boulevardier", "cucumber cooler"),
            filterAndSortCocktails(items, CocktailFilter(sort = CocktailSort.ABV_ASC)).map { it.name },
        )
        assertEquals(
            listOf("Boulevardier", "Aviation", "cucumber cooler"),
            filterAndSortCocktails(items, CocktailFilter(sort = CocktailSort.MISSING_ASC)).map { it.name },
        )
    }

    @Test
    fun `scrape response maps scraper meta source line onto ingredients`() {
        val dto = ScrapeResponseDto(
            schemaVersion = "2",
            schema = ScrapedSchemaDto(
                name = "Margarita",
                instructions = "Shake and strain.",
                glass = "Margarita",
                method = "Shake",
                tags = listOf("Tequila"),
                images = listOf(ScrapedImageDto("https://example.com/marg.jpg", "Photographer")),
                ingredients = listOf(
                    ScrapedIngredientDto(name = "Blanco Tequila", amount = 2.0, units = "oz"),
                    ScrapedIngredientDto(name = "Lime Juice", amount = 1.0, units = "oz"),
                ),
            ),
            scraperMeta = listOf(
                ScraperMetaDto(ingredientName = "blanco tequila", source = "2 oz blanco tequila"),
            ),
        )

        val mapped = dto.toModel()
        assertEquals("Margarita", mapped.name)
        assertEquals("2 oz blanco tequila", mapped.ingredients[0].sourceLine)
        assertNull(mapped.ingredients[1].sourceLine)
    }

    private fun relationItem(
        id: Int,
        name: String,
        shortDescription: String? = null,
        abv: Double? = null,
        glassId: Int? = null,
        methodId: Int? = null,
        isFavorite: Boolean = false,
        missingNames: List<String> = emptyList(),
        tags: List<TagDto> = emptyList(),
        ingredientNames: List<String> = emptyList(),
    ): CocktailWithIngredients {
        val dto = CocktailDto(
            id = id,
            slug = "${name.lowercase().replace(' ', '-')}-$id",
            name = name,
            instructions = "Mix.",
            description = shortDescription,
            abv = abv,
            tags = tags,
            glass = glassId?.let { GlassDto(it, "Glass $it") },
            method = methodId?.let { CocktailMethodDto(it, "Method $it") },
            ingredients = ingredientNames.mapIndexed { index, ingName ->
                CocktailIngredientDto(
                    sort = index + 1,
                    amount = 30.0,
                    units = "ml",
                    ingredient = IngredientBasicDto(
                        id = id * 100 + index,
                        slug = ingName.lowercase().replace(' ', '-'),
                        name = ingName,
                    ),
                    inBarShelf = ingName !in missingNames,
                )
            },
            inBarShelf = missingNames.isEmpty() && ingredientNames.isNotEmpty(),
            isFavorited = isFavorite,
        )
        val (entity, ingredients) = dto.toEntityAndIngredients(barId = 1)
        return CocktailWithIngredients(entity, ingredients)
    }
}
