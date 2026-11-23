package com.ryanvidal.barfriend.feature.cocktails

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object CocktailsListRoute : NavKey

@Serializable
data class CocktailDetailRoute(val cocktailId: Int) : NavKey

@Serializable
data class CocktailEditorRoute(
    val cocktailId: Int? = null,
    val initialImportUrl: String? = null,
) : NavKey
