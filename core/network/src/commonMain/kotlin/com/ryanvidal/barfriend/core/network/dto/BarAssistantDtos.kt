package com.ryanvidal.barfriend.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DataEnvelope<T>(
    val data: T,
)

@Serializable
data class PaginatedEnvelope<T>(
    val data: List<T>,
    val meta: PaginationMetaDto? = null,
)

@Serializable
data class PaginationMetaDto(
    @SerialName("current_page") val currentPage: Int = 1,
    @SerialName("last_page") val lastPage: Int = 1,
    @SerialName("per_page") val perPage: Int = 25,
    val total: Int = 0,
)

@Serializable
data class ApiErrorDto(
    val type: String? = null,
    val message: String? = null,
)

@Serializable
data class ServerVersionDto(
    val version: String,
    @SerialName("latest_version") val latestVersion: String? = null,
    @SerialName("is_latest") val isLatest: Boolean? = null,
    val type: String? = null,
    @SerialName("is_password_login_enabled") val isPasswordLoginEnabled: Boolean = true,
    @SerialName("is_ai_enabled") val isAiEnabled: Boolean = false,
    @SerialName("is_ai_image_enabled") val isAiImageEnabled: Boolean = false,
)

@Serializable
data class LoginRequestDto(
    val email: String,
    val password: String,
    @SerialName("token_name") val tokenName: String = "Bar Friend Mobile",
)

@Serializable
data class TokenDto(
    val token: String,
)

@Serializable
data class BarDto(
    val id: Int,
    val name: String,
    val subtitle: String? = null,
    val description: String? = null,
    val slug: String,
    val status: String? = null,
)

@Serializable
data class ImageDto(
    val id: Int,
    @SerialName("file_path") val filePath: String? = null,
    val url: String? = null,
    @SerialName("thumb_url") val thumbUrl: String? = null,
    val copyright: String? = null,
    val sort: Int = 0,
    @SerialName("placeholder_hash") val placeholderHash: String? = null,
)

@Serializable
data class TagDto(
    val id: Int,
    val name: String,
)

@Serializable
data class UtensilDto(
    val id: Int,
    val name: String,
)

@Serializable
data class GlassDto(
    val id: Int,
    val name: String,
    val description: String? = null,
    val volume: Double? = null,
    @SerialName("volume_units") val volumeUnits: String? = null,
)

@Serializable
data class CocktailMethodDto(
    val id: Int,
    val name: String,
    val description: String? = null,
    @SerialName("dilution_percentage") val dilutionPercentage: Int? = null,
)

@Serializable
data class IngredientBasicDto(
    val id: Int,
    val slug: String,
    val name: String,
    val image: ImageDto? = null,
)

@Serializable
data class CocktailIngredientSubstituteDto(
    val ingredient: IngredientBasicDto,
    val amount: Double? = null,
    @SerialName("amount_max") val amountMax: Double? = null,
    val units: String? = null,
    @SerialName("in_bar_shelf") val inBarShelf: Boolean = false,
)

@Serializable
data class CocktailIngredientDto(
    val sort: Int = 0,
    val amount: Double? = null,
    @SerialName("amount_max") val amountMax: Double? = null,
    val units: String? = null,
    val optional: Boolean = false,
    val ingredient: IngredientBasicDto,
    val substitutes: List<CocktailIngredientSubstituteDto> = emptyList(),
    val note: String? = null,
    @SerialName("in_bar_shelf") val inBarShelf: Boolean = false,
    @SerialName("in_bar_shelf_as_substitute") val inBarShelfAsSubstitute: Boolean = false,
    @SerialName("in_bar_shelf_as_complex_ingredient") val inBarShelfAsComplexIngredient: Boolean = false,
    @SerialName("in_bar_shelf_as_variant") val inBarShelfAsVariant: Boolean = false,
    @SerialName("is_specified") val isSpecified: Boolean = false,
)

@Serializable
data class CocktailDto(
    val id: Int,
    val slug: String,
    val name: String,
    val instructions: String = "",
    val garnish: String? = null,
    val description: String? = null,
    val source: String? = null,
    val abv: Double? = null,
    val images: List<ImageDto> = emptyList(),
    val tags: List<TagDto> = emptyList(),
    val utensils: List<UtensilDto> = emptyList(),
    val glass: GlassDto? = null,
    val method: CocktailMethodDto? = null,
    val ingredients: List<CocktailIngredientDto> = emptyList(),
    @SerialName("in_bar_shelf") val inBarShelf: Boolean = false,
    @SerialName("is_favorited") val isFavorited: Boolean = false,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class IngredientCategoryDto(
    val id: Int,
    val name: String,
)

@Serializable
data class IngredientDto(
    val id: Int,
    val slug: String,
    val name: String,
    val strength: Double = 0.0,
    val description: String? = null,
    val color: String? = null,
    val images: List<ImageDto> = emptyList(),
    @SerialName("in_bar_shelf") val inBarShelf: Boolean = false,
    @SerialName("category") val category: IngredientCategoryDto? = null,
    @SerialName("parent_ingredient") val parentIngredient: IngredientBasicDto? = null,
)

@Serializable
data class CocktailWriteRequestDto(
    val name: String,
    val instructions: String,
    val description: String? = null,
    val source: String? = null,
    val garnish: String? = null,
    @SerialName("glass_id") val glassId: Int? = null,
    @SerialName("cocktail_method_id") val methodId: Int? = null,
    val tags: List<String>,
    val utensils: List<Int>,
    val images: List<Int>,
    val ingredients: List<CocktailIngredientWriteDto>,
)

@Serializable
data class CocktailIngredientWriteDto(
    @SerialName("ingredient_id") val ingredientId: Int,
    val amount: Double,
    @SerialName("amount_max") val amountMax: Double? = null,
    val units: String,
    val sort: Int,
    val optional: Boolean,
    @SerialName("is_specified") val isSpecified: Boolean,
    val note: String? = null,
    val substitutes: List<CocktailIngredientSubstituteWriteDto>,
)

@Serializable
data class CocktailIngredientSubstituteWriteDto(
    @SerialName("ingredient_id") val ingredientId: Int,
    val amount: Double? = null,
    @SerialName("amount_max") val amountMax: Double? = null,
    val units: String? = null,
)

@Serializable
data class ToggleFavoriteDto(
    val id: Int,
    @SerialName("is_favorited") val isFavorited: Boolean,
)

@Serializable
data class ScrapeRequestDto(
    val source: String,
)

@Serializable
data class ScrapeResponseDto(
    @SerialName("schema_version") val schemaVersion: String? = null,
    val schema: ScrapedSchemaDto,
    @SerialName("scraper_meta") val scraperMeta: List<ScraperMetaDto> = emptyList(),
)

@Serializable
data class ScrapedSchemaDto(
    val name: String,
    val description: String? = null,
    val instructions: String = "",
    val garnish: String? = null,
    val source: String? = null,
    val tags: List<String> = emptyList(),
    val glass: String? = null,
    val method: String? = null,
    val images: List<ScrapedImageDto> = emptyList(),
    val ingredients: List<ScrapedIngredientDto> = emptyList(),
)

@Serializable
data class ScrapedImageDto(
    val uri: String,
    val copyright: String? = null,
)

@Serializable
data class ScrapedIngredientDto(
    val name: String,
    val amount: Double? = null,
    @SerialName("amount_max") val amountMax: Double? = null,
    val units: String? = null,
    val note: String? = null,
)

@Serializable
data class ScraperMetaDto(
    @SerialName("ingredient_name") val ingredientName: String,
    val source: String? = null,
)

@Serializable
data class ProfileDto(
    val id: Int,
    val name: String,
    val email: String,
)

@Serializable
data class BarInventoryBatchRequestDto(
    val ingredients: List<Int>,
)

@Serializable
data class IngredientRecommendDto(
    val id: Int,
    val slug: String,
    val name: String,
    @SerialName("potential_cocktails") val potentialCocktails: Int = 0,
)

@Serializable
data class CocktailBasicDto(
    val id: Int,
    val slug: String,
    val name: String,
)

@Serializable
data class ShoppingListItemDto(
    val ingredient: IngredientBasicDto,
    val quantity: Int? = 1,
)

@Serializable
data class ShoppingListIngredientInputDto(
    val id: Int,
    val quantity: Int = 1,
)

@Serializable
data class ShoppingListBatchStoreRequestDto(
    val ingredients: List<ShoppingListIngredientInputDto>,
)

@Serializable
data class ShoppingListIngredientIdDto(
    val id: Int,
)

@Serializable
data class ShoppingListBatchDeleteRequestDto(
    val ingredients: List<ShoppingListIngredientIdDto>,
)
