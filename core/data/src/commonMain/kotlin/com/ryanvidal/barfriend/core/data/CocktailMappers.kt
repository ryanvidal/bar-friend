package com.ryanvidal.barfriend.core.data

import com.ryanvidal.barfriend.core.database.dao.CocktailWithIngredients
import com.ryanvidal.barfriend.core.database.entity.CocktailEntity
import com.ryanvidal.barfriend.core.database.entity.CocktailIngredientEntity
import com.ryanvidal.barfriend.core.database.entity.CocktailMethodEntity
import com.ryanvidal.barfriend.core.database.entity.GlassEntity
import com.ryanvidal.barfriend.core.database.entity.IngredientEntity
import com.ryanvidal.barfriend.core.database.entity.TagEntity
import com.ryanvidal.barfriend.core.database.entity.UtensilEntity
import com.ryanvidal.barfriend.core.model.CocktailDetail
import com.ryanvidal.barfriend.core.model.CocktailFilter
import com.ryanvidal.barfriend.core.model.CocktailImage
import com.ryanvidal.barfriend.core.model.CocktailIngredient
import com.ryanvidal.barfriend.core.model.CocktailMethod
import com.ryanvidal.barfriend.core.model.CocktailSort
import com.ryanvidal.barfriend.core.model.CocktailSummary
import com.ryanvidal.barfriend.core.model.CocktailTag
import com.ryanvidal.barfriend.core.model.CocktailUpsertRequest
import com.ryanvidal.barfriend.core.model.Glass
import com.ryanvidal.barfriend.core.model.IngredientSummary
import com.ryanvidal.barfriend.core.model.IngredientSubstitute
import com.ryanvidal.barfriend.core.model.ScrapedCocktail
import com.ryanvidal.barfriend.core.model.ScrapedCocktailIngredient
import com.ryanvidal.barfriend.core.model.ScrapedImage
import com.ryanvidal.barfriend.core.model.UploadedImage
import com.ryanvidal.barfriend.core.model.Utensil
import com.ryanvidal.barfriend.core.network.ServerUrlNormalizer
import com.ryanvidal.barfriend.core.network.dto.CocktailDto
import com.ryanvidal.barfriend.core.network.dto.CocktailIngredientSubstituteWriteDto
import com.ryanvidal.barfriend.core.network.dto.CocktailIngredientWriteDto
import com.ryanvidal.barfriend.core.network.dto.CocktailMethodDto
import com.ryanvidal.barfriend.core.network.dto.CocktailWriteRequestDto
import com.ryanvidal.barfriend.core.network.dto.GlassDto
import com.ryanvidal.barfriend.core.network.dto.ImageDto
import com.ryanvidal.barfriend.core.network.dto.IngredientDto
import com.ryanvidal.barfriend.core.network.dto.ScrapeResponseDto
import com.ryanvidal.barfriend.core.network.dto.TagDto
import com.ryanvidal.barfriend.core.network.dto.UtensilDto
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.encodedPath
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val StorageJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
internal data class StoredCocktailImage(
    val id: Int,
    val url: String,
    val thumbUrl: String,
    val sort: Int = 0,
    val placeholderHash: String? = null,
    val copyright: String? = null,
)

@Serializable
internal data class StoredCocktailTag(
    val id: Int,
    val name: String,
)

@Serializable
internal data class StoredUtensil(
    val id: Int,
    val name: String,
)

@Serializable
internal data class StoredIngredientSubstitute(
    val id: Int,
    val slug: String,
    val name: String,
    val inBarShelf: Boolean = false,
    val amount: Double? = null,
    val amountMax: Double? = null,
    val units: String? = null,
)

internal inline fun <reified T> decodeStoredList(json: String): List<T> =
    if (json.isBlank()) {
        emptyList()
    } else {
        runCatching { StorageJson.decodeFromString<List<T>>(json) }.getOrDefault(emptyList())
    }

internal fun resolveImageUrl(raw: String?, serverUrl: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        return trimmed
    }
    val normalizedServer = serverUrl?.takeIf { it.isNotBlank() }
        ?.let { runCatching { ServerUrlNormalizer.normalize(it) }.getOrNull() }
        ?: return trimmed
    val baseUrl = runCatching { Url(normalizedServer) }.getOrNull() ?: return trimmed
    if (trimmed.startsWith("//")) {
        return "${baseUrl.protocol.name}:$trimmed"
    }

    val basePath = baseUrl.encodedPath.trimEnd('/')
    val querySplit = trimmed.indexOfAny(charArrayOf('?', '#'))
    val rawPath = if (querySplit >= 0) trimmed.substring(0, querySplit) else trimmed
    val suffix = if (querySplit >= 0) trimmed.substring(querySplit) else ""
    val normalizedPath = if (
        basePath.isNotEmpty() &&
        (rawPath == basePath || rawPath.startsWith("$basePath/"))
    ) {
        rawPath
    } else {
        "$basePath/${rawPath.trimStart('/')}"
    }

    val origin = URLBuilder(baseUrl).apply {
        user = null
        password = null
        encodedPath = ""
        parameters.clear()
        fragment = ""
        trailingQuery = false
    }.buildString().trimEnd('/')
    return "$origin$normalizedPath$suffix"
}

fun CocktailDto.toEntityAndIngredients(
    barId: Int,
    serverUrl: String? = null,
): Pair<CocktailEntity, List<CocktailIngredientEntity>> {
    val storedImages = images
        .sortedBy { it.sort }
        .map { image ->
            val resolvedUrl = resolveImageUrl(image.url, serverUrl).orEmpty()
            val resolvedThumb = resolveImageUrl(image.thumbUrl, serverUrl) ?: resolvedUrl
            StoredCocktailImage(
                id = image.id,
                url = resolvedUrl.ifBlank { resolvedThumb },
                thumbUrl = resolvedThumb.ifBlank { resolvedUrl },
                sort = image.sort,
                placeholderHash = image.placeholderHash,
                copyright = image.copyright,
            )
        }
    val primaryImage = storedImages.firstOrNull()

    val storedTags = tags.map { StoredCocktailTag(id = it.id, name = it.name) }
    val storedUtensils = utensils.map { StoredUtensil(id = it.id, name = it.name) }

    val sortedIngredients = ingredients.sortedBy { it.sort }
    val missingNames = sortedIngredients
        .filter { ing ->
            !ing.optional &&
                !(ing.inBarShelf || ing.inBarShelfAsSubstitute || ing.inBarShelfAsVariant || ing.inBarShelfAsComplexIngredient)
        }
        .map { it.ingredient.name }
        .distinct()

    val seenIngredientSortKeys = HashSet<Pair<Int, Int>>(sortedIngredients.size)
    val ingredientEntities = sortedIngredients.map { ing ->
        var resolvedSort = ing.sort
        while (!seenIngredientSortKeys.add(ing.ingredient.id to resolvedSort)) {
            resolvedSort += 1
        }
        val storedSubstitutes = ing.substitutes.map { sub ->
            StoredIngredientSubstitute(
                id = sub.ingredient.id,
                slug = sub.ingredient.slug,
                name = sub.ingredient.name,
                inBarShelf = sub.inBarShelf,
                amount = sub.amount,
                amountMax = sub.amountMax,
                units = sub.units,
            )
        }
        CocktailIngredientEntity(
            cocktailId = id,
            ingredientId = ing.ingredient.id,
            ingredientSlug = ing.ingredient.slug,
            ingredientName = ing.ingredient.name,
            sort = resolvedSort,
            amount = ing.amount,
            amountMax = ing.amountMax,
            units = ing.units,
            optional = ing.optional,
            note = ing.note,
            inBarShelf = ing.inBarShelf,
            inBarShelfAsSubstitute = ing.inBarShelfAsSubstitute,
            inBarShelfAsVariant = ing.inBarShelfAsVariant,
            inBarShelfAsComplex = ing.inBarShelfAsComplexIngredient,
            substitutesJson = StorageJson.encodeToString(storedSubstitutes),
            isSpecified = ing.isSpecified,
        )
    }

    val resolvedInBarShelf = inBarShelf || (ingredientEntities.isNotEmpty() && missingNames.isEmpty())

    val cocktailEntity = CocktailEntity(
        id = id,
        barId = barId,
        slug = slug,
        name = name,
        shortDescription = buildShortDescription(description),
        instructions = instructions,
        garnish = garnish,
        description = description,
        source = source,
        abv = abv,
        glassId = glass?.id,
        glassName = glass?.name,
        methodId = method?.id,
        methodName = method?.name,
        mainImageThumbUrl = primaryImage?.thumbUrl?.takeIf { it.isNotBlank() },
        mainImageUrl = primaryImage?.url?.takeIf { it.isNotBlank() },
        imagesJson = StorageJson.encodeToString(storedImages),
        isFavorite = isFavorited,
        inBarShelf = resolvedInBarShelf,
        missingIngredientsCount = missingNames.size,
        missingIngredientNamesJson = StorageJson.encodeToString(missingNames),
        tagsJson = StorageJson.encodeToString(storedTags),
        utensilsJson = StorageJson.encodeToString(storedUtensils),
        author = null,
        originBar = null,
        year = null,
        updatedAt = updatedAt,
    )

    return cocktailEntity to ingredientEntities
}

fun CocktailWithIngredients.toCocktailSummary(): CocktailSummary = CocktailSummary(
    id = cocktail.id,
    slug = cocktail.slug,
    name = cocktail.name,
    shortDescription = cocktail.shortDescription,
    abv = cocktail.abv,
    glassName = cocktail.glassName,
    methodName = cocktail.methodName,
    mainImageThumbUrl = cocktail.mainImageThumbUrl,
    isFavorite = cocktail.isFavorite,
    inBarShelf = cocktail.inBarShelf,
    missingIngredientsCount = cocktail.missingIngredientsCount,
    missingIngredientNames = decodeStoredList<String>(cocktail.missingIngredientNamesJson),
    tags = decodeStoredList<StoredCocktailTag>(cocktail.tagsJson).map { tag ->
        CocktailTag(id = tag.id, name = tag.name)
    },
)

fun CocktailWithIngredients.toCocktailDetail(
    glass: Glass? = null,
    method: CocktailMethod? = null,
): CocktailDetail {
    val glassId = cocktail.glassId
    val glassName = cocktail.glassName
    val resolvedGlass = glass ?: if (glassId != null && glassName != null) {
        Glass(
            id = glassId,
            name = glassName,
            volume = null,
            volumeUnits = null,
        )
    } else {
        null
    }
    val methodId = cocktail.methodId
    val methodName = cocktail.methodName
    val resolvedMethod = method ?: if (methodId != null && methodName != null) {
        CocktailMethod(
            id = methodId,
            name = methodName,
            dilutionPercentage = null,
        )
    } else {
        null
    }
    return CocktailDetail(
        id = cocktail.id,
        slug = cocktail.slug,
        name = cocktail.name,
        shortDescription = cocktail.shortDescription,
        instructions = cocktail.instructions,
        garnish = cocktail.garnish,
        description = cocktail.description,
        source = cocktail.source,
        abv = cocktail.abv,
        glass = resolvedGlass,
        method = resolvedMethod,
        images = decodeStoredList<StoredCocktailImage>(cocktail.imagesJson)
            .sortedBy { it.sort }
            .map { image ->
                CocktailImage(
                    id = image.id,
                    url = image.url,
                    thumbUrl = image.thumbUrl,
                    sort = image.sort,
                    placeholderHash = image.placeholderHash,
                    copyright = image.copyright,
                )
            },
        tags = decodeStoredList<StoredCocktailTag>(cocktail.tagsJson).map { tag ->
            CocktailTag(id = tag.id, name = tag.name)
        },
        utensils = decodeStoredList<StoredUtensil>(cocktail.utensilsJson).map { utensil ->
            Utensil(id = utensil.id, name = utensil.name)
        },
        ingredients = ingredients.sortedBy { it.sort }.map { it.toModel() },
        isFavorite = cocktail.isFavorite,
        inBarShelf = cocktail.inBarShelf,
        missingIngredientsCount = cocktail.missingIngredientsCount,
        author = cocktail.author,
        originBar = cocktail.originBar,
        year = cocktail.year,
    )
}

fun CocktailIngredientEntity.toModel(): CocktailIngredient = CocktailIngredient(
    id = ((cocktailId * 31) + ingredientId) * 31 + sort,
    ingredientId = ingredientId,
    ingredientSlug = ingredientSlug,
    name = ingredientName,
    sort = sort,
    amount = amount,
    amountMax = amountMax,
    units = units,
    optional = optional,
    note = note,
    inBarShelf = inBarShelf,
    inBarShelfAsSubstitute = inBarShelfAsSubstitute,
    inBarShelfAsVariant = inBarShelfAsVariant,
    inBarShelfAsComplex = inBarShelfAsComplex,
    substitutes = decodeStoredList<StoredIngredientSubstitute>(substitutesJson).map { sub ->
        IngredientSubstitute(
            id = sub.id,
            slug = sub.slug,
            name = sub.name,
            inBarShelf = sub.inBarShelf,
            amount = sub.amount,
            amountMax = sub.amountMax,
            units = sub.units,
        )
    },
    isSpecified = isSpecified,
)

fun filterAndSortCocktails(
    items: List<CocktailWithIngredients>,
    filter: CocktailFilter,
): List<CocktailSummary> {
    val trimmedQuery = filter.query.trim()
    val filtered = items.asSequence()
        .mapNotNull { item ->
            val cocktail = item.cocktail
            val tags = decodeStoredList<StoredCocktailTag>(cocktail.tagsJson)

            if (filter.canMakeOnly && !(cocktail.inBarShelf || cocktail.missingIngredientsCount == 0)) {
                return@mapNotNull null
            }
            if (filter.favoritesOnly && !cocktail.isFavorite) {
                return@mapNotNull null
            }
            if (filter.tagIds.isNotEmpty()) {
                val cocktailTagIds = tags.mapTo(HashSet(tags.size)) { it.id }
                if (!cocktailTagIds.containsAll(filter.tagIds)) {
                    return@mapNotNull null
                }
            }
            if (filter.glassId != null && cocktail.glassId != filter.glassId) {
                return@mapNotNull null
            }
            if (filter.methodId != null && cocktail.methodId != filter.methodId) {
                return@mapNotNull null
            }
            val abvMin = filter.abvMin
            if (abvMin != null) {
                val abv = cocktail.abv ?: return@mapNotNull null
                if (abv < abvMin) {
                    return@mapNotNull null
                }
            }
            val abvMax = filter.abvMax
            if (abvMax != null) {
                val abv = cocktail.abv ?: return@mapNotNull null
                if (abv > abvMax) {
                    return@mapNotNull null
                }
            }
            if (trimmedQuery.isNotEmpty()) {
                val matchesName = cocktail.name.contains(trimmedQuery, ignoreCase = true)
                val matchesDescription = cocktail.shortDescription?.contains(trimmedQuery, ignoreCase = true) == true
                val matchesTag = tags.any { it.name.contains(trimmedQuery, ignoreCase = true) }
                val matchesIngredient = item.ingredients.any {
                    it.ingredientName.contains(trimmedQuery, ignoreCase = true)
                }
                if (!(matchesName || matchesDescription || matchesTag || matchesIngredient)) {
                    return@mapNotNull null
                }
            }

            item.toCocktailSummary()
        }
        .toList()

    val comparator = when (filter.sort) {
        CocktailSort.NAME_ASC -> compareBy<CocktailSummary> { it.name.lowercase() }
            .thenBy { it.name }
            .thenBy { it.id }

        CocktailSort.NAME_DESC -> compareByDescending<CocktailSummary> { it.name.lowercase() }
            .thenByDescending { it.name }
            .thenBy { it.id }

        CocktailSort.ABV_DESC -> compareByDescending<CocktailSummary> { it.abv ?: Double.NEGATIVE_INFINITY }
            .thenBy { it.name.lowercase() }
            .thenBy { it.id }

        CocktailSort.ABV_ASC -> compareBy<CocktailSummary> { it.abv ?: Double.POSITIVE_INFINITY }
            .thenBy { it.name.lowercase() }
            .thenBy { it.id }

        CocktailSort.MISSING_ASC -> compareBy<CocktailSummary> { it.missingIngredientsCount }
            .thenBy { it.name.lowercase() }
            .thenBy { it.id }
    }

    return filtered.sortedWith(comparator)
}

fun IngredientDto.toEntity(barId: Int, serverUrl: String? = null): IngredientEntity {
    val primaryImage = images.minByOrNull { it.sort }
    val thumbUrl = resolveImageUrl(primaryImage?.thumbUrl, serverUrl)
        ?: resolveImageUrl(primaryImage?.url, serverUrl)
    return IngredientEntity(
        id = id,
        barId = barId,
        slug = slug,
        name = name,
        strength = strength,
        description = description,
        color = color,
        categoryName = category?.name,
        parentIngredientId = parentIngredient?.id,
        mainImageThumbUrl = thumbUrl,
    )
}

fun GlassDto.toEntity(barId: Int): GlassEntity = GlassEntity(
    id = id,
    barId = barId,
    name = name,
    volume = volume,
    volumeUnits = volumeUnits,
)

fun CocktailMethodDto.toEntity(barId: Int): CocktailMethodEntity = CocktailMethodEntity(
    id = id,
    barId = barId,
    name = name,
    dilutionPercentage = dilutionPercentage,
)

fun TagDto.toEntity(barId: Int): TagEntity = TagEntity(
    id = id,
    barId = barId,
    name = name,
)

fun UtensilDto.toEntity(barId: Int): UtensilEntity = UtensilEntity(
    id = id,
    barId = barId,
    name = name,
)

fun IngredientEntity.toSummary(
    shelfIds: Set<Int>,
    shoppingListIds: Set<Int>,
): IngredientSummary = IngredientSummary(
    id = id,
    slug = slug,
    name = name,
    strength = strength,
    categoryName = categoryName,
    mainImageThumbUrl = mainImageThumbUrl,
    inBarShelf = id in shelfIds,
    inShoppingList = id in shoppingListIds,
)

fun GlassEntity.toModel(): Glass = Glass(
    id = id,
    name = name,
    volume = volume,
    volumeUnits = volumeUnits,
)

fun CocktailMethodEntity.toModel(): CocktailMethod = CocktailMethod(
    id = id,
    name = name,
    dilutionPercentage = dilutionPercentage,
)

fun TagEntity.toModel(): CocktailTag = CocktailTag(
    id = id,
    name = name,
)

fun UtensilEntity.toModel(): Utensil = Utensil(
    id = id,
    name = name,
)

fun CocktailUpsertRequest.toWriteDto(): CocktailWriteRequestDto = CocktailWriteRequestDto(
    name = name.trim(),
    instructions = instructions.trim(),
    description = description?.trim()?.takeIf { it.isNotEmpty() },
    source = source?.trim()?.takeIf { it.isNotEmpty() },
    garnish = garnish?.trim()?.takeIf { it.isNotEmpty() },
    glassId = glassId,
    methodId = methodId,
    tags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
    utensils = utensilIds.distinct(),
    images = imageIds.distinct(),
    ingredients = ingredients.map { ing ->
        CocktailIngredientWriteDto(
            ingredientId = ing.ingredientId,
            amount = ing.amount,
            amountMax = ing.amountMax,
            units = ing.units.trim(),
            sort = ing.sort,
            optional = ing.optional,
            isSpecified = ing.isSpecified,
            note = ing.note?.trim()?.takeIf { it.isNotEmpty() },
            substitutes = ing.substitutes.map { sub ->
                CocktailIngredientSubstituteWriteDto(
                    ingredientId = sub.ingredientId,
                    amount = sub.amount,
                    amountMax = sub.amountMax,
                    units = sub.units?.trim()?.takeIf { it.isNotEmpty() },
                )
            },
        )
    },
)

fun ImageDto.toUploadedImage(serverUrl: String? = null): UploadedImage {
    val resolvedUrl = resolveImageUrl(url, serverUrl).orEmpty()
    val resolvedThumb = resolveImageUrl(thumbUrl, serverUrl) ?: resolvedUrl
    return UploadedImage(
        id = id,
        url = resolvedUrl.ifBlank { resolvedThumb },
        thumbUrl = resolvedThumb.ifBlank { resolvedUrl },
        sort = sort,
        copyright = copyright,
    )
}

fun ScrapeResponseDto.toModel(): ScrapedCocktail {
    val metaByName = scraperMeta.associateBy { it.ingredientName.trim().lowercase() }
    return ScrapedCocktail(
        name = schema.name,
        instructions = schema.instructions,
        description = schema.description,
        garnish = schema.garnish,
        source = schema.source,
        tags = schema.tags,
        glassName = schema.glass,
        methodName = schema.method,
        images = schema.images.map { img ->
            ScrapedImage(
                uri = img.uri,
                copyright = img.copyright,
            )
        },
        ingredients = schema.ingredients.map { ing ->
            ScrapedCocktailIngredient(
                name = ing.name,
                amount = ing.amount,
                amountMax = ing.amountMax,
                units = ing.units,
                note = ing.note,
                sourceLine = metaByName[ing.name.trim().lowercase()]?.source,
            )
        },
    )
}

private fun buildShortDescription(description: String?): String? {
    val trimmed = description?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val singleLine = trimmed.lineSequence().firstOrNull()?.trim().orEmpty()
    if (singleLine.isEmpty()) return null
    return if (singleLine.length <= 140) singleLine else "${singleLine.take(137).trimEnd()}..."
}
