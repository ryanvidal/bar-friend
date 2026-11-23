package com.ryanvidal.barfriend.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.ryanvidal.barfriend.core.database.entity.BarShelfEntity
import com.ryanvidal.barfriend.core.database.entity.CocktailMethodEntity
import com.ryanvidal.barfriend.core.database.entity.GlassEntity
import com.ryanvidal.barfriend.core.database.entity.IngredientEntity
import com.ryanvidal.barfriend.core.database.entity.ShoppingListEntity
import com.ryanvidal.barfriend.core.database.entity.SyncMetadataEntity
import com.ryanvidal.barfriend.core.database.entity.TagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InventoryDao {
    @Query("SELECT ingredientId FROM bar_shelf WHERE barId = :barId")
    fun observeBarShelfIngredientIds(barId: Int): Flow<List<Int>>

    @Query("SELECT * FROM shopping_list WHERE barId = :barId")
    fun observeShoppingList(barId: Int): Flow<List<ShoppingListEntity>>

    @Upsert
    suspend fun upsertBarShelfItems(items: List<BarShelfEntity>)

    @Query("DELETE FROM bar_shelf WHERE barId = :barId AND ingredientId = :ingredientId")
    suspend fun deleteBarShelfItem(barId: Int, ingredientId: Int)

    @Query("DELETE FROM bar_shelf WHERE barId = :barId")
    suspend fun clearBarShelf(barId: Int)

    @Transaction
    suspend fun replaceBarShelf(barId: Int, ingredientIds: List<Int>) {
        clearBarShelf(barId)
        if (ingredientIds.isNotEmpty()) {
            upsertBarShelfItems(ingredientIds.map { BarShelfEntity(barId = barId, ingredientId = it) })
        }
    }

    @Upsert
    suspend fun upsertShoppingListItems(items: List<ShoppingListEntity>)

    @Query("DELETE FROM shopping_list WHERE barId = :barId AND ingredientId = :ingredientId")
    suspend fun deleteShoppingListItem(barId: Int, ingredientId: Int)

    @Query("DELETE FROM shopping_list WHERE barId = :barId")
    suspend fun clearShoppingList(barId: Int)

    @Transaction
    suspend fun replaceShoppingList(barId: Int, items: List<ShoppingListEntity>) {
        clearShoppingList(barId)
        if (items.isNotEmpty()) {
            upsertShoppingListItems(items)
        }
    }
}

@Dao
interface CatalogDao {
    @Query("SELECT * FROM ingredients WHERE barId = :barId ORDER BY name COLLATE NOCASE ASC")
    fun observeIngredients(barId: Int): Flow<List<IngredientEntity>>

    @Query("SELECT * FROM glasses WHERE barId = :barId ORDER BY name COLLATE NOCASE ASC")
    fun observeGlasses(barId: Int): Flow<List<GlassEntity>>

    @Query("SELECT * FROM cocktail_methods WHERE barId = :barId ORDER BY name COLLATE NOCASE ASC")
    fun observeMethods(barId: Int): Flow<List<CocktailMethodEntity>>

    @Query("SELECT * FROM tags WHERE barId = :barId ORDER BY name COLLATE NOCASE ASC")
    fun observeTags(barId: Int): Flow<List<TagEntity>>

    @Upsert
    suspend fun upsertIngredients(ingredients: List<IngredientEntity>)

    @Upsert
    suspend fun upsertGlasses(glasses: List<GlassEntity>)

    @Upsert
    suspend fun upsertMethods(methods: List<CocktailMethodEntity>)

    @Upsert
    suspend fun upsertTags(tags: List<TagEntity>)

    @Query("SELECT lastSyncedEpochMillis FROM sync_metadata WHERE `key` = :key")
    suspend fun getSyncTimestamp(key: String): Long?

    @Upsert
    suspend fun setSyncTimestamp(metadata: SyncMetadataEntity)
}
