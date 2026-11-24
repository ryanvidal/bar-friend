package com.ryanvidal.barfriend.core.database.dao

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.ryanvidal.barfriend.core.database.entity.BarShelfEntity
import com.ryanvidal.barfriend.core.database.entity.CocktailEntity
import com.ryanvidal.barfriend.core.database.entity.CocktailIngredientEntity
import com.ryanvidal.barfriend.core.database.entity.CocktailMethodEntity
import com.ryanvidal.barfriend.core.database.entity.GlassEntity
import com.ryanvidal.barfriend.core.database.entity.IngredientEntity
import com.ryanvidal.barfriend.core.database.entity.SyncMetadataEntity
import com.ryanvidal.barfriend.core.database.entity.TagEntity
import com.ryanvidal.barfriend.core.database.entity.UtensilEntity

@Dao
interface SyncDao {
    @Query("DELETE FROM cocktails WHERE barId = :barId")
    suspend fun deleteCocktailsForBar(barId: Int)

    @Query("DELETE FROM ingredients WHERE barId = :barId")
    suspend fun deleteIngredientsForBar(barId: Int)

    @Query("DELETE FROM glasses WHERE barId = :barId")
    suspend fun deleteGlassesForBar(barId: Int)

    @Query("DELETE FROM cocktail_methods WHERE barId = :barId")
    suspend fun deleteMethodsForBar(barId: Int)

    @Query("DELETE FROM tags WHERE barId = :barId")
    suspend fun deleteTagsForBar(barId: Int)

    @Query("DELETE FROM utensils WHERE barId = :barId")
    suspend fun deleteUtensilsForBar(barId: Int)

    @Query("DELETE FROM bar_shelf WHERE barId = :barId")
    suspend fun deleteBarShelfForBar(barId: Int)

    @Upsert
    suspend fun upsertCocktails(cocktails: List<CocktailEntity>)

    @Upsert
    suspend fun upsertCocktailIngredients(ingredients: List<CocktailIngredientEntity>)

    @Upsert
    suspend fun upsertIngredients(ingredients: List<IngredientEntity>)

    @Upsert
    suspend fun upsertGlasses(glasses: List<GlassEntity>)

    @Upsert
    suspend fun upsertMethods(methods: List<CocktailMethodEntity>)

    @Upsert
    suspend fun upsertTags(tags: List<TagEntity>)

    @Upsert
    suspend fun upsertUtensils(utensils: List<UtensilEntity>)

    @Upsert
    suspend fun upsertBarShelfItems(items: List<BarShelfEntity>)

    @Upsert
    suspend fun upsertSyncMetadata(metadata: SyncMetadataEntity)

    @Query("DELETE FROM cocktail_ingredients")
    suspend fun deleteAllCocktailIngredients()

    @Query("DELETE FROM cocktails")
    suspend fun deleteAllCocktails()

    @Query("DELETE FROM bar_shelf")
    suspend fun deleteAllBarShelf()

    @Query("DELETE FROM shopping_list")
    suspend fun deleteAllShoppingList()

    @Query("DELETE FROM ingredients")
    suspend fun deleteAllIngredients()

    @Query("DELETE FROM glasses")
    suspend fun deleteAllGlasses()

    @Query("DELETE FROM cocktail_methods")
    suspend fun deleteAllMethods()

    @Query("DELETE FROM tags")
    suspend fun deleteAllTags()

    @Query("DELETE FROM utensils")
    suspend fun deleteAllUtensils()

    @Query("DELETE FROM sync_metadata")
    suspend fun deleteAllSyncMetadata()

    @Transaction
    suspend fun replaceBarSnapshot(
        barId: Int,
        cocktails: List<CocktailEntity>,
        cocktailIngredients: List<CocktailIngredientEntity>,
        ingredients: List<IngredientEntity>,
        glasses: List<GlassEntity>,
        methods: List<CocktailMethodEntity>,
        tags: List<TagEntity>,
        utensils: List<UtensilEntity>,
        shelfIngredientIds: List<Int>,
        syncKey: String,
        syncedAtEpochMillis: Long,
    ) {
        deleteCocktailsForBar(barId)
        deleteIngredientsForBar(barId)
        deleteGlassesForBar(barId)
        deleteMethodsForBar(barId)
        deleteTagsForBar(barId)
        deleteUtensilsForBar(barId)
        deleteBarShelfForBar(barId)

        if (cocktails.isNotEmpty()) {
            upsertCocktails(cocktails)
        }
        if (cocktailIngredients.isNotEmpty()) {
            upsertCocktailIngredients(cocktailIngredients)
        }
        if (ingredients.isNotEmpty()) {
            upsertIngredients(ingredients)
        }
        if (glasses.isNotEmpty()) {
            upsertGlasses(glasses)
        }
        if (methods.isNotEmpty()) {
            upsertMethods(methods)
        }
        if (tags.isNotEmpty()) {
            upsertTags(tags)
        }
        if (utensils.isNotEmpty()) {
            upsertUtensils(utensils)
        }
        val distinctShelf = shelfIngredientIds.distinct()
        if (distinctShelf.isNotEmpty()) {
            upsertBarShelfItems(distinctShelf.map { BarShelfEntity(barId = barId, ingredientId = it) })
        }
        upsertSyncMetadata(
            SyncMetadataEntity(
                key = syncKey,
                lastSyncedEpochMillis = syncedAtEpochMillis,
            ),
        )
    }

    @Transaction
    suspend fun clearAllData() {
        deleteAllCocktailIngredients()
        deleteAllCocktails()
        deleteAllBarShelf()
        deleteAllShoppingList()
        deleteAllIngredients()
        deleteAllGlasses()
        deleteAllMethods()
        deleteAllTags()
        deleteAllUtensils()
        deleteAllSyncMetadata()
    }
}
