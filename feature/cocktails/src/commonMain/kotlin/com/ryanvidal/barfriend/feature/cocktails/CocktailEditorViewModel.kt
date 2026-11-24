package com.ryanvidal.barfriend.feature.cocktails

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ryanvidal.barfriend.core.data.CocktailRepository
import com.ryanvidal.barfriend.core.model.BarCatalog
import com.ryanvidal.barfriend.core.model.CocktailDetail
import com.ryanvidal.barfriend.core.model.CocktailIngredientInput
import com.ryanvidal.barfriend.core.model.CocktailUpsertRequest
import com.ryanvidal.barfriend.core.model.IngredientSubstituteInput
import com.ryanvidal.barfriend.core.model.IngredientSummary
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.ImageFormat
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.compressImage
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_RAW_PHOTO_BYTES = 25 * 1024 * 1024
private const val COMPRESSED_PHOTO_QUALITY = 85
private const val COMPRESSED_PHOTO_MAX_DIMENSION = 1600

data class EditorIngredientRow(
    val localKey: Long,
    val ingredientId: Int? = null,
    val ingredientName: String = "",
    val amountText: String = "",
    val amountMaxText: String = "",
    val units: String = "ml",
    val optional: Boolean = false,
    val isSpecified: Boolean = false,
    val note: String = "",
    val substitutes: List<IngredientSubstituteInput> = emptyList(),
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
            return localKey == other.localKey
        }

        override fun hashCode(): Int = localKey.hashCode()
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
    val hasLoadedExisting: Boolean = false,
    val isImporting: Boolean = false,
    val isProcessingPhoto: Boolean = false,
    val isSaving: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
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
) {
    val isBusy: Boolean
        get() = isSaving || isImporting || isLoadingExisting || isProcessingPhoto

    val canSubmitSave: Boolean
        get() = !isBusy && (!isEditing || hasLoadedExisting)
}

private data class EditorContentSnapshot(
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
)

private fun CocktailEditorUiState.toContentSnapshot(): EditorContentSnapshot = EditorContentSnapshot(
    name = name,
    instructions = instructions,
    description = description,
    garnish = garnish,
    source = source,
    glassId = glassId,
    methodId = methodId,
    tags = tags,
    utensilIds = utensilIds,
    ingredients = ingredients,
    photos = photos,
)

class CocktailEditorViewModel(
    private val cocktailId: Int?,
    initialImportUrl: String?,
    private val cocktailRepository: CocktailRepository,
) : ViewModel() {
    private var nextKey = 1L
    private var hasUserEdited = false
    private var activePhotoJobs = 0
    private var loadJob: Job? = null
    private var baselineSnapshot = EditorContentSnapshot()

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

    fun retryLoadExisting() {
        val id = cocktailId ?: return
        val current = _uiState.value
        if (current.hasLoadedExisting || current.isLoadingExisting) return
        _uiState.update { it.copy(isLoadingExisting = true, errorMessage = null) }
        loadExistingCocktail(id)
    }

    private fun loadExistingCocktail(id: Int) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val observerJob = launch {
                val detail = cocktailRepository.observeCocktailDetail(id)
                    .filterNotNull()
                    .first()
                if (!hasUserEdited) {
                    applyExistingCocktail(detail)
                }
            }
            val refreshResult = try {
                cocktailRepository.refreshCocktail(id)
            } finally {
                observerJob.cancel()
            }
            refreshResult.fold(
                onSuccess = { freshDetail ->
                    if (!hasUserEdited) {
                        applyExistingCocktail(freshDetail)
                    }
                },
                onFailure = { error ->
                    if (!_uiState.value.hasLoadedExisting) {
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
        _uiState.update { state ->
            if (hasUserEdited) return@update state
            val hydrated = state.copy(
                isLoadingExisting = false,
                hasLoadedExisting = true,
                errorMessage = null,
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
                            units = ing.units?.trim().orEmpty(),
                            optional = ing.optional,
                            isSpecified = ing.isSpecified,
                            note = ing.note.orEmpty(),
                            substitutes = ing.substitutes.map { sub ->
                                IngredientSubstituteInput(
                                    ingredientId = sub.id,
                                    ingredientName = sub.name,
                                    amount = sub.amount,
                                    amountMax = sub.amountMax,
                                    units = sub.units,
                                )
                            },
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
            baselineSnapshot = hydrated.toContentSnapshot()
            hydrated.copy(hasUnsavedChanges = false)
        }
    }

    private inline fun updateDraft(
        clearError: Boolean = false,
        transform: (CocktailEditorUiState) -> CocktailEditorUiState,
    ) {
        _uiState.update { current ->
            if (current.isSaving) return@update current
            val updated = transform(current)
            if (updated == current) {
                current
            } else {
                hasUserEdited = true
                val next = if (clearError) updated.copy(errorMessage = null) else updated
                next.copy(hasUnsavedChanges = next.toContentSnapshot() != baselineSnapshot)
            }
        }
    }

    fun onNameChanged(value: String) {
        updateDraft(clearError = true) { state ->
            if (value == state.name) state else state.copy(name = value)
        }
    }

    fun onInstructionsChanged(value: String) {
        updateDraft(clearError = true) { state ->
            if (value == state.instructions) state else state.copy(instructions = value)
        }
    }

    fun onDescriptionChanged(value: String) {
        updateDraft { state ->
            if (value == state.description) state else state.copy(description = value)
        }
    }

    fun onGarnishChanged(value: String) {
        updateDraft { state ->
            if (value == state.garnish) state else state.copy(garnish = value)
        }
    }

    fun onSourceChanged(value: String) {
        updateDraft { state ->
            if (value == state.source) state else state.copy(source = value)
        }
    }

    fun onGlassSelected(glassId: Int?) {
        updateDraft { state ->
            if (glassId == state.glassId) state else state.copy(glassId = glassId)
        }
    }

    fun onMethodSelected(methodId: Int?) {
        updateDraft { state ->
            if (methodId == state.methodId) state else state.copy(methodId = methodId)
        }
    }

    fun toggleTag(tagName: String) {
        val cleaned = tagName.trim()
        if (cleaned.isEmpty()) return
        updateDraft { state ->
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
        updateDraft { state ->
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
        updateDraft(clearError = true) { state ->
            state.copy(ingredients = state.ingredients + EditorIngredientRow(localKey = key))
        }
        return key
    }

    fun addIngredientRow(ingredient: IngredientSummary): Long {
        val key = nextLocalKey()
        updateDraft(clearError = true) { state ->
            state.copy(
                ingredients = state.ingredients + EditorIngredientRow(
                    localKey = key,
                    ingredientId = ingredient.id,
                    ingredientName = ingredient.name,
                    amountText = "1",
                    units = "oz",
                ),
            )
        }
        return key
    }

    fun updateIngredientSelection(localKey: Long, ingredient: IngredientSummary) {
        updateDraft(clearError = true) { state ->
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
        updateDraft(clearError = true) { state ->
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
            )
        }
    }

    fun moveIngredientUp(localKey: Long) {
        updateDraft { state ->
            val index = state.ingredients.indexOfFirst { it.localKey == localKey }
            if (index <= 0) return@updateDraft state
            val mutable = state.ingredients.toMutableList()
            val item = mutable.removeAt(index)
            mutable.add(index - 1, item)
            state.copy(ingredients = mutable)
        }
    }

    fun moveIngredientDown(localKey: Long) {
        updateDraft { state ->
            val index = state.ingredients.indexOfFirst { it.localKey == localKey }
            if (index < 0 || index >= state.ingredients.lastIndex) return@updateDraft state
            val mutable = state.ingredients.toMutableList()
            val item = mutable.removeAt(index)
            mutable.add(index + 1, item)
            state.copy(ingredients = mutable)
        }
    }

    fun removeIngredientRow(localKey: Long) {
        updateDraft(clearError = true) { state ->
            val filtered = state.ingredients.filterNot { it.localKey == localKey }
            if (filtered.size == state.ingredients.size) state else state.copy(ingredients = filtered)
        }
    }

    fun addLocalPhoto(file: PlatformFile) {
        if (_uiState.value.isSaving) return
        activePhotoJobs += 1
        _uiState.update { it.copy(isProcessingPhoto = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                val rawBytes = file.readBytes()
                if (rawBytes.isEmpty()) {
                    _uiState.update { it.copy(errorMessage = "Selected photo is empty.") }
                    return@launch
                }
                if (rawBytes.size > MAX_RAW_PHOTO_BYTES) {
                    _uiState.update { it.copy(errorMessage = "Selected photo is too large (max 25 MB).") }
                    return@launch
                }
                val compressedBytes = FileKit.compressImage(
                    bytes = rawBytes,
                    imageFormat = ImageFormat.JPEG,
                    quality = COMPRESSED_PHOTO_QUALITY,
                    maxWidth = COMPRESSED_PHOTO_MAX_DIMENSION,
                    maxHeight = COMPRESSED_PHOTO_MAX_DIMENSION,
                )
                if (compressedBytes.isEmpty()) {
                    _uiState.update { it.copy(errorMessage = "Selected photo could not be processed.") }
                    return@launch
                }
                addLocalPhoto(
                    bytes = compressedBytes,
                    fileName = normalizeJpegFileName(file.name),
                    mimeType = "image/jpeg",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(errorMessage = e.message ?: "Unable to process selected photo.")
                }
            } finally {
                activePhotoJobs = (activePhotoJobs - 1).coerceAtLeast(0)
                val stillProcessing = activePhotoJobs > 0
                _uiState.update { it.copy(isProcessingPhoto = stillProcessing) }
            }
        }
    }

    fun addLocalPhoto(
        bytes: ByteArray,
        fileName: String,
        mimeType: String?,
    ) {
        if (bytes.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Selected photo is empty.") }
            return
        }
        if (bytes.size > MAX_RAW_PHOTO_BYTES) {
            _uiState.update { it.copy(errorMessage = "Selected photo is too large (max 25 MB).") }
            return
        }
        val item = EditorPhotoItem.PendingLocal(
            localKey = nextLocalKey(),
            bytes = bytes,
            fileName = fileName,
            mimeType = mimeType,
        )
        updateDraft(clearError = true) { state ->
            state.copy(photos = state.photos + item)
        }
    }

    fun removePhoto(key: String) {
        updateDraft { state ->
            val filtered = state.photos.filterNot { it.stableKey == key }
            if (filtered.size == state.photos.size) state else state.copy(photos = filtered)
        }
    }

    fun importFromUrl(rawUrl: String) {
        val url = rawUrl.trim()
        if (url.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Enter a valid recipe URL.") }
            return
        }
        while (true) {
            val current = _uiState.value
            if (current.isBusy || (current.isEditing && !current.hasLoadedExisting)) return
            if (_uiState.compareAndSet(current, current.copy(isImporting = true, errorMessage = null))) {
                break
            }
        }

        viewModelScope.launch {
            try {
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
                                units = item.units?.trim().orEmpty(),
                                optional = false,
                                note = item.note?.trim().orEmpty(),
                                unmatchedScrapedHint = if (matched == null) {
                                    item.sourceLine?.trim()?.takeIf { it.isNotEmpty() } ?: cleanName
                                } else {
                                    null
                                },
                            )
                        }

                        hasUserEdited = true
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

                            val next = current.copy(
                                isImporting = false,
                                name = scraped.name.trim().ifEmpty { current.name },
                                instructions = scraped.instructions.trim().ifEmpty { current.instructions },
                                description = scraped.description?.trim()?.takeIf { it.isNotEmpty() } ?: current.description,
                                garnish = scraped.garnish?.trim()?.takeIf { it.isNotEmpty() } ?: current.garnish,
                                source = scraped.source?.trim()?.takeIf { it.isNotEmpty() } ?: url,
                                glassId = matchedGlassId ?: current.glassId,
                                methodId = matchedMethodId ?: current.methodId,
                                tags = mergedTags,
                                ingredients = if (mappedIngredients.isNotEmpty()) mappedIngredients else current.ingredients,
                                photos = current.photos + importedPhotos,
                            )
                            next.copy(hasUnsavedChanges = next.toContentSnapshot() != baselineSnapshot)
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        errorMessage = e.message ?: "Unable to import recipe from URL.",
                    )
                }
            }
        }
    }

    fun save() {
        var validatedSnapshot: CocktailEditorUiState? = null
        while (true) {
            val current = _uiState.value
            if (!current.canSubmitSave) return
            val validationError = validateForSave(current)
            if (validationError != null) {
                if (_uiState.compareAndSet(current, current.copy(errorMessage = validationError))) {
                    return
                }
                continue
            }
            if (_uiState.compareAndSet(current, current.copy(isSaving = true, errorMessage = null))) {
                validatedSnapshot = current
                break
            }
        }
        val snapshot = validatedSnapshot

        viewModelScope.launch {
            try {
                val collectedImageIds = mutableListOf<Int>()
                val uploadedByKey = mutableMapOf<String, EditorPhotoItem.Existing>()

                for (index in snapshot.photos.indices) {
                    val sortOrder = index + 1
                    when (val photo = snapshot.photos[index]) {
                        is EditorPhotoItem.Existing -> collectedImageIds += photo.id

                        is EditorPhotoItem.PendingLocal -> {
                            val uploaded = cocktailRepository.uploadImage(
                                bytes = photo.bytes,
                                fileName = photo.fileName,
                                mimeType = photo.mimeType,
                                sort = sortOrder,
                            ).getOrElse { error ->
                                _uiState.update { current ->
                                    current.copy(
                                        isSaving = false,
                                        photos = reconcileUploadedPhotos(current.photos, uploadedByKey),
                                        errorMessage = error.message ?: "Failed to upload photo ${photo.fileName}.",
                                    )
                                }
                                return@launch
                            }
                            uploadedByKey[photo.stableKey] = EditorPhotoItem.Existing(
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
                                _uiState.update { current ->
                                    current.copy(
                                        isSaving = false,
                                        photos = reconcileUploadedPhotos(current.photos, uploadedByKey),
                                        errorMessage = error.message ?: "Failed to upload imported image.",
                                    )
                                }
                                return@launch
                            }
                            uploadedByKey[photo.stableKey] = EditorPhotoItem.Existing(
                                id = uploaded.id,
                                url = uploaded.url,
                                thumbUrl = uploaded.thumbUrl,
                            )
                            collectedImageIds += uploaded.id
                        }
                    }
                }

                _uiState.update { current ->
                    current.copy(photos = reconcileUploadedPhotos(current.photos, uploadedByKey))
                }

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
                        val amount = row.amountText.trim().takeIf { it.isNotEmpty() }?.toDouble() ?: 0.0
                        CocktailIngredientInput(
                            ingredientId = requireNotNull(row.ingredientId),
                            ingredientName = row.ingredientName,
                            amount = amount,
                            amountMax = row.amountMaxText.trim().takeIf { it.isNotEmpty() }?.toDouble(),
                            units = row.units.trim(),
                            sort = index + 1,
                            optional = row.optional,
                            isSpecified = row.isSpecified,
                            note = row.note.trim().takeIf { it.isNotEmpty() },
                            substitutes = row.substitutes,
                        )
                    },
                )

                cocktailRepository.saveCocktail(cocktailId, request).fold(
                    onSuccess = { saved ->
                        _uiState.update { current ->
                            baselineSnapshot = current.toContentSnapshot()
                            current.copy(
                                isSaving = false,
                                hasUnsavedChanges = false,
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = e.message ?: "Unable to save recipe.",
                    )
                }
            }
        }
    }

    fun consumeSavedEvent() {
        _uiState.update { it.copy(savedCocktailId = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun reconcileUploadedPhotos(
        photos: List<EditorPhotoItem>,
        uploadedByKey: Map<String, EditorPhotoItem.Existing>,
    ): List<EditorPhotoItem> {
        if (uploadedByKey.isEmpty()) return photos
        return photos.map { photo -> uploadedByKey[photo.stableKey] ?: photo }
    }

    private suspend fun resolveLatestCatalog(): BarCatalog {
        val current = _uiState.value.catalog
        if (current.ingredients.isNotEmpty() || current.glasses.isNotEmpty() || current.methods.isNotEmpty()) {
            return current
        }
        return cocktailRepository.observeCatalog().first()
    }

    private fun validateForSave(state: CocktailEditorUiState): String? {
        if (state.isEditing && !state.hasLoadedExisting) {
            return "Load the existing recipe before saving."
        }
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
            val trimmedAmount = row.amountText.trim()
            val trimmedUnits = row.units.trim()
            val amount = if (trimmedAmount.isEmpty()) {
                if (trimmedUnits.isNotEmpty()) {
                    0.0
                } else {
                    return "Enter an amount or unit for ${row.ingredientName}."
                }
            } else {
                val parsed = trimmedAmount.toDoubleOrNull()
                if (parsed == null || !parsed.isFinite() || parsed < 0.0) {
                    return "Enter a valid amount for ${row.ingredientName}."
                }
                parsed
            }
            if (row.amountMaxText.isNotBlank()) {
                val maxAmount = row.amountMaxText.trim().toDoubleOrNull()
                if (maxAmount == null || !maxAmount.isFinite() || maxAmount < 0.0 || maxAmount < amount) {
                    return "Enter a valid max amount for ${row.ingredientName}."
                }
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

    private fun normalizeJpegFileName(originalName: String): String {
        val base = originalName.trim().substringBeforeLast('.').ifBlank { "photo" }
        return "$base.jpg"
    }
}
