package com.ryanvidal.barfriend.feature.cocktails

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocalBar
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.ryanvidal.barfriend.core.designsystem.SectionHeader
import com.ryanvidal.barfriend.core.designsystem.StatusMissingTerracotta
import com.ryanvidal.barfriend.core.designsystem.StatusReadyGreen
import com.ryanvidal.barfriend.core.designsystem.StatusSubstituteAmber
import com.ryanvidal.barfriend.core.model.IngredientSummary
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitOpenCameraSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberCameraPickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.mimeType
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CocktailEditorScreen(
    viewModel: CocktailEditorViewModel,
    onBack: () -> Unit,
    onSaved: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var showIngredientPicker by rememberSaveable { mutableStateOf(false) }
    var pickerTargetRowKey by rememberSaveable { mutableStateOf<Long?>(null) }
    var customTagDraft by rememberSaveable { mutableStateOf("") }

    // FileKit launchers must stay registered at the screen root, outside sheets/dialogs.
    val galleryLauncher = rememberFilePickerLauncher(
        type = FileKitType.Image,
        onError = { failure ->
            scope.launch {
                snackbarHostState.showSnackbar(failure.message ?: "Unable to open photo library.")
            }
        },
    ) { file: PlatformFile? ->
        if (file != null) {
            scope.launch {
                runCatching {
                    val bytes = file.readBytes()
                    val mime = file.mimeType()?.let { "${it.primaryType}/${it.subtype}" }
                    viewModel.addLocalPhoto(
                        bytes = bytes,
                        fileName = file.name,
                        mimeType = mime,
                    )
                }.onFailure { error ->
                    snackbarHostState.showSnackbar(error.message ?: "Unable to read selected photo.")
                }
            }
        }
    }

    val cameraLauncher = rememberCameraPickerLauncher(
        openCameraSettings = FileKitOpenCameraSettings.createDefault(),
        onError = { failure ->
            scope.launch {
                snackbarHostState.showSnackbar(failure.message ?: "Camera is unavailable on this device.")
            }
        },
    ) { file: PlatformFile? ->
        if (file != null) {
            scope.launch {
                runCatching {
                    val bytes = file.readBytes()
                    val mime = file.mimeType()?.let { "${it.primaryType}/${it.subtype}" } ?: "image/jpeg"
                    viewModel.addLocalPhoto(
                        bytes = bytes,
                        fileName = file.name,
                        mimeType = mime,
                    )
                }.onFailure { error ->
                    snackbarHostState.showSnackbar(error.message ?: "Unable to read captured photo.")
                }
            }
        }
    }

    LaunchedEffect(uiState.savedCocktailId) {
        val savedId = uiState.savedCocktailId
        if (savedId != null) {
            onSaved(savedId)
        }
    }

    val isBusy = uiState.isSaving || uiState.isImporting || uiState.isLoadingExisting

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(if (uiState.isEditing) "Edit Recipe" else "New Recipe")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showImportDialog = true },
                        enabled = !isBusy,
                    ) {
                        Icon(Icons.Rounded.Link, contentDescription = "Import from URL")
                    }
                    TextButton(
                        onClick = viewModel::save,
                        enabled = !isBusy,
                    ) {
                        if (uiState.isSaving) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Text("Save")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (uiState.isLoadingExisting || uiState.isImporting || uiState.isSaving) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = when {
                            uiState.isLoadingExisting -> "Loading recipe..."
                            uiState.isImporting -> "Importing recipe from URL..."
                            else -> "Saving recipe and uploading photos..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            uiState.errorMessage?.let { error ->
                EditorErrorBanner(
                    message = error,
                    onDismiss = viewModel::clearError,
                )
            }

            // Photos Section
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(title = "Photos")
                if (uiState.photos.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(
                            items = uiState.photos,
                            key = { it.stableKey },
                        ) { photo ->
                            EditorPhotoThumbnail(
                                item = photo,
                                onRemove = { viewModel.removePhoto(photo.stableKey) },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = { galleryLauncher.launch() },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PhotoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Add Photo")
                    }
                    OutlinedButton(
                        onClick = { cameraLauncher.launch() },
                        enabled = !isBusy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PhotoCamera,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Camera")
                    }
                }
            }

            // Basics Section
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(title = "Details")
                OutlinedTextField(
                    value = uiState.name,
                    onValueChange = viewModel::onNameChanged,
                    label = { Text("Recipe Name *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = uiState.description,
                    onValueChange = viewModel::onDescriptionChanged,
                    label = { Text("Description") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    EditorDropdownField(
                        label = "Glass",
                        selectedLabel = uiState.catalog.glasses
                            .firstOrNull { it.id == uiState.glassId }?.name ?: "None",
                        options = uiState.catalog.glasses.map { it.id to it.name },
                        noneLabel = "None",
                        onSelect = viewModel::onGlassSelected,
                        modifier = Modifier.weight(1f),
                    )
                    EditorDropdownField(
                        label = "Method",
                        selectedLabel = uiState.catalog.methods
                            .firstOrNull { it.id == uiState.methodId }?.name ?: "None",
                        options = uiState.catalog.methods.map { it.id to it.name },
                        noneLabel = "None",
                        onSelect = viewModel::onMethodSelected,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = uiState.garnish,
                    onValueChange = viewModel::onGarnishChanged,
                    label = { Text("Garnish") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = uiState.source,
                    onValueChange = viewModel::onSourceChanged,
                    label = { Text("Source") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Tags Section
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(title = "Tags")
                val displayedTags = remember(uiState.catalog.tags, uiState.tags) {
                    val seen = mutableSetOf<String>()
                    buildList {
                        uiState.catalog.tags.forEach { tag ->
                            if (seen.add(tag.name.lowercase())) add(tag.name)
                        }
                        uiState.tags.forEach { custom ->
                            if (seen.add(custom.lowercase())) add(custom)
                        }
                    }
                }
                if (displayedTags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        displayedTags.forEach { tagName ->
                            val selected = uiState.tags.any { it.equals(tagName, ignoreCase = true) }
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.toggleTag(tagName) },
                                label = { Text(tagName) },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = customTagDraft,
                        onValueChange = { customTagDraft = it },
                        label = { Text("Add custom tag") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(
                        onClick = {
                            viewModel.addCustomTag(customTagDraft)
                            customTagDraft = ""
                        },
                        enabled = customTagDraft.isNotBlank(),
                    ) {
                        Text("Add")
                    }
                }
            }

            // Ingredients Section
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionHeader(
                    title = "Ingredients (${uiState.ingredients.size})",
                    trailing = {
                        FilledTonalButton(
                            onClick = {
                                pickerTargetRowKey = null
                                showIngredientPicker = true
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add Ingredient")
                        }
                    },
                )

                if (uiState.ingredients.isEmpty()) {
                    OutlinedCard(
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Tap \"Add Ingredient\" to pick ingredients from your bar catalog.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                } else {
                    uiState.ingredients.forEachIndexed { index, row ->
                        EditorIngredientCard(
                            row = row,
                            canMoveUp = index > 0,
                            canMoveDown = index < uiState.ingredients.lastIndex,
                            onPickIngredient = {
                                pickerTargetRowKey = row.localKey
                                showIngredientPicker = true
                            },
                            onAmountChanged = { viewModel.updateIngredientRow(row.localKey, amountText = it) },
                            onAmountMaxChanged = { viewModel.updateIngredientRow(row.localKey, amountMaxText = it) },
                            onUnitChanged = { viewModel.updateIngredientRow(row.localKey, units = it) },
                            onOptionalToggled = {
                                viewModel.updateIngredientRow(row.localKey, optional = !row.optional)
                            },
                            onNoteChanged = { viewModel.updateIngredientRow(row.localKey, note = it) },
                            onMoveUp = { viewModel.moveIngredientUp(row.localKey) },
                            onMoveDown = { viewModel.moveIngredientDown(row.localKey) },
                            onRemove = { viewModel.removeIngredientRow(row.localKey) },
                        )
                    }
                }
            }

            // Instructions Section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(title = "Instructions *")
                OutlinedTextField(
                    value = uiState.instructions,
                    onValueChange = viewModel::onInstructionsChanged,
                    placeholder = {
                        Text("1. Combine ingredients with ice.\n2. Shake or stir until chilled.\n3. Strain into prepared glass.")
                    },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = viewModel::save,
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(if (uiState.isEditing) "Save Changes" else "Save Recipe")
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showImportDialog) {
        EditorImportUrlDialog(
            onDismiss = { showImportDialog = false },
            onImport = { url ->
                showImportDialog = false
                viewModel.importFromUrl(url)
            },
        )
    }

    if (showIngredientPicker) {
        IngredientPickerSheet(
            ingredients = uiState.catalog.ingredients,
            onDismiss = { showIngredientPicker = false },
            onSelect = { ingredient ->
                val targetKey = pickerTargetRowKey
                if (targetKey == null) {
                    viewModel.addIngredientRow(ingredient)
                } else {
                    viewModel.updateIngredientSelection(targetKey, ingredient)
                }
                showIngredientPicker = false
            },
        )
    }
}

@Composable
private fun EditorErrorBanner(
    message: String,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = StatusMissingTerracotta.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, StatusMissingTerracotta.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = StatusMissingTerracotta,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = "Dismiss error")
            }
        }
    }
}

@Composable
private fun EditorPhotoThumbnail(
    item: EditorPhotoItem,
    onRemove: () -> Unit,
) {
    val model: Any = when (item) {
        is EditorPhotoItem.Existing -> item.thumbUrl.ifBlank { item.url }
        is EditorPhotoItem.PendingLocal -> item.bytes
        is EditorPhotoItem.PendingRemote -> item.url
    }
    val badgeLabel = when (item) {
        is EditorPhotoItem.Existing -> null
        is EditorPhotoItem.PendingLocal -> "Local"
        is EditorPhotoItem.PendingRemote -> "Imported"
    }

    Box(
        modifier = Modifier
            .size(116.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Icon(
            imageVector = Icons.Rounded.LocalBar,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            modifier = Modifier
                .align(Alignment.Center)
                .size(32.dp),
        )
        AsyncImage(
            model = model,
            contentDescription = "Recipe photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp),
        ) {
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Remove photo",
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        if (badgeLabel != null) {
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp),
            ) {
                Text(
                    text = badgeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorDropdownField(
    label: String,
    selectedLabel: String,
    options: List<Pair<Int, String>>,
    noneLabel: String,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(noneLabel) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            options.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorIngredientCard(
    row: EditorIngredientRow,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPickIngredient: () -> Unit,
    onAmountChanged: (String) -> Unit,
    onAmountMaxChanged: (String) -> Unit,
    onUnitChanged: (String) -> Unit,
    onOptionalToggled: () -> Unit,
    onNoteChanged: (String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val isUnmatched = row.ingredientId == null
    OutlinedCard(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isUnmatched) StatusSubstituteAmber else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (isUnmatched) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = StatusSubstituteAmber,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Unmatched: ${row.unmatchedScrapedHint ?: row.ingredientName.ifBlank { "Select ingredient" }}",
                        style = MaterialTheme.typography.labelMedium,
                        color = StatusSubstituteAmber,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Surface(
                    onClick = onPickIngredient,
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = row.ingredientName.ifBlank { "Match Bar Assistant ingredient" },
                            style = MaterialTheme.typography.titleSmall,
                            color = if (row.ingredientId != null) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                StatusSubstituteAmber
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move down")
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove ingredient")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = row.amountText,
                    onValueChange = onAmountChanged,
                    label = { Text("Amount *") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = row.amountMaxText,
                    onValueChange = onAmountMaxChanged,
                    label = { Text("Max") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(0.9f),
                )
                UnitDropdownField(
                    selectedUnit = row.units,
                    onSelectUnit = onUnitChanged,
                    modifier = Modifier.weight(1.1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = row.optional,
                    onClick = onOptionalToggled,
                    label = { Text("Optional") },
                )
                OutlinedTextField(
                    value = row.note,
                    onValueChange = onNoteChanged,
                    label = { Text("Note") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitDropdownField(
    selectedUnit: String,
    onSelectUnit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedUnit,
            onValueChange = {},
            readOnly = true,
            label = { Text("Unit") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            EDITOR_MEASUREMENT_UNITS.forEach { unit ->
                DropdownMenuItem(
                    text = { Text(unit) },
                    onClick = {
                        onSelectUnit(unit)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IngredientPickerSheet(
    ingredients: List<IngredientSummary>,
    onDismiss: () -> Unit,
    onSelect: (IngredientSummary) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(ingredients, query) {
        val needle = query.trim()
        if (needle.isEmpty()) {
            ingredients
        } else {
            ingredients.filter { item ->
                item.name.contains(needle, ignoreCase = true) ||
                    (item.categoryName?.contains(needle, ignoreCase = true) == true)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = 560.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Select Ingredient",
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search catalog ingredients") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (ingredients.isEmpty()) {
                            "Sync your bar catalog first to browse ingredients."
                        } else {
                            "No ingredients match \"$query\"."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(
                        items = filtered,
                        key = { it.id },
                    ) { ingredient ->
                        ListItem(
                            headlineContent = { Text(ingredient.name) },
                            supportingContent = ingredient.categoryName?.let { cat ->
                                { Text(cat) }
                            },
                            trailingContent = if (ingredient.inBarShelf) {
                                {
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = StatusReadyGreen.copy(alpha = 0.16f),
                                    ) {
                                        Text(
                                            text = "In Bar",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = StatusReadyGreen,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                            modifier = Modifier.clickable { onSelect(ingredient) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorImportUrlDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf("") }
    val trimmed = url.trim()
    val isValid = trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Recipe from URL") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Paste a cocktail page URL to fill recipe fields and match ingredients.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Recipe URL") },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onImport(trimmed) },
                enabled = isValid,
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
