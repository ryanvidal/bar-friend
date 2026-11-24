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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import coil3.compose.AsyncImage
import com.ryanvidal.barfriend.core.designsystem.BarFriendTheme
import com.ryanvidal.barfriend.core.designsystem.EmptyStateCard
import com.ryanvidal.barfriend.core.designsystem.LocalBarFriendStatusColors
import com.ryanvidal.barfriend.core.designsystem.SectionHeader
import com.ryanvidal.barfriend.core.model.AppThemeMode
import com.ryanvidal.barfriend.core.model.BarCatalog
import com.ryanvidal.barfriend.core.model.CocktailMethod
import com.ryanvidal.barfriend.core.model.CocktailTag
import com.ryanvidal.barfriend.core.model.Glass
import com.ryanvidal.barfriend.core.model.IngredientSummary
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitOpenCameraSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberCameraPickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlinx.coroutines.launch

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
    var showDiscardDialog by rememberSaveable { mutableStateOf(false) }

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
            viewModel.addLocalPhoto(file)
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
            viewModel.addLocalPhoto(file)
        }
    }

    val navigationEventState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    NavigationBackHandler(
        state = navigationEventState,
        isBackEnabled = uiState.hasUnsavedChanges || uiState.isSaving,
        onBackCompleted = {
            if (!uiState.isSaving) {
                showDiscardDialog = true
            }
        },
    )

    val handleBack: () -> Unit = {
        when {
            uiState.isSaving -> Unit
            uiState.hasUnsavedChanges -> showDiscardDialog = true
            else -> onBack()
        }
    }

    LaunchedEffect(uiState.savedCocktailId) {
        val savedId = uiState.savedCocktailId
        if (savedId != null) {
            viewModel.consumeSavedEvent()
            onSaved(savedId)
        }
    }

    CocktailEditorContent(
        uiState = uiState,
        onBack = handleBack,
        onRetryLoad = viewModel::retryLoadExisting,
        onSave = viewModel::save,
        onImportFromUrl = viewModel::importFromUrl,
        onOpenPhotoLibrary = { galleryLauncher.launch() },
        onOpenCamera = { cameraLauncher.launch() },
        onRemovePhoto = viewModel::removePhoto,
        onNameChanged = viewModel::onNameChanged,
        onDescriptionChanged = viewModel::onDescriptionChanged,
        onGarnishChanged = viewModel::onGarnishChanged,
        onSourceChanged = viewModel::onSourceChanged,
        onGlassSelected = viewModel::onGlassSelected,
        onMethodSelected = viewModel::onMethodSelected,
        onToggleTag = viewModel::toggleTag,
        onAddCustomTag = viewModel::addCustomTag,
        onAddIngredient = viewModel::addIngredientRow,
        onUpdateIngredientSelection = viewModel::updateIngredientSelection,
        onUpdateIngredientRow = viewModel::updateIngredientRow,
        onMoveIngredientUp = viewModel::moveIngredientUp,
        onMoveIngredientDown = viewModel::moveIngredientDown,
        onRemoveIngredientRow = viewModel::removeIngredientRow,
        onInstructionsChanged = viewModel::onInstructionsChanged,
        onClearError = viewModel::clearError,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("Discard unsaved changes?") },
            text = { Text("You have unsaved recipe edits that will be lost if you leave now.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        onBack()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Discard")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text("Keep Editing")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CocktailEditorContent(
    uiState: CocktailEditorUiState,
    onBack: () -> Unit,
    onRetryLoad: () -> Unit,
    onSave: () -> Unit,
    onImportFromUrl: (String) -> Unit,
    onOpenPhotoLibrary: () -> Unit,
    onOpenCamera: () -> Unit,
    onRemovePhoto: (String) -> Unit,
    onNameChanged: (String) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onGarnishChanged: (String) -> Unit,
    onSourceChanged: (String) -> Unit,
    onGlassSelected: (Int?) -> Unit,
    onMethodSelected: (Int?) -> Unit,
    onToggleTag: (String) -> Unit,
    onAddCustomTag: (String) -> Unit,
    onAddIngredient: (IngredientSummary) -> Unit,
    onUpdateIngredientSelection: (Long, IngredientSummary) -> Unit,
    onUpdateIngredientRow: (
        localKey: Long,
        amountText: String?,
        amountMaxText: String?,
        units: String?,
        optional: Boolean?,
        note: String?,
    ) -> Unit,
    onMoveIngredientUp: (Long) -> Unit,
    onMoveIngredientDown: (Long) -> Unit,
    onRemoveIngredientRow: (Long) -> Unit,
    onInstructionsChanged: (String) -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var showIngredientPicker by rememberSaveable { mutableStateOf(false) }
    var pickerTargetRowKey by rememberSaveable { mutableStateOf<Long?>(null) }
    var customTagDraft by rememberSaveable { mutableStateOf("") }

    val isBusy = uiState.isBusy
    val isFormEditable = !isBusy && (!uiState.isEditing || uiState.hasLoadedExisting)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(if (uiState.isEditing) "Edit Recipe" else "New Recipe")
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        enabled = !uiState.isSaving,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showImportDialog = true },
                        enabled = isFormEditable,
                    ) {
                        Icon(Icons.Rounded.Link, contentDescription = "Import from URL")
                    }
                    TextButton(
                        onClick = onSave,
                        enabled = uiState.canSubmitSave,
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
        when {
            uiState.isEditing && !uiState.hasLoadedExisting && uiState.isLoadingExisting -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.isEditing && !uiState.hasLoadedExisting -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyStateCard(
                        icon = Icons.Rounded.LocalBar,
                        title = "Unable to load recipe",
                        subtitle = uiState.errorMessage ?: "Connect to your Bar Assistant server and try again.",
                        actionLabel = "Retry",
                        onActionClick = onRetryLoad,
                    )
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    if (isBusy) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = when {
                                    uiState.isLoadingExisting -> "Loading recipe..."
                                    uiState.isImporting -> "Importing recipe from URL..."
                                    uiState.isProcessingPhoto -> "Processing photo..."
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
                            onDismiss = onClearError,
                        )
                    }

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
                                        enabled = isFormEditable,
                                        onRemove = { onRemovePhoto(photo.stableKey) },
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OutlinedButton(
                                onClick = onOpenPhotoLibrary,
                                enabled = isFormEditable,
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
                                onClick = onOpenCamera,
                                enabled = isFormEditable,
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

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader(title = "Details")
                        OutlinedTextField(
                            value = uiState.name,
                            onValueChange = onNameChanged,
                            enabled = isFormEditable,
                            label = { Text("Recipe Name *") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = uiState.description,
                            onValueChange = onDescriptionChanged,
                            enabled = isFormEditable,
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
                                enabled = isFormEditable,
                                onSelect = onGlassSelected,
                                modifier = Modifier.weight(1f),
                            )
                            EditorDropdownField(
                                label = "Method",
                                selectedLabel = uiState.catalog.methods
                                    .firstOrNull { it.id == uiState.methodId }?.name ?: "None",
                                options = uiState.catalog.methods.map { it.id to it.name },
                                noneLabel = "None",
                                enabled = isFormEditable,
                                onSelect = onMethodSelected,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        OutlinedTextField(
                            value = uiState.garnish,
                            onValueChange = onGarnishChanged,
                            enabled = isFormEditable,
                            label = { Text("Garnish") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = uiState.source,
                            onValueChange = onSourceChanged,
                            enabled = isFormEditable,
                            label = { Text("Source") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

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
                                        enabled = isFormEditable,
                                        onClick = { onToggleTag(tagName) },
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
                                enabled = isFormEditable,
                                label = { Text("Add custom tag") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            FilledTonalButton(
                                onClick = {
                                    onAddCustomTag(customTagDraft)
                                    customTagDraft = ""
                                },
                                enabled = isFormEditable && customTagDraft.isNotBlank(),
                            ) {
                                Text("Add")
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionHeader(
                            title = "Ingredients (${uiState.ingredients.size})",
                            trailing = {
                                FilledTonalButton(
                                    onClick = {
                                        pickerTargetRowKey = null
                                        showIngredientPicker = true
                                    },
                                    enabled = isFormEditable,
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
                                key(row.localKey) {
                                    EditorIngredientCard(
                                        row = row,
                                        enabled = isFormEditable,
                                        canMoveUp = index > 0,
                                        canMoveDown = index < uiState.ingredients.lastIndex,
                                        onPickIngredient = {
                                            pickerTargetRowKey = row.localKey
                                            showIngredientPicker = true
                                        },
                                        onAmountChanged = { value ->
                                            onUpdateIngredientRow(row.localKey, value, null, null, null, null)
                                        },
                                        onAmountMaxChanged = { value ->
                                            onUpdateIngredientRow(row.localKey, null, value, null, null, null)
                                        },
                                        onUnitChanged = { value ->
                                            onUpdateIngredientRow(row.localKey, null, null, value, null, null)
                                        },
                                        onOptionalToggled = {
                                            onUpdateIngredientRow(row.localKey, null, null, null, !row.optional, null)
                                        },
                                        onNoteChanged = { value ->
                                            onUpdateIngredientRow(row.localKey, null, null, null, null, value)
                                        },
                                        onMoveUp = { onMoveIngredientUp(row.localKey) },
                                        onMoveDown = { onMoveIngredientDown(row.localKey) },
                                        onRemove = { onRemoveIngredientRow(row.localKey) },
                                    )
                                }
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(title = "Instructions *")
                        OutlinedTextField(
                            value = uiState.instructions,
                            onValueChange = onInstructionsChanged,
                            enabled = isFormEditable,
                            placeholder = {
                                Text("1. Combine ingredients with ice.\n2. Shake or stir until chilled.\n3. Strain into prepared glass.")
                            },
                            minLines = 5,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Button(
                        onClick = onSave,
                        enabled = uiState.canSubmitSave,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                    ) {
                        Text(if (uiState.isEditing) "Save Changes" else "Save Recipe")
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }

    if (showImportDialog) {
        EditorImportUrlDialog(
            onDismiss = { showImportDialog = false },
            onImport = { url ->
                showImportDialog = false
                onImportFromUrl(url)
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
                    onAddIngredient(ingredient)
                } else {
                    onUpdateIngredientSelection(targetKey, ingredient)
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
    val missingColor = LocalBarFriendStatusColors.current.missing
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = missingColor.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, missingColor.copy(alpha = 0.45f)),
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
                tint = missingColor,
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
    enabled: Boolean,
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
        IconButton(
            onClick = onRemove,
            enabled = enabled,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(48.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                modifier = Modifier.size(28.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Remove photo",
                        modifier = Modifier.size(16.dp),
                    )
                }
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
    enabled: Boolean,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = enabled),
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
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

@Composable
private fun EditorIngredientCard(
    row: EditorIngredientRow,
    enabled: Boolean,
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
    val substituteColor = LocalBarFriendStatusColors.current.substitute
    val isUnmatched = row.ingredientId == null
    OutlinedCard(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (isUnmatched) substituteColor else MaterialTheme.colorScheme.outlineVariant,
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
                        tint = substituteColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Unmatched: ${row.unmatchedScrapedHint ?: row.ingredientName.ifBlank { "Select ingredient" }}",
                        style = MaterialTheme.typography.labelMedium,
                        color = substituteColor,
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
                    enabled = enabled,
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
                                substituteColor
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onMoveUp, enabled = enabled && canMoveUp) {
                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = onMoveDown, enabled = enabled && canMoveDown) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move down")
                }
                IconButton(onClick = onRemove, enabled = enabled) {
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
                    enabled = enabled,
                    label = { Text("Amount") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = row.amountMaxText,
                    onValueChange = onAmountMaxChanged,
                    enabled = enabled,
                    label = { Text("Max") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                UnitDropdownField(
                    selectedUnit = row.units,
                    enabled = enabled,
                    onSelectUnit = onUnitChanged,
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = row.optional,
                    enabled = enabled,
                    onClick = onOptionalToggled,
                    label = { Text("Optional") },
                )
            }

            OutlinedTextField(
                value = row.note,
                onValueChange = onNoteChanged,
                enabled = enabled,
                label = { Text("Note") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitDropdownField(
    selectedUnit: String,
    enabled: Boolean,
    onSelectUnit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selectedUnit,
            onValueChange = onSelectUnit,
            enabled = enabled,
            label = { Text("Unit") },
            placeholder = { Text("None / custom") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled = enabled),
        )
        ExposedDropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("None (unitless)") },
                onClick = {
                    onSelectUnit("")
                    expanded = false
                },
            )
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
    val readyColor = LocalBarFriendStatusColors.current.ready
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
                .imePadding()
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
                                        color = readyColor.copy(alpha = 0.16f),
                                    ) {
                                        Text(
                                            text = "In Bar",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = readyColor,
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

private fun previewEditorUiState(): CocktailEditorUiState = CocktailEditorUiState(
    isEditing = true,
    hasLoadedExisting = true,
    name = "Negroni",
    description = "Equal parts gin, Campari, and sweet vermouth.",
    garnish = "Orange peel",
    source = "IBA",
    glassId = 10,
    methodId = 20,
    tags = listOf("Classic", "Bitter"),
    instructions = "1. Add all ingredients to a mixing glass with ice.\n2. Stir until well chilled.\n3. Strain over a large ice cube.",
    catalog = BarCatalog(
        glasses = listOf(Glass(10, "Rocks", 250.0, "ml"), Glass(11, "Coupe", 160.0, "ml")),
        methods = listOf(CocktailMethod(20, "Stir", 20), CocktailMethod(21, "Shake", 25)),
        tags = listOf(CocktailTag(1, "Classic"), CocktailTag(2, "Bitter"), CocktailTag(3, "Aperitivo")),
    ),
    ingredients = listOf(
        EditorIngredientRow(
            localKey = 1L,
            ingredientId = 1,
            ingredientName = "London Dry Gin",
            amountText = "1",
            units = "oz",
        ),
        EditorIngredientRow(
            localKey = 2L,
            ingredientId = null,
            ingredientName = "Campari",
            amountText = "1",
            units = "oz",
            unmatchedScrapedHint = "1 oz Campari",
        ),
    ),
)

@Preview
@Composable
private fun CocktailEditorContentDarkPreview() {
    BarFriendTheme(themeMode = AppThemeMode.DARK) {
        CocktailEditorPreviewHost()
    }
}

@Preview
@Composable
private fun CocktailEditorContentLightPreview() {
    BarFriendTheme(themeMode = AppThemeMode.LIGHT) {
        CocktailEditorPreviewHost()
    }
}

@Composable
private fun CocktailEditorPreviewHost() {
    CocktailEditorContent(
        uiState = previewEditorUiState(),
        onBack = {},
        onRetryLoad = {},
        onSave = {},
        onImportFromUrl = {},
        onOpenPhotoLibrary = {},
        onOpenCamera = {},
        onRemovePhoto = {},
        onNameChanged = {},
        onDescriptionChanged = {},
        onGarnishChanged = {},
        onSourceChanged = {},
        onGlassSelected = {},
        onMethodSelected = {},
        onToggleTag = {},
        onAddCustomTag = {},
        onAddIngredient = {},
        onUpdateIngredientSelection = { _, _ -> },
        onUpdateIngredientRow = { _, _, _, _, _, _ -> },
        onMoveIngredientUp = {},
        onMoveIngredientDown = {},
        onRemoveIngredientRow = {},
        onInstructionsChanged = {},
        onClearError = {},
    )
}
