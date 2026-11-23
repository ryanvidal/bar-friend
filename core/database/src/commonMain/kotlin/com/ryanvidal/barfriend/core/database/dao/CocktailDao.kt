package com.ryanvidal.barfriend.core.database.dao

import androidx.room3.Dao
import androidx.room3.Embedded
import androidx.room3.Query
import androidx.room3.Relation
import androidx.room3.Transaction
import androidx.room3.Upsert
import com.ryanvidal.barfriend.core.database.entity.CocktailEntity
import com.ryanvidal.barfriend.core.database.entity.CocktailIngredientEntity
import kotlinx.coroutines.flow.Flow

data class CocktailWithIngredients(
    @Embedded val cocktail: CocktailEntity,
    @Relation(
        parentColumns = ["id"],
        entityColumns = ["cocktailId"],
    )
    val ingredients: List<CocktailIngredientEntity>,
)

@Dao
interface CocktailDao {
    @Query("SELECT * FROM cocktails WHERE barId = :barId ORDER BY name COLLATE NOCASE ASC")
    fun observeCocktails(barId: Int): Flow<List<CocktailEntity>>

    @Transaction
    @Query("SELECT * FROM cocktails WHERE barId = :barId ORDER BY name COLLATE NOCASE ASC")
    fun observeCocktailsWithIngredients(barId: Int): Flow<List<CocktailWithIngredients>>

    @Query(
        """
        SELECT * FROM cocktails
        WHERE barId = :barId
          AND (:query = '' OR name LIKE '%' || :query || '%' OR shortDescription LIKE '%' || :query || '%')
        ORDER BY name COLLATE NOCASE ASC
        """,
    )
    fun searchCocktails(barId: Int, query: String): Flow<List<CocktailEntity>>

    @Transaction
    @Query("SELECT * FROM cocktails WHERE id = :cocktailId")
    fun observeCocktailWithIngredients(cocktailId: Int): Flow<CocktailWithIngredients?>

    @Query("SELECT * FROM cocktail_ingredients WHERE cocktailId = :cocktailId ORDER BY sort ASC")
    fun observeCocktailIngredients(cocktailId: Int): Flow<List<CocktailIngredientEntity>>

    @Upsert
    suspend fun upsertCocktails(cocktails: List<CocktailEntity>)

    @Upsert
    suspend fun upsertCocktail(cocktail: CocktailEntity)

    @Query("DELETE FROM cocktail_ingredients WHERE cocktailId = :cocktailId")
    suspend fun deleteCocktailIngredients(cocktailId: Int)

    @Upsert
    suspend fun upsertCocktailIngredients(ingredients: List<CocktailIngredientEntity>)

    @Transaction
    suspend fun replaceCocktailWithIngredients(
        cocktail: CocktailEntity,
        ingredients: List<CocktailIngredientEntity>,
    ) {
        upsertCocktail(cocktail)
        deleteCocktailIngredients(cocktail.id)
        if (ingredients.isNotEmpty()) {
            upsertCocktailIngredients(ingredients)
        }
    }

    @Query("UPDATE cocktails SET isFavorite = :isFavorite WHERE id = :cocktailId")
    suspend fun updateFavorite(cocktailId: Int, isFavorite: Boolean)

    @Transaction
    suspend fun replaceAllCocktailsForBar(
        barId: Int,
        cocktails: List<CocktailEntity>,
        ingredients: List<CocktailIngredientEntity>,
    ) {
        deleteCocktailsForBar(barId)
        if (cocktails.isNotEmpty()) {
            upsertCocktails(cocktails)
        }
        if (ingredients.isNotEmpty()) {
            upsertCocktailIngredients(ingredients)
        }
    }

    @Query("DELETE FROM cocktails WHERE id = :cocktailId")
    suspend fun deleteCocktailById(cocktailId: Int)

    @Query("DELETE FROM cocktails WHERE barId = :barId")
    suspend fun deleteCocktailsForBar(barId: Int)
}
