package com.ryanvidal.barfriend.core.data

import com.ryanvidal.barfriend.core.model.IngredientSummary
import com.ryanvidal.barfriend.core.model.RecommendedIngredient
import com.ryanvidal.barfriend.core.model.ShoppingListItem
import com.ryanvidal.barfriend.core.model.UnlockedCocktail
import kotlinx.coroutines.flow.Flow

interface InventoryRepository {
    fun observeBarShelfIngredients(): Flow<List<IngredientSummary>>
    fun observeShoppingList(): Flow<List<ShoppingListItem>>
    fun observeShoppingListIds(): Flow<Set<Int>>

    suspend fun syncShoppingList(): Result<Unit>
    suspend fun setInBarShelf(ingredientIds: List<Int>, inStock: Boolean): Result<Unit>
    suspend fun addToShoppingList(ingredientIds: List<Int>, quantity: Int = 1): Result<Unit>
    suspend fun updateShoppingListQuantity(ingredientId: Int, quantity: Int): Result<Unit>
    suspend fun removeFromShoppingList(ingredientIds: List<Int>): Result<Unit>
    suspend fun markPurchasedToBarShelf(ingredientIds: List<Int>): Result<Unit>
    suspend fun getRecommendedIngredients(): Result<List<RecommendedIngredient>>
    suspend fun getUnlockedCocktails(ingredientId: Int): Result<List<UnlockedCocktail>>
}
