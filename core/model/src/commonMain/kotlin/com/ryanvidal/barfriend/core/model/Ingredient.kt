package com.ryanvidal.barfriend.core.model

data class IngredientSummary(
    val id: Int,
    val slug: String,
    val name: String,
    val strength: Double,
    val categoryName: String?,
    val mainImageThumbUrl: String?,
    val inBarShelf: Boolean,
    val inShoppingList: Boolean,
)

data class Ingredient(
    val id: Int,
    val slug: String,
    val name: String,
    val strength: Double,
    val description: String?,
    val color: String?,
    val categoryName: String?,
    val parentIngredientId: Int?,
    val mainImageThumbUrl: String?,
    val inBarShelf: Boolean,
    val inShoppingList: Boolean,
)

data class ShoppingListItem(
    val ingredientId: Int,
    val ingredientSlug: String,
    val ingredientName: String,
    val categoryName: String?,
    val quantity: Int,
    val mainImageThumbUrl: String?,
)
