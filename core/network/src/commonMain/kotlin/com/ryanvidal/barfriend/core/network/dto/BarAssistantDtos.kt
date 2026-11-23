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
data class IngredientDto(
    val id: Int,
    val slug: String,
    val name: String,
    val strength: Double = 0.0,
    val description: String? = null,
    val color: String? = null,
    val images: List<ImageDto> = emptyList(),
    @SerialName("in_bar_shelf") val inBarShelf: Boolean = false,
)
