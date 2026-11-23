package com.ryanvidal.barfriend.core.model

enum class CocktailSort(val label: String) {
    NAME_ASC("Name A–Z"),
    NAME_DESC("Name Z–A"),
    ABV_DESC("Highest ABV"),
    ABV_ASC("Lowest ABV"),
    MISSING_ASC("Fewest Missing"),
}

data class CocktailFilter(
    val query: String = "",
    val canMakeOnly: Boolean = false,
    val favoritesOnly: Boolean = false,
    val tagIds: Set<Int> = emptySet(),
    val glassId: Int? = null,
    val methodId: Int? = null,
    val abvMin: Double? = null,
    val abvMax: Double? = null,
    val sort: CocktailSort = CocktailSort.NAME_ASC,
) {
    val activeAdvancedFilterCount: Int
        get() = listOfNotNull(
            tagIds.takeIf { it.isNotEmpty() },
            glassId,
            methodId,
            abvMin,
            abvMax,
            sort.takeIf { it != CocktailSort.NAME_ASC },
        ).size
}

data class BarCatalog(
    val ingredients: List<IngredientSummary> = emptyList(),
    val glasses: List<Glass> = emptyList(),
    val methods: List<CocktailMethod> = emptyList(),
    val tags: List<CocktailTag> = emptyList(),
    val utensils: List<Utensil> = emptyList(),
)
