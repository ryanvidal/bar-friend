package com.ryanvidal.barfriend.core.database.entity

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "cocktails",
    indices = [
        Index(value = ["barId", "name"]),
        Index(value = ["barId", "slug"]),
    ],
)
data class CocktailEntity(
    @PrimaryKey val id: Int,
    val barId: Int,
    val slug: String,
    val name: String,
    val shortDescription: String?,
    val instructions: String,
    val garnish: String?,
    val description: String?,
    val source: String?,
    val abv: Double?,
    val glassId: Int?,
    val glassName: String?,
    val methodId: Int?,
    val methodName: String?,
    val mainImageThumbUrl: String?,
    val mainImageUrl: String?,
    val imagesJson: String,
    val isFavorite: Boolean,
    val inBarShelf: Boolean,
    val missingIngredientsCount: Int,
    val missingIngredientNamesJson: String,
    val tagsJson: String,
    val utensilsJson: String,
    val author: String?,
    val originBar: String?,
    val year: Int?,
    val updatedAt: String?,
)

@Entity(
    tableName = "cocktail_ingredients",
    primaryKeys = ["cocktailId", "ingredientId", "sort"],
    foreignKeys = [
        ForeignKey(
            entity = CocktailEntity::class,
            parentColumns = ["id"],
            childColumns = ["cocktailId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["cocktailId"]), Index(value = ["ingredientId"])],
)
data class CocktailIngredientEntity(
    val cocktailId: Int,
    val ingredientId: Int,
    val ingredientSlug: String,
    val ingredientName: String,
    val sort: Int,
    val amount: Double?,
    val amountMax: Double?,
    val units: String?,
    val optional: Boolean,
    val note: String?,
    val inBarShelf: Boolean,
    val inBarShelfAsSubstitute: Boolean,
    val inBarShelfAsVariant: Boolean,
    val inBarShelfAsComplex: Boolean,
    val substitutesJson: String,
)

@Entity(
    tableName = "ingredients",
    indices = [Index(value = ["barId", "name"])],
)
data class IngredientEntity(
    @PrimaryKey val id: Int,
    val barId: Int,
    val slug: String,
    val name: String,
    val strength: Double,
    val description: String?,
    val color: String?,
    val categoryName: String?,
    val parentIngredientId: Int?,
    val mainImageThumbUrl: String?,
)

@Entity(
    tableName = "bar_shelf",
    primaryKeys = ["barId", "ingredientId"],
)
data class BarShelfEntity(
    val barId: Int,
    val ingredientId: Int,
)

@Entity(
    tableName = "shopping_list",
    primaryKeys = ["barId", "ingredientId"],
)
data class ShoppingListEntity(
    val barId: Int,
    val ingredientId: Int,
    val quantity: Int,
)

@Entity(
    tableName = "glasses",
    indices = [Index(value = ["barId"])],
)
data class GlassEntity(
    @PrimaryKey val id: Int,
    val barId: Int,
    val name: String,
    val volume: Double?,
    val volumeUnits: String?,
)

@Entity(
    tableName = "cocktail_methods",
    indices = [Index(value = ["barId"])],
)
data class CocktailMethodEntity(
    @PrimaryKey val id: Int,
    val barId: Int,
    val name: String,
    val dilutionPercentage: Int?,
)

@Entity(
    tableName = "tags",
    indices = [Index(value = ["barId"])],
)
data class TagEntity(
    @PrimaryKey val id: Int,
    val barId: Int,
    val name: String,
)

@Entity(
    tableName = "utensils",
    indices = [Index(value = ["barId"])],
)
data class UtensilEntity(
    @PrimaryKey val id: Int,
    val barId: Int,
    val name: String,
)

@Entity(tableName = "sync_metadata")
data class SyncMetadataEntity(
    @PrimaryKey val key: String,
    val lastSyncedEpochMillis: Long,
)
