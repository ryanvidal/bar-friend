package com.ryanvidal.barfriend.feature.cocktails

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LocalBar
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.ryanvidal.barfriend.core.data.SyncStatus
import com.ryanvidal.barfriend.core.designsystem.BarFriendTheme
import com.ryanvidal.barfriend.core.designsystem.EmptyStateCard
import com.ryanvidal.barfriend.core.designsystem.InventoryStatusChip
import com.ryanvidal.barfriend.core.designsystem.LocalBarFriendStatusColors
import com.ryanvidal.barfriend.core.designsystem.SectionHeader
import com.ryanvidal.barfriend.core.model.AppThemeMode
import com.ryanvidal.barfriend.core.model.BarCatalog
import com.ryanvidal.barfriend.core.model.CocktailFilter
import com.ryanvidal.barfriend.core.model.CocktailSort
import com.ryanvidal.barfriend.core.model.CocktailSummary
import com.ryanvidal.barfriend.core.model.CocktailTag
import kotlin.math.abs

@Composable
fun CocktailsListScreen(
    viewModel: CocktailsListViewModel,
    onOpenCocktail: (Int) -> Unit,
    onAddRecipe: () -> Unit,
    onImportFromUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.actionErrorMessage) {
        val message = uiState.actionErrorMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.dismissActionError()
        }
    }

    CocktailsListContent(
        uiState = uiState,
        onQueryChanged = viewModel::onQueryChanged,
        onToggleCanMake = viewModel::toggleCanMakeOnly,
        onToggleFavorites = viewModel::toggleFavoritesOnly,
        onUpdateFilter = viewModel::updateFilter,
        onClearAdvancedFilters = viewModel::clearAdvancedFilters,
        onClearAllFilters = viewModel::clearAllFilters,
        onRefresh = viewModel::refresh,
        onOpenCocktail = onOpenCocktail,
        onToggleFavorite = viewModel::toggleFavorite,
        onAddRecipe = onAddRecipe,
        onImportFromUrl = onImportFromUrl,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CocktailsListContent(
    uiState: CocktailsListUiState,
    onQueryChanged: (String) -> Unit,
    onToggleCanMake: () -> Unit,
    onToggleFavorites: () -> Unit,
    onUpdateFilter: (CocktailFilter) -> Unit,
    onClearAdvancedFilters: () -> Unit,
    onClearAllFilters: () -> Unit,
    onRefresh: () -> Unit,
    onOpenCocktail: (Int) -> Unit,
    onToggleFavorite: (Int) -> Unit,
    onAddRecipe: () -> Unit,
    onImportFromUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val focusManager = LocalFocusManager.current
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }
    var showAddMenuSheet by rememberSaveable { mutableStateOf(false) }
    var showImportUrlDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Cocktails") },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddMenuSheet = true }) {
                Icon(Icons.Rounded.Add, contentDescription = "Add recipe")
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            OutlinedTextField(
                value = uiState.filter.query,
                onValueChange = onQueryChanged,
                placeholder = { Text("Search cocktails or ingredients") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.filter.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChanged("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )

            Spacer(modifier = Modifier.height(10.dp))

            QuickFilterBar(
                filter = uiState.filter,
                onToggleCanMake = onToggleCanMake,
                onToggleFavorites = onToggleFavorites,
                onOpenFilters = { showFilterSheet = true },
            )

            uiState.syncStatus.errorMessage?.let { syncError ->
                SyncErrorBanner(
                    message = syncError,
                    onRetry = onRefresh,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            PullToRefreshBox(
                isRefreshing = uiState.syncStatus.isSyncing,
                onRefresh = onRefresh,
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
            ) {
                when {
                    uiState.cocktails.isEmpty() &&
                        uiState.syncStatus.isSyncing &&
                        uiState.syncStatus.lastSyncedEpochMillis == null -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }
                    }

                    uiState.cocktails.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        ) {
                            if (uiState.filter.hasActiveFilters) {
                                EmptyStateCard(
                                    icon = Icons.Rounded.SearchOff,
                                    title = "No matching cocktails",
                                    subtitle = "Try adjusting your search or clearing active filters.",
                                    actionLabel = "Clear Filters",
                                    onActionClick = onClearAllFilters,
                                )
                            } else {
                                EmptyStateCard(
                                    icon = Icons.Rounded.LocalBar,
                                    title = "No cocktails yet",
                                    subtitle = "Create a new recipe or import one from a URL to populate your bar menu.",
                                    actionLabel = "Create Recipe",
                                    onActionClick = onAddRecipe,
                                )
                            }
                        }
                    }

                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 340.dp),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 8.dp,
                                bottom = 96.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(
                                items = uiState.cocktails,
                                key = { it.id },
                            ) { cocktail ->
                                CocktailListCard(
                                    cocktail = cocktail,
                                    lastSyncedEpochMillis = uiState.syncStatus.lastSyncedEpochMillis,
                                    onClick = { onOpenCocktail(cocktail.id) },
                                    onToggleFavorite = { onToggleFavorite(cocktail.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        AdvancedFiltersSheet(
            currentFilter = uiState.filter,
            catalog = uiState.catalog,
            onDismiss = { showFilterSheet = false },
            onReset = {
                onClearAdvancedFilters()
                showFilterSheet = false
            },
            onApply = { updated ->
                onUpdateFilter(updated)
                showFilterSheet = false
            },
        )
    }

    if (showAddMenuSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddMenuSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    text = "Add Recipe",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                ListItem(
                    headlineContent = { Text("Create New Recipe") },
                    supportingContent = { Text("Enter ingredients, steps, and photos manually") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier = Modifier.clickable {
                        showAddMenuSheet = false
                        onAddRecipe()
                    },
                )
                ListItem(
                    headlineContent = { Text("Import from URL") },
                    supportingContent = { Text("Extract recipe details from a web page") },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Rounded.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier = Modifier.clickable {
                        showAddMenuSheet = false
                        showImportUrlDialog = true
                    },
                )
            }
        }
    }

    if (showImportUrlDialog) {
        ImportUrlPromptDialog(
            onDismiss = { showImportUrlDialog = false },
            onImport = { url ->
                showImportUrlDialog = false
                onImportFromUrl(url)
            },
        )
    }
}

@Composable
private fun QuickFilterBar(
    filter: CocktailFilter,
    onToggleCanMake: () -> Unit,
    onToggleFavorites: () -> Unit,
    onOpenFilters: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = filter.canMakeOnly,
            onClick = onToggleCanMake,
            label = { Text("Can Make") },
        )
        FilterChip(
            selected = filter.favoritesOnly,
            onClick = onToggleFavorites,
            leadingIcon = {
                Icon(
                    imageVector = if (filter.favoritesOnly) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            label = { Text("Favorites") },
        )
        val activeCount = filter.activeAdvancedFilterCount
        FilterChip(
            selected = activeCount > 0,
            onClick = onOpenFilters,
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            label = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Filters")
                    if (activeCount > 0) {
                        Badge { Text(activeCount.toString()) }
                    }
                }
            },
        )
        AssistChip(
            onClick = onOpenFilters,
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.Sort,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            label = { Text(filter.sort.label) },
        )
    }
}

@Composable
private fun SyncErrorBanner(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CocktailListCard(
    cocktail: CocktailSummary,
    lastSyncedEpochMillis: Long?,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val missingColor = LocalBarFriendStatusColors.current.missing
    ElevatedCard(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            var imageFailed by remember(cocktail.mainImageThumbUrl, lastSyncedEpochMillis) {
                mutableStateOf(false)
            }
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.LocalBar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                    modifier = Modifier.size(44.dp),
                )
            }
            if (!cocktail.mainImageThumbUrl.isNullOrBlank() && !imageFailed) {
                AsyncImage(
                    model = cocktail.mainImageThumbUrl,
                    contentDescription = cocktail.name,
                    contentScale = ContentScale.Crop,
                    onError = { imageFailed = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconButton(
                onClick = onToggleFavorite,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(48.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (cocktail.isFavorite) {
                                Icons.Rounded.Favorite
                            } else {
                                Icons.Rounded.FavoriteBorder
                            },
                            contentDescription = if (cocktail.isFavorite) {
                                "Remove from favorites"
                            } else {
                                "Add to favorites"
                            },
                            tint = if (cocktail.isFavorite) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = cocktail.name,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            val metaParts = buildList {
                cocktail.abv?.let(::formatAbv)?.takeIf { it.isNotEmpty() }?.let(::add)
                cocktail.glassName?.takeIf { it.isNotBlank() }?.let(::add)
                cocktail.methodName?.takeIf { it.isNotBlank() }?.let(::add)
            }
            if (metaParts.isNotEmpty()) {
                Text(
                    text = metaParts.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (cocktail.tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    cocktail.tags.take(3).forEach { tag ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                        ) {
                            Text(
                                text = tag.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                InventoryStatusChip(
                    inBarShelf = cocktail.inBarShelf,
                    missingIngredientsCount = cocktail.missingIngredientsCount,
                )
                if (cocktail.missingIngredientsCount == 1 && cocktail.missingIngredientNames.isNotEmpty()) {
                    Text(
                        text = "Missing 1: ${cocktail.missingIngredientNames.first()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = missingColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private data class AbvPreset(
    val label: String,
    val min: Double?,
    val max: Double?,
)

private val ABV_PRESETS = listOf(
    AbvPreset("Zero Proof", 0.0, 0.5),
    AbvPreset("Low (<12%)", null, 12.0),
    AbvPreset("Medium (12–25%)", 12.0, 25.0),
    AbvPreset("Strong (25%+)", 25.0, null),
)

private val TagIdSetSaver = listSaver<Set<Int>, Int>(
    save = { it.toList() },
    restore = { it.toSet() },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedFiltersSheet(
    currentFilter: CocktailFilter,
    catalog: BarCatalog,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
    onApply: (CocktailFilter) -> Unit,
) {
    var selectedSortName by rememberSaveable(currentFilter) {
        mutableStateOf(currentFilter.sort.name)
    }
    var selectedTagIds by rememberSaveable(currentFilter, stateSaver = TagIdSetSaver) {
        mutableStateOf(currentFilter.tagIds)
    }
    var selectedGlassId by rememberSaveable(currentFilter) {
        mutableStateOf(currentFilter.glassId)
    }
    var selectedMethodId by rememberSaveable(currentFilter) {
        mutableStateOf(currentFilter.methodId)
    }
    var abvMinText by rememberSaveable(currentFilter) {
        mutableStateOf(currentFilter.abvMin?.let(::formatNumericInput).orEmpty())
    }
    var abvMaxText by rememberSaveable(currentFilter) {
        mutableStateOf(currentFilter.abvMax?.let(::formatNumericInput).orEmpty())
    }
    var abvValidationError by rememberSaveable(currentFilter) {
        mutableStateOf<String?>(null)
    }

    val selectedSort = CocktailSort.entries.firstOrNull { it.name == selectedSortName } ?: CocktailSort.NAME_ASC

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Sort & Filter",
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(
                    onClick = {
                        selectedSortName = CocktailSort.NAME_ASC.name
                        selectedTagIds = emptySet()
                        selectedGlassId = null
                        selectedMethodId = null
                        abvMinText = ""
                        abvMaxText = ""
                        abvValidationError = null
                    },
                ) {
                    Text("Clear Draft")
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(title = "Sort")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CocktailSort.entries.forEach { sortOption ->
                            FilterChip(
                                selected = selectedSort == sortOption,
                                onClick = { selectedSortName = sortOption.name },
                                label = { Text(sortOption.label) },
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader(title = "ABV Range")
                    val currentMin = abvMinText.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
                    val currentMax = abvMaxText.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ABV_PRESETS.forEach { preset ->
                            val isSelected = matchesNullableDouble(currentMin, preset.min) &&
                                matchesNullableDouble(currentMax, preset.max)
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (isSelected) {
                                        abvMinText = ""
                                        abvMaxText = ""
                                    } else {
                                        abvMinText = preset.min?.let(::formatNumericInput).orEmpty()
                                        abvMaxText = preset.max?.let(::formatNumericInput).orEmpty()
                                    }
                                    abvValidationError = null
                                },
                                label = { Text(preset.label) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = abvMinText,
                            onValueChange = {
                                abvMinText = it
                                abvValidationError = null
                            },
                            label = { Text("Min %") },
                            isError = abvValidationError != null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = abvMaxText,
                            onValueChange = {
                                abvMaxText = it
                                abvValidationError = null
                            },
                            label = { Text("Max %") },
                            isError = abvValidationError != null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    abvValidationError?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                if (catalog.glasses.isNotEmpty()) {
                    CatalogDropdownField(
                        label = "Glass",
                        selectedLabel = catalog.glasses.firstOrNull { it.id == selectedGlassId }?.name ?: "Any glass",
                        options = catalog.glasses.map { it.id to it.name },
                        anyLabel = "Any glass",
                        onSelect = { selectedGlassId = it },
                    )
                }

                if (catalog.methods.isNotEmpty()) {
                    CatalogDropdownField(
                        label = "Method",
                        selectedLabel = catalog.methods.firstOrNull { it.id == selectedMethodId }?.name ?: "Any method",
                        options = catalog.methods.map { it.id to it.name },
                        anyLabel = "Any method",
                        onSelect = { selectedMethodId = it },
                    )
                }

                if (catalog.tags.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionHeader(title = "Tags")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            catalog.tags.forEach { tag ->
                                val selected = tag.id in selectedTagIds
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        selectedTagIds = if (selected) {
                                            selectedTagIds - tag.id
                                        } else {
                                            selectedTagIds + tag.id
                                        }
                                    },
                                    label = { Text(tag.name) },
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Reset")
                }
                Button(
                    onClick = {
                        val validation = validateAbvRange(abvMinText, abvMaxText)
                        if (validation.error != null) {
                            abvValidationError = validation.error
                        } else {
                            onApply(
                                currentFilter.copy(
                                    sort = selectedSort,
                                    tagIds = selectedTagIds,
                                    glassId = selectedGlassId,
                                    methodId = selectedMethodId,
                                    abvMin = validation.min,
                                    abvMax = validation.max,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Apply")
                }
            }
        }
    }
}

private data class AbvValidationResult(
    val min: Double? = null,
    val max: Double? = null,
    val error: String? = null,
)

private fun validateAbvRange(minText: String, maxText: String): AbvValidationResult {
    val trimmedMin = minText.trim()
    val parsedMin = if (trimmedMin.isEmpty()) {
        null
    } else {
        trimmedMin.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return AbvValidationResult(error = "Enter a valid non-negative Min ABV.")
    }

    val trimmedMax = maxText.trim()
    val parsedMax = if (trimmedMax.isEmpty()) {
        null
    } else {
        trimmedMax.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            ?: return AbvValidationResult(error = "Enter a valid non-negative Max ABV.")
    }

    if (parsedMin != null && parsedMax != null && parsedMin > parsedMax) {
        return AbvValidationResult(error = "Min ABV cannot exceed Max ABV.")
    }

    return AbvValidationResult(min = parsedMin, max = parsedMax)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogDropdownField(
    label: String,
    selectedLabel: String,
    options: List<Pair<Int, String>>,
    anyLabel: String,
    onSelect: (Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(anyLabel) },
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
private fun ImportUrlPromptDialog(
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
                    text = "Paste a cocktail recipe web link to scrape its ingredients and instructions.",
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

private fun matchesNullableDouble(a: Double?, b: Double?): Boolean {
    if (a == null && b == null) return true
    if (a == null || b == null) return false
    return abs(a - b) < 1e-4
}

private fun previewListCocktails(): List<CocktailSummary> = listOf(
    CocktailSummary(
        id = 1,
        slug = "negroni",
        name = "Negroni",
        shortDescription = "Equal parts gin, Campari, and sweet vermouth.",
        abv = 24.0,
        glassName = "Rocks",
        methodName = "Stir",
        mainImageThumbUrl = null,
        isFavorite = true,
        inBarShelf = false,
        missingIngredientsCount = 1,
        missingIngredientNames = listOf("Sweet Vermouth"),
        tags = listOf(CocktailTag(30, "Classic"), CocktailTag(31, "Bitter")),
    ),
    CocktailSummary(
        id = 2,
        slug = "gimlet",
        name = "Gimlet",
        shortDescription = "Crisp gin and fresh lime.",
        abv = 21.0,
        glassName = "Coupe",
        methodName = "Shake",
        mainImageThumbUrl = null,
        isFavorite = false,
        inBarShelf = true,
        missingIngredientsCount = 0,
        missingIngredientNames = emptyList(),
        tags = listOf(CocktailTag(30, "Classic"), CocktailTag(32, "Citrus")),
    ),
)

@Preview
@Composable
private fun CocktailsListPopulatedDarkPreview() {
    BarFriendTheme(themeMode = AppThemeMode.DARK) {
        CocktailsListPreviewHost(cocktails = previewListCocktails())
    }
}

@Preview
@Composable
private fun CocktailsListPopulatedLightPreview() {
    BarFriendTheme(themeMode = AppThemeMode.LIGHT) {
        CocktailsListPreviewHost(cocktails = previewListCocktails())
    }
}

@Preview
@Composable
private fun CocktailsListEmptyDarkPreview() {
    BarFriendTheme(themeMode = AppThemeMode.DARK) {
        CocktailsListPreviewHost(cocktails = emptyList())
    }
}

@Preview
@Composable
private fun CocktailsListEmptyLightPreview() {
    BarFriendTheme(themeMode = AppThemeMode.LIGHT) {
        CocktailsListPreviewHost(cocktails = emptyList())
    }
}

@Composable
private fun CocktailsListPreviewHost(cocktails: List<CocktailSummary>) {
    CocktailsListContent(
        uiState = CocktailsListUiState(
            cocktails = cocktails,
            syncStatus = SyncStatus(lastSyncedEpochMillis = 1_700_000_000_000L),
            hasLoaded = true,
        ),
        onQueryChanged = {},
        onToggleCanMake = {},
        onToggleFavorites = {},
        onUpdateFilter = {},
        onClearAdvancedFilters = {},
        onClearAllFilters = {},
        onRefresh = {},
        onOpenCocktail = {},
        onToggleFavorite = {},
        onAddRecipe = {},
        onImportFromUrl = {},
    )
}
