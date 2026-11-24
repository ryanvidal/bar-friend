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
import com.ryanvidal.barfriend.core.database.entity.ShoppingListEntity
import com.ryanvidal.barfriend.core.database.entity.SyncMetadataEntity
import com.ryanvidal.barfriend.core.database.entity.TagEntity
import com.ryanvidal.barfriend.core.database.entity.UtensilEntity

data class CocktailAvailabilityUpdate(
    val cocktailId: Int,
    val inBarShelf: Boolean,
    val missingIngredientsCount: Int,
    val missingIngredientNamesJson: String,
)

data class CocktailIngredientAvailabilityUpdate(
    val cocktailId: Int,
    val ingredientId: Int,
    val sort: Int,
    val inBarShelf: Boolean,
    val inBarShelfAsSubstitute: Boolean,
    val inBarShelfAsVariant: Boolean,
    val inBarShelfAsComplex: Boolean,
    val substitutesJson: String,
)

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

    @Query("DELETE FROM shopping_list WHERE barId = :barId")
    suspend fun deleteShoppingListForBar(barId: Int)

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
    suspend fun upsertShoppingListItems(items: List<ShoppingListEntity>)

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
        shoppingListItems: List<ShoppingListEntity>? = null,
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
        if (shoppingListItems != null) {
            deleteShoppingListForBar(barId)
            val distinctShopping = shoppingListItems.distinctBy { it.ingredientId }
            if (distinctShopping.isNotEmpty()) {
                upsertShoppingListItems(distinctShopping)
            }
        }
        upsertSyncMetadata(
            SyncMetadataEntity(
                key = syncKey,
                lastSyncedEpochMillis = syncedAtEpochMillis,
            ),
        )
    }

    @Query("DELETE FROM bar_shelf WHERE barId = :barId AND ingredientId IN (:ingredientIds)")
    suspend fun deleteBarShelfItemsChunk(barId: Int, ingredientIds: List<Int>)

    @Query(
        """
        UPDATE cocktails
        SET inBarShelf = :inBarShelf,
            missingIngredientsCount = :missingIngredientsCount,
            missingIngredientNamesJson = :missingIngredientNamesJson
        WHERE barId = :barId AND id = :cocktailId
        """,
    )
    suspend fun updateCocktailAvailability(
        barId: Int,
        cocktailId: Int,
        inBarShelf: Boolean,
        missingIngredientsCount: Int,
        missingIngredientNamesJson: String,
    )

    @Query(
        """
        UPDATE cocktail_ingredients
        SET inBarShelf = :inBarShelf,
            inBarShelfAsSubstitute = :inBarShelfAsSubstitute,
            inBarShelfAsVariant = :inBarShelfAsVariant,
            inBarShelfAsComplex = :inBarShelfAsComplex,
            substitutesJson = :substitutesJson
        WHERE cocktailId = :cocktailId AND ingredientId = :ingredientId AND sort = :sort
        """,
    )
    suspend fun updateCocktailIngredientAvailability(
        cocktailId: Int,
        ingredientId: Int,
        sort: Int,
        inBarShelf: Boolean,
        inBarShelfAsSubstitute: Boolean,
        inBarShelfAsVariant: Boolean,
        inBarShelfAsComplex: Boolean,
        substitutesJson: String,
    )

    @Transaction
    suspend fun applyBarShelfAndAvailability(
        barId: Int,
        replaceAllShelfIds: List<Int>? = null,
        addShelfIds: List<Int> = emptyList(),
        removeShelfIds: List<Int> = emptyList(),
        cocktailUpdates: List<CocktailAvailabilityUpdate>,
        ingredientUpdates: List<CocktailIngredientAvailabilityUpdate>,
    ) {
        if (replaceAllShelfIds != null) {
            deleteBarShelfForBar(barId)
            val distinctShelf = replaceAllShelfIds.distinct()
            if (distinctShelf.isNotEmpty()) {
                upsertBarShelfItems(distinctShelf.map { BarShelfEntity(barId = barId, ingredientId = it) })
            }
        } else {
            if (addShelfIds.isNotEmpty()) {
                upsertBarShelfItems(addShelfIds.distinct().map { BarShelfEntity(barId = barId, ingredientId = it) })
            }
            if (removeShelfIds.isNotEmpty()) {
                removeShelfIds.distinct().chunked(900).forEach { chunk ->
                    deleteBarShelfItemsChunk(barId, chunk)
                }
            }
        }
        ingredientUpdates.forEach { update ->
            updateCocktailIngredientAvailability(
                cocktailId = update.cocktailId,
                ingredientId = update.ingredientId,
                sort = update.sort,
                inBarShelf = update.inBarShelf,
                inBarShelfAsSubstitute = update.inBarShelfAsSubstitute,
                inBarShelfAsVariant = update.inBarShelfAsVariant,
                inBarShelfAsComplex = update.inBarShelfAsComplex,
                substitutesJson = update.substitutesJson,
            )
        }
        cocktailUpdates.forEach { update ->
            updateCocktailAvailability(
                barId = barId,
                cocktailId = update.cocktailId,
                inBarShelf = update.inBarShelf,
                missingIngredientsCount = update.missingIngredientsCount,
                missingIngredientNamesJson = update.missingIngredientNamesJson,
            )
        }
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
