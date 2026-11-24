package com.ryanvidal.barfriend.core.model

data class CocktailUpsertRequest(
    val name: String,
    val instructions: String,
    val description: String? = null,
    val garnish: String? = null,
    val source: String? = null,
    val glassId: Int? = null,
    val methodId: Int? = null,
    val tags: List<String> = emptyList(),
    val utensilIds: List<Int> = emptyList(),
    val imageIds: List<Int> = emptyList(),
    val ingredients: List<CocktailIngredientInput> = emptyList(),
)

data class CocktailIngredientInput(
    val ingredientId: Int,
    val ingredientName: String,
    val amount: Double,
    val amountMax: Double? = null,
    val units: String,
    val sort: Int = 0,
    val optional: Boolean = false,
    val isSpecified: Boolean = false,
    val note: String? = null,
    val substitutes: List<IngredientSubstituteInput> = emptyList(),
)

data class IngredientSubstituteInput(
    val ingredientId: Int,
    val ingredientName: String,
    val amount: Double? = null,
    val amountMax: Double? = null,
    val units: String? = null,
)

data class UploadedImage(
    val id: Int,
    val url: String,
    val thumbUrl: String,
    val sort: Int,
    val copyright: String? = null,
)

data class ScrapedCocktail(
    val name: String,
    val instructions: String,
    val description: String? = null,
    val garnish: String? = null,
    val source: String? = null,
    val tags: List<String> = emptyList(),
    val glassName: String? = null,
    val methodName: String? = null,
    val images: List<ScrapedImage> = emptyList(),
    val ingredients: List<ScrapedCocktailIngredient> = emptyList(),
)

data class ScrapedImage(
    val uri: String,
    val copyright: String? = null,
)

data class ScrapedCocktailIngredient(
    val name: String,
    val amount: Double? = null,
    val amountMax: Double? = null,
    val units: String? = null,
    val note: String? = null,
    val sourceLine: String? = null,
)
