package com.ryanvidal.barfriend.feature.cocktails

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ryanvidal.barfriend.core.data.CocktailRepository
import com.ryanvidal.barfriend.core.model.BarCatalog
import com.ryanvidal.barfriend.core.model.CocktailDetail
import com.ryanvidal.barfriend.core.model.CocktailIngredientInput
import com.ryanvidal.barfriend.core.model.CocktailUpsertRequest
import com.ryanvidal.barfriend.core.model.IngredientSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EditorIngredientRow(
    val localKey: Long,
    val ingredientId: Int? = null,
    val ingredientName: String = "",
    val amountText: String = "",
    val amountMaxText: String = "",
    val units: String = "ml",
    val optional: Boolean = false,
    val note: String = "",
    val unmatchedScrapedHint: String? = null,
)

sealed interface EditorPhotoItem {
    val stableKey: String

    data class Existing(
        val id: Int,
        val url: String,
        val thumbUrl: String,
    ) : EditorPhotoItem {
        override val stableKey: String = "existing:$id"
    }

    data class PendingLocal(
        val localKey: Long,
        val bytes: ByteArray,
        val fileName: String,
        val mimeType: String?,
    ) : EditorPhotoItem {
        override val stableKey: String = "local:$localKey"

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PendingLocal) return false
            return localKey == other.localKey &&
                fileName == other.fileName &&
                mimeType == other.mimeType &&
                bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var result = localKey.hashCode()
            result = 31 * result + bytes.contentHashCode()
            result = 31 * result + fileName.hashCode()
            result = 31 * result + (mimeType?.hashCode() ?: 0)
            return result
        }
    }

    data class PendingRemote(
        val localKey: Long,
        val url: String,
        val copyright: String? = null,
    ) : EditorPhotoItem {
        override val stableKey: String = "remote:$localKey"
    }
}

data class CocktailEditorUiState(
    val isEditing: Boolean = false,
    val isLoadingExisting: Boolean = false,
    val isImporting: Boolean = false,
    val isSaving: Boolean = false,
    val name: String = "",
    val instructions: String = "",
    val description: String = "",
    val garnish: String = "",
    val source: String = "",
    val glassId: Int? = null,
    val methodId: Int? = null,
    val tags: List<String> = emptyList(),
    val utensilIds: List<Int> = emptyList(),
    val ingredients: List<EditorIngredientRow> = emptyList(),
    val photos: List<EditorPhotoItem> = emptyList(),
    val catalog: BarCatalog = BarCatalog(),
    val errorMessage: String? = null,
    val savedCocktailId: Int? = null,
)

class CocktailEditorViewModel(
    private val cocktailId: Int?,
    initialImportUrl: String?,
    private val cocktailRepository: CocktailRepository,
) : ViewModel() {
    private var nextKey = 1L
    private var hasLoadedExisting = false

    private val _uiState = MutableStateFlow(
        CocktailEditorUiState(
            isEditing = cocktailId != null,
            isLoadingExisting = cocktailId != null,
        ),
    )
    val uiState: StateFlow<CocktailEditorUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            cocktailRepository.observeCatalog().collect { catalog ->
                _uiState.update { it.copy(catalog = catalog) }
            }
        }
        if (cocktailId != null) {
            loadExistingCocktail(cocktailId)
        } else if (!initialImportUrl.isNullOrBlank()) {
            importFromUrl(initialImportUrl)
        }
    }

    private fun nextLocalKey(): Long = nextKey++

    private fun loadExistingCocktail(id: Int) {
        val observerJob = viewModelScope.launch {
            val detail = cocktailRepository.observeCocktailDetail(id)
                .filterNotNull()
                .first()
            applyExistingCocktail(detail)
        }
        viewModelScope.launch {
            val refreshResult = cocktailRepository.refreshCocktail(id)
            refreshResult.fold(
                onSuccess = { detail ->
                    if (!hasLoadedExisting) {
                        observerJob.cancel()
                        applyExistingCocktail(detail)
                    }
                },
                onFailure = { error ->
                    if (!hasLoadedExisting) {
                        _uiState.update {
                            it.copy(
                                isLoadingExisting = false,
                                errorMessage = error.message ?: "Unable to load recipe.",
                            )
                        }
                    }
                },
            )
        }
    }

    private fun applyExistingCocktail(detail: CocktailDetail) {
        if (hasLoadedExisting) return
        hasLoadedExisting = true
        _uiState.update { state ->
            state.copy(
                isLoadingExisting = false,
                name = detail.name,
                instructions = detail.instructions,
                description = detail.description.orEmpty(),
                garnish = detail.garnish.orEmpty(),
                source = detail.source.orEmpty(),
                glassId = detail.glass?.id,
                methodId = detail.method?.id,
                tags = detail.tags.map { it.name }.distinct(),
                utensilIds = detail.utensils.map { it.id },
                ingredients = detail.ingredients
                    .sortedBy { it.sort }
                    .map { ing ->
                        EditorIngredientRow(
                            localKey = nextLocalKey(),
                            ingredientId = ing.ingredientId,
                            ingredientName = ing.name,
                            amountText = ing.amount?.let(::formatNumericInput).orEmpty(),
                            amountMaxText = ing.amountMax?.let(::formatNumericInput).orEmpty(),
                            units = ing.units?.takeIf { it.isNotBlank() } ?: "ml",
                            optional = ing.optional,
                            note = ing.note.orEmpty(),
                        )
                    },
                photos = detail.images
                    .sortedBy { it.sort }
                    .map { img ->
                        EditorPhotoItem.Existing(
                            id = img.id,
                            url = img.url,
                            thumbUrl = img.thumbUrl,
                        )
                    },
            )
        }
    }

    fun onNameChanged(value: String) {
        _uiState.update { it.copy(name = value, errorMessage = null) }
    }

    fun onInstructionsChanged(value: String) {
        _uiState.update { it.copy(instructions = value, errorMessage = null) }
    }

    fun onDescriptionChanged(value: String) {
        _uiState.update { it.copy(description = value) }
    }

    fun onGarnishChanged(value: String) {
        _uiState.update { it.copy(garnish = value) }
    }

    fun onSourceChanged(value: String) {
        _uiState.update { it.copy(source = value) }
    }

    fun onGlassSelected(glassId: Int?) {
        _uiState.update { it.copy(glassId = glassId) }
    }

    fun onMethodSelected(methodId: Int?) {
        _uiState.update { it.copy(methodId = methodId) }
    }

    fun toggleTag(tagName: String) {
        val cleaned = tagName.trim()
        if (cleaned.isEmpty()) return
        _uiState.update { state ->
            val exists = state.tags.any { it.equals(cleaned, ignoreCase = true) }
            val updated = if (exists) {
                state.tags.filterNot { it.equals(cleaned, ignoreCase = true) }
            } else {
                state.tags + cleaned
            }
            state.copy(tags = updated)
        }
    }

    fun addCustomTag(tagName: String) {
        val cleaned = tagName.trim()
        if (cleaned.isEmpty()) return
        _uiState.update { state ->
            if (state.tags.any { it.equals(cleaned, ignoreCase = true) }) {
                state
            } else {
                val canonical = state.catalog.tags.firstOrNull {
                    it.name.trim().equals(cleaned, ignoreCase = true)
                }?.name ?: cleaned
                state.copy(tags = state.tags + canonical)
            }
        }
    }

    fun addIngredientRow(): Long {
        val key = nextLocalKey()
        _uiState.update { state ->
            state.copy(
                ingredients = state.ingredients + EditorIngredientRow(localKey = key),
                errorMessage = null,
            )
        }
        return key
    }

    fun addIngredientRow(ingredient: IngredientSummary): Long {
        val key = nextLocalKey()
        _uiState.update { state ->
            state.copy(
                ingredients = state.ingredients + EditorIngredientRow(
                    localKey = key,
                    ingredientId = ingredient.id,
                    ingredientName = ingredient.name,
                    amountText = "1",
                    units = "oz",
                ),
                errorMessage = null,
            )
        }
        return key
    }

    fun updateIngredientSelection(localKey: Long, ingredient: IngredientSummary) {
        _uiState.update { state ->
            state.copy(
                ingredients = state.ingredients.map { row ->
                    if (row.localKey == localKey) {
                        row.copy(
                            ingredientId = ingredient.id,
                            ingredientName = ingredient.name,
                            unmatchedScrapedHint = null,
                        )
                    } else {
                        row
                    }
                },
                errorMessage = null,
            )
        }
    }

    fun updateIngredientRow(
        localKey: Long,
        amountText: String? = null,
        amountMaxText: String? = null,
        units: String? = null,
        optional: Boolean? = null,
        note: String? = null,
    ) {
        _uiState.update { state ->
            state.copy(
                ingredients = state.ingredients.map { row ->
                    if (row.localKey == localKey) {
                        row.copy(
                            amountText = amountText ?: row.amountText,
                            amountMaxText = amountMaxText ?: row.amountMaxText,
                            units = units ?: row.units,
                            optional = optional ?: row.optional,
                            note = note ?: row.note,
                        )
                    } else {
                        row
                    }
                },
                errorMessage = null,
            )
        }
    }

    fun moveIngredientUp(localKey: Long) {
        _uiState.update { state ->
            val index = state.ingredients.indexOfFirst { it.localKey == localKey }
            if (index <= 0) return@update state
            val mutable = state.ingredients.toMutableList()
            val item = mutable.removeAt(index)
            mutable.add(index - 1, item)
            state.copy(ingredients = mutable)
        }
    }

    fun moveIngredientDown(localKey: Long) {
        _uiState.update { state ->
            val index = state.ingredients.indexOfFirst { it.localKey == localKey }
            if (index < 0 || index >= state.ingredients.lastIndex) return@update state
            val mutable = state.ingredients.toMutableList()
            val item = mutable.removeAt(index)
            mutable.add(index + 1, item)
            state.copy(ingredients = mutable)
        }
    }

    fun removeIngredientRow(localKey: Long) {
        _uiState.update { state ->
            state.copy(
                ingredients = state.ingredients.filterNot { it.localKey == localKey },
                errorMessage = null,
            )
        }
    }

    fun addLocalPhoto(
        bytes: ByteArray,
        fileName: String,
        mimeType: String?,
    ) {
        val item = EditorPhotoItem.PendingLocal(
            localKey = nextLocalKey(),
            bytes = bytes,
            fileName = fileName,
            mimeType = mimeType,
        )
        _uiState.update { state ->
            state.copy(photos = state.photos + item, errorMessage = null)
        }
    }

    fun removePhoto(key: String) {
        _uiState.update { state ->
            state.copy(photos = state.photos.filterNot { it.stableKey == key })
        }
    }

    fun importFromUrl(rawUrl: String) {
        val url = rawUrl.trim()
        if (url.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Enter a valid recipe URL.") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true, errorMessage = null) }
            cocktailRepository.scrapeRecipeFromUrl(url).fold(
                onSuccess = { scraped ->
                    val catalog = resolveLatestCatalog()
                    val matchedGlassId = scraped.glassName?.trim()?.takeIf { it.isNotEmpty() }?.let { gName ->
                        catalog.glasses.firstOrNull { it.name.trim().equals(gName, ignoreCase = true) }?.id
                    }
                    val matchedMethodId = scraped.methodName?.trim()?.takeIf { it.isNotEmpty() }?.let { mName ->
                        catalog.methods.firstOrNull { it.name.trim().equals(mName, ignoreCase = true) }?.id
                    }
                    val mappedIngredients = scraped.ingredients.map { item ->
                        val cleanName = item.name.trim()
                        val matched = catalog.ingredients.firstOrNull {
                            it.name.trim().equals(cleanName, ignoreCase = true)
                        }
                        EditorIngredientRow(
                            localKey = nextLocalKey(),
                            ingredientId = matched?.id,
                            ingredientName = matched?.name ?: cleanName,
                            amountText = item.amount?.let(::formatNumericInput).orEmpty(),
                            amountMaxText = item.amountMax?.let(::formatNumericInput).orEmpty(),
                            units = item.units?.trim()?.takeIf { it.isNotEmpty() } ?: "ml",
                            optional = false,
                            note = item.note?.trim().orEmpty(),
                            unmatchedScrapedHint = if (matched == null) {
                                item.sourceLine?.trim()?.takeIf { it.isNotEmpty() } ?: cleanName
                            } else {
                                null
                            },
                        )
                    }

                    _uiState.update { current ->
                        val mergedTags = mergeTags(current.tags, scraped.tags, catalog)
                        val existingUrls = current.photos.mapNotNull { photo ->
                            when (photo) {
                                is EditorPhotoItem.Existing -> photo.url
                                is EditorPhotoItem.PendingRemote -> photo.url
                                is EditorPhotoItem.PendingLocal -> null
                            }
                        }.toMutableSet()
                        val importedPhotos = scraped.images.mapNotNull { image ->
                            val uri = image.uri.trim()
                            if (uri.isEmpty() || !existingUrls.add(uri)) {
                                null
                            } else {
                                EditorPhotoItem.PendingRemote(
                                    localKey = nextLocalKey(),
                                    url = uri,
                                    copyright = image.copyright,
                                )
                            }
                        }

                        current.copy(
                            isImporting = false,
                            name = scraped.name.trim().ifEmpty { current.name },
                            instructions = scraped.instructions.trim().ifEmpty { current.instructions },
                            description = scraped.description?.trim() ?: current.description,
                            garnish = scraped.garnish?.trim() ?: current.garnish,
                            source = scraped.source?.trim()?.takeIf { it.isNotEmpty() } ?: url,
                            glassId = matchedGlassId ?: current.glassId,
                            methodId = matchedMethodId ?: current.methodId,
                            tags = mergedTags,
                            ingredients = if (mappedIngredients.isNotEmpty()) mappedIngredients else current.ingredients,
                            photos = current.photos + importedPhotos,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            errorMessage = error.message ?: "Unable to import recipe from URL.",
                        )
                    }
                },
            )
        }
    }

    fun save() {
        val snapshot = _uiState.value
        if (snapshot.isSaving) return

        val validationError = validateForSave(snapshot)
        if (validationError != null) {
            _uiState.update { it.copy(errorMessage = validationError) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }

            val collectedImageIds = mutableListOf<Int>()
            val updatedPhotos = snapshot.photos.toMutableList()

            for (index in updatedPhotos.indices) {
                val sortOrder = index + 1
                when (val photo = updatedPhotos[index]) {
                    is EditorPhotoItem.Existing -> collectedImageIds += photo.id

                    is EditorPhotoItem.PendingLocal -> {
                        val uploaded = cocktailRepository.uploadImage(
                            bytes = photo.bytes,
                            fileName = photo.fileName,
                            mimeType = photo.mimeType,
                            sort = sortOrder,
                        ).getOrElse { error ->
                            _uiState.update {
                                it.copy(
                                    isSaving = false,
                                    photos = updatedPhotos,
                                    errorMessage = error.message ?: "Failed to upload photo ${photo.fileName}.",
                                )
                            }
                            return@launch
                        }
                        updatedPhotos[index] = EditorPhotoItem.Existing(
                            id = uploaded.id,
                            url = uploaded.url,
                            thumbUrl = uploaded.thumbUrl,
                        )
                        collectedImageIds += uploaded.id
                    }

                    is EditorPhotoItem.PendingRemote -> {
                        val uploaded = cocktailRepository.uploadImageFromUrl(
                            imageUrl = photo.url,
                            copyright = photo.copyright,
                            sort = sortOrder,
                        ).getOrElse { error ->
                            _uiState.update {
                                it.copy(
                                    isSaving = false,
                                    photos = updatedPhotos,
                                    errorMessage = error.message ?: "Failed to upload imported image.",
                                )
                            }
                            return@launch
                        }
                        updatedPhotos[index] = EditorPhotoItem.Existing(
                            id = uploaded.id,
                            url = uploaded.url,
                            thumbUrl = uploaded.thumbUrl,
                        )
                        collectedImageIds += uploaded.id
                    }
                }
            }

            _uiState.update { it.copy(photos = updatedPhotos) }

            val request = CocktailUpsertRequest(
                name = snapshot.name.trim(),
                instructions = snapshot.instructions.trim(),
                description = snapshot.description.trim().takeIf { it.isNotEmpty() },
                garnish = snapshot.garnish.trim().takeIf { it.isNotEmpty() },
                source = snapshot.source.trim().takeIf { it.isNotEmpty() },
                glassId = snapshot.glassId,
                methodId = snapshot.methodId,
                tags = snapshot.tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
                utensilIds = snapshot.utensilIds,
                imageIds = collectedImageIds,
                ingredients = snapshot.ingredients.mapIndexed { index, row ->
                    CocktailIngredientInput(
                        ingredientId = requireNotNull(row.ingredientId),
                        ingredientName = row.ingredientName,
                        amount = row.amountText.trim().toDouble(),
                        amountMax = row.amountMaxText.trim().takeIf { it.isNotEmpty() }?.toDouble(),
                        units = row.units.trim(),
                        sort = index + 1,
                        optional = row.optional,
                        note = row.note.trim().takeIf { it.isNotEmpty() },
                    )
                },
            )

            cocktailRepository.saveCocktail(cocktailId, request).fold(
                onSuccess = { saved ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            savedCocktailId = saved.id,
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isSaving = false,
                            errorMessage = error.message ?: "Unable to save recipe.",
                        )
                    }
                },
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private suspend fun resolveLatestCatalog(): BarCatalog {
        val current = _uiState.value.catalog
        if (current.ingredients.isNotEmpty() || current.glasses.isNotEmpty() || current.methods.isNotEmpty()) {
            return current
        }
        return cocktailRepository.observeCatalog().first()
    }

    private fun validateForSave(state: CocktailEditorUiState): String? {
        if (state.name.isBlank()) {
            return "Recipe name is required."
        }
        if (state.instructions.isBlank()) {
            return "Instructions are required."
        }
        state.ingredients.forEach { row ->
            if (row.ingredientId == null) {
                val label = row.unmatchedScrapedHint ?: row.ingredientName.ifBlank { "ingredient row" }
                return "Match or remove \"$label\" before saving."
            }
            val amount = row.amountText.trim().toDoubleOrNull()
            if (amount == null || amount <= 0.0) {
                return "Enter an amount greater than 0 for ${row.ingredientName}."
            }
            if (row.amountMaxText.isNotBlank()) {
                val maxAmount = row.amountMaxText.trim().toDoubleOrNull()
                if (maxAmount == null || maxAmount < amount) {
                    return "Enter a valid max amount for ${row.ingredientName}."
                }
            }
            if (row.units.isBlank()) {
                return "Select a unit for ${row.ingredientName}."
            }
        }
        return null
    }

    private fun mergeTags(
        existing: List<String>,
        incoming: List<String>,
        catalog: BarCatalog,
    ): List<String> {
        val seen = mutableSetOf<String>()
        return buildList {
            (existing + incoming).forEach { raw ->
                val cleaned = raw.trim()
                if (cleaned.isNotEmpty() && seen.add(cleaned.lowercase())) {
                    val canonical = catalog.tags.firstOrNull {
                        it.name.trim().equals(cleaned, ignoreCase = true)
                    }?.name ?: cleaned
                    add(canonical)
                }
            }
        }
    }
}
