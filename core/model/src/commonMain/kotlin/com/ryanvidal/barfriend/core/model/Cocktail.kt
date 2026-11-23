package com.ryanvidal.barfriend.core.model

data class CocktailSummary(
    val id: Int,
    val slug: String,
    val name: String,
    val shortDescription: String?,
    val abv: Double?,
    val glassName: String?,
    val methodName: String?,
    val mainImageThumbUrl: String?,
    val isFavorite: Boolean,
    val inBarShelf: Boolean,
    val missingIngredientsCount: Int,
    val missingIngredientNames: List<String>,
    val tags: List<CocktailTag>,
)

data class CocktailDetail(
    val id: Int,
    val slug: String,
    val name: String,
    val shortDescription: String?,
    val instructions: String,
    val garnish: String?,
    val description: String?,
    val source: String?,
    val abv: Double?,
    val glass: Glass?,
    val method: CocktailMethod?,
    val images: List<CocktailImage>,
    val tags: List<CocktailTag>,
    val utensils: List<Utensil>,
    val ingredients: List<CocktailIngredient>,
    val isFavorite: Boolean,
    val inBarShelf: Boolean,
    val missingIngredientsCount: Int,
    val author: String? = null,
    val originBar: String? = null,
    val year: Int? = null,
)

data class CocktailIngredient(
    val id: Int,
    val ingredientId: Int,
    val ingredientSlug: String,
    val name: String,
    val sort: Int,
    val amount: Double?,
    val amountMax: Double?,
    val units: String?,
    val optional: Boolean,
    val note: String?,
    val inBarShelf: Boolean,
    val inBarShelfAsSubstitute: Boolean = false,
    val inBarShelfAsVariant: Boolean = false,
    val inBarShelfAsComplex: Boolean = false,
    val substitutes: List<IngredientSubstitute>,
) {
    val isAvailableInBar: Boolean
        get() = inBarShelf || inBarShelfAsSubstitute || inBarShelfAsVariant || inBarShelfAsComplex
}

data class IngredientSubstitute(
    val id: Int,
    val slug: String,
    val name: String,
    val inBarShelf: Boolean,
)

data class CocktailMethod(
    val id: Int,
    val name: String,
    val dilutionPercentage: Int?,
)

data class Glass(
    val id: Int,
    val name: String,
    val volume: Double?,
    val volumeUnits: String?,
)

data class CocktailTag(
    val id: Int,
    val name: String,
)

data class Utensil(
    val id: Int,
    val name: String,
)

data class CocktailImage(
    val id: Int,
    val url: String,
    val thumbUrl: String,
    val sort: Int,
    val placeholderHash: String?,
    val copyright: String?,
)
