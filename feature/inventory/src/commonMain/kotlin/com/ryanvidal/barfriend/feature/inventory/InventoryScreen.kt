package com.ryanvidal.barfriend.feature.inventory

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddShoppingCart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Liquor
import androidx.compose.material.icons.rounded.LocalBar
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import coil3.compose.AsyncImage
import com.ryanvidal.barfriend.core.data.SyncStatus
import com.ryanvidal.barfriend.core.designsystem.BarFriendTheme
import com.ryanvidal.barfriend.core.designsystem.EmptyStateCard
import com.ryanvidal.barfriend.core.designsystem.LocalBarFriendStatusColors
import com.ryanvidal.barfriend.core.designsystem.SectionHeader
import com.ryanvidal.barfriend.core.model.AppThemeMode
import com.ryanvidal.barfriend.core.model.IngredientSummary
import com.ryanvidal.barfriend.core.model.RecommendedIngredient
import com.ryanvidal.barfriend.core.model.ShoppingListItem
import com.ryanvidal.barfriend.core.model.UnlockedCocktail
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToLong

@Serializable
data object InventoryRoute : NavKey

@Composable
fun InventoryScreen(
    viewModel: InventoryViewModel,
    onOpenCocktail: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.snackbarMessage) {
        val message = uiState.snackbarMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeSnackbar()
        }
    }

    InventoryContent(
        uiState = uiState,
        onSelectTab = viewModel::selectTab,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onSelectStockFilter = viewModel::selectStockFilter,
        onSelectCategory = viewModel::selectCategory,
        onClearFilters = viewModel::clearFilters,
        onRefresh = viewModel::refreshAll,
        onRetryRecommendations = viewModel::loadRecommendations,
        onToggleBarShelf = viewModel::toggleBarShelf,
        onToggleShoppingList = viewModel::toggleShoppingList,
        onAddToShoppingList = viewModel::addToShoppingList,
        onUpdateQuantity = viewModel::updateQuantity,
        onRemoveFromShoppingList = viewModel::removeFromShoppingList,
        onMarkPurchased = viewModel::markPurchased,
        onMarkAllPurchased = viewModel::markAllPurchased,
        onClearShoppingList = viewModel::clearShoppingList,
        onOpenUnlockedCocktails = viewModel::openUnlockedCocktails,
        onDismissUnlockedCocktails = viewModel::dismissUnlockedCocktails,
        onOpenCocktail = onOpenCocktail,
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryContent(
    uiState: InventoryUiState,
    onSelectTab: (InventoryTab) -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onSelectStockFilter: (ShelfStockFilter) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onClearFilters: () -> Unit,
    onRefresh: () -> Unit,
    onRetryRecommendations: () -> Unit,
    onToggleBarShelf: (Int, Boolean) -> Unit,
    onToggleShoppingList: (Int) -> Unit,
    onAddToShoppingList: (Int) -> Unit,
    onUpdateQuantity: (Int, Int) -> Unit,
    onRemoveFromShoppingList: (Int) -> Unit,
    onMarkPurchased: (Int) -> Unit,
    onMarkAllPurchased: () -> Unit,
    onClearShoppingList: () -> Unit,
    onOpenUnlockedCocktails: (Int, String) -> Unit,
    onDismissUnlockedCocktails: () -> Unit,
    onOpenCocktail: (Int) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    var showAddShoppingSheet by rememberSaveable { mutableStateOf(false) }
    var showMoveAllDialog by rememberSaveable { mutableStateOf(false) }
    var showClearShoppingDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(title = { Text("Inventory") })
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            ) {
                InventoryTab.entries.forEachIndexed { index, tab ->
                    SegmentedButton(
                        selected = uiState.selectedTab == tab,
                        onClick = { onSelectTab(tab) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = InventoryTab.entries.size,
                        ),
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = tab.label,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (tab == InventoryTab.SHOPPING_LIST && uiState.shoppingList.isNotEmpty()) {
                                    Badge { Text(uiState.shoppingList.size.toString()) }
                                }
                            }
                        },
                    )
                }
            }

            uiState.syncStatus.errorMessage?.let { syncError ->
                SyncErrorBanner(
                    message = syncError,
                    onRetry = onRefresh,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }

            when (uiState.selectedTab) {
                InventoryTab.BAR_SHELF -> BarShelfTabContent(
                    uiState = uiState,
                    onSearchQueryChanged = onSearchQueryChanged,
                    onSelectStockFilter = onSelectStockFilter,
                    onSelectCategory = onSelectCategory,
                    onClearFilters = onClearFilters,
                    onRefresh = onRefresh,
                    onRetryRecommendations = onRetryRecommendations,
                    onToggleBarShelf = onToggleBarShelf,
                    onToggleShoppingList = onToggleShoppingList,
                    onOpenUnlockedCocktails = onOpenUnlockedCocktails,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                )

                InventoryTab.SHOPPING_LIST -> ShoppingListTabContent(
                    uiState = uiState,
                    onRefresh = onRefresh,
                    onOpenAddSheet = { showAddShoppingSheet = true },
                    onRequestMoveAll = { showMoveAllDialog = true },
                    onRequestClear = { showClearShoppingDialog = true },
                    onUpdateQuantity = onUpdateQuantity,
                    onRemoveFromShoppingList = onRemoveFromShoppingList,
                    onMarkPurchased = onMarkPurchased,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                )
            }
        }
    }

    if (showAddShoppingSheet) {
        AddShoppingItemSheet(
            allIngredients = uiState.allIngredients,
            shoppingList = uiState.shoppingList,
            inFlightShoppingIds = uiState.inFlightShoppingIds,
            inFlightPurchaseIds = uiState.inFlightPurchaseIds,
            isBulkUpdating = uiState.isBulkUpdating,
            lastSyncedEpochMillis = uiState.syncStatus.lastSyncedEpochMillis,
            onDismiss = { showAddShoppingSheet = false },
            onAddIngredient = onAddToShoppingList,
        )
    }

    uiState.unlockedSheet?.let { sheet ->
        UnlockedCocktailsSheet(
            sheet = sheet,
            inFlightShelfIds = uiState.inFlightShelfIds,
            inFlightShoppingIds = uiState.inFlightShoppingIds,
            inFlightPurchaseIds = uiState.inFlightPurchaseIds,
            isBulkUpdating = uiState.isBulkUpdating,
            onDismiss = onDismissUnlockedCocktails,
            onToggleBarShelf = onToggleBarShelf,
            onToggleShoppingList = onToggleShoppingList,
            onRetry = { onOpenUnlockedCocktails(sheet.ingredientId, sheet.ingredientName) },
            onOpenCocktail = { cocktailId ->
                onDismissUnlockedCocktails()
                onOpenCocktail(cocktailId)
            },
        )
    }

    if (showMoveAllDialog) {
        val count = uiState.shoppingList.size
        AlertDialog(
            onDismissRequest = { showMoveAllDialog = false },
            title = { Text("Move All to Bar Shelf?") },
            text = {
                Text(
                    "Mark all $count item${if (count == 1) "" else "s"} as purchased, add them to your Bar Shelf, and remove them from your shopping list?",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showMoveAllDialog = false
                        onMarkAllPurchased()
                    },
                ) {
                    Text("Move All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showMoveAllDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    if (showClearShoppingDialog) {
        val count = uiState.shoppingList.size
        AlertDialog(
            onDismissRequest = { showClearShoppingDialog = false },
            title = { Text("Clear Shopping List?") },
            text = {
                Text(
                    "Remove all $count item${if (count == 1) "" else "s"} from your shopping list?",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearShoppingDialog = false
                        onClearShoppingList()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearShoppingDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BarShelfTabContent(
    uiState: InventoryUiState,
    onSearchQueryChanged: (String) -> Unit,
    onSelectStockFilter: (ShelfStockFilter) -> Unit,
    onSelectCategory: (String?) -> Unit,
    onClearFilters: () -> Unit,
    onRefresh: () -> Unit,
    onRetryRecommendations: () -> Unit,
    onToggleBarShelf: (Int, Boolean) -> Unit,
    onToggleShoppingList: (Int) -> Unit,
    onOpenUnlockedCocktails: (Int, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val hasActiveFilters = uiState.searchQuery.isNotBlank() ||
        uiState.stockFilter != ShelfStockFilter.ALL ||
        uiState.selectedCategory != null

    Column(modifier = modifier) {
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "${uiState.inStockCount} of ${uiState.totalIngredientsCount} in stock",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasActiveFilters) {
                TextButton(
                    onClick = onClearFilters,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Clear Filters")
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onSearchQueryChanged,
            placeholder = { Text("Search ingredients or categories") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (uiState.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChanged("") }) {
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

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShelfStockFilter.entries.forEach { filter ->
                FilterChip(
                    selected = uiState.stockFilter == filter,
                    onClick = { onSelectStockFilter(filter) },
                    label = { Text(filter.label) },
                )
            }
        }

        if (uiState.categories.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = uiState.selectedCategory == null,
                    onClick = { onSelectCategory(null) },
                    label = { Text("All categories") },
                )
                uiState.categories.forEach { category ->
                    val selected = uiState.selectedCategory == category
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectCategory(if (selected) null else category) },
                        label = { Text(category) },
                    )
                }
            }
        }

        val showRecommendations = uiState.searchQuery.isBlank() &&
            uiState.selectedCategory == null &&
            uiState.stockFilter != ShelfStockFilter.IN_STOCK &&
            (uiState.recommendations.isNotEmpty() || uiState.isLoadingRecommendations)

        val showRecommendationError = uiState.searchQuery.isBlank() &&
            uiState.selectedCategory == null &&
            uiState.stockFilter != ShelfStockFilter.IN_STOCK &&
            uiState.allIngredients.isNotEmpty() &&
            uiState.recommendations.isEmpty() &&
            !uiState.isLoadingRecommendations &&
            uiState.recommendationsError != null

        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f),
        ) {
            when {
                uiState.allIngredients.isEmpty() &&
                    uiState.syncStatus.isSyncing &&
                    uiState.syncStatus.lastSyncedEpochMillis == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                uiState.filteredIngredients.isEmpty() && !showRecommendations -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    ) {
                        if (hasActiveFilters) {
                            EmptyStateCard(
                                icon = Icons.Rounded.SearchOff,
                                title = "No matching ingredients",
                                subtitle = "Try adjusting your search or clearing active filters.",
                                actionLabel = "Clear Filters",
                                onActionClick = onClearFilters,
                            )
                        } else {
                            EmptyStateCard(
                                icon = Icons.Rounded.Liquor,
                                title = "No ingredients synced",
                                subtitle = "Pull to refresh or sync your bar catalog to manage your shelf.",
                                actionLabel = "Refresh",
                                onActionClick = onRefresh,
                            )
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (showRecommendations) {
                            item(key = "recommendations_header") {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    SectionHeader(
                                        title = "Recommended Next Bottles",
                                    )
                                    Text(
                                        text = "What can this unlock? Tap a bottle to inspect recipes.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            item(key = "recommendations_row") {
                                if (uiState.isLoadingRecommendations && uiState.recommendations.isEmpty()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                        Text(
                                            text = "Finding bottles that unlock cocktails...",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                } else {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        items(
                                            items = uiState.recommendations,
                                            key = { it.id },
                                        ) { recommendation ->
                                            val isShelfBusy = recommendation.id in uiState.inFlightShelfIds ||
                                                recommendation.id in uiState.inFlightPurchaseIds ||
                                                uiState.isBulkUpdating
                                            val isShoppingBusy = recommendation.id in uiState.inFlightShoppingIds ||
                                                recommendation.id in uiState.inFlightPurchaseIds ||
                                                uiState.isBulkUpdating
                                            RecommendedBottleCard(
                                                recommendation = recommendation,
                                                isShelfBusy = isShelfBusy,
                                                isShoppingBusy = isShoppingBusy,
                                                onCardClick = {
                                                    onOpenUnlockedCocktails(recommendation.id, recommendation.name)
                                                },
                                                onAddToShelf = { onToggleBarShelf(recommendation.id, true) },
                                                onToggleShoppingList = { onToggleShoppingList(recommendation.id) },
                                            )
                                        }
                                    }
                                }
                            }
                        } else if (showRecommendationError) {
                            item(key = "recommendations_error") {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        Text(
                                            text = uiState.recommendationsError.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(
                                            onClick = onRetryRecommendations,
                                            modifier = Modifier.heightIn(min = 48.dp),
                                        ) {
                                            Text("Retry")
                                        }
                                    }
                                }
                            }
                        }

                        if (uiState.filteredIngredients.isEmpty()) {
                            item(key = "empty_filtered_with_recs") {
                                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    EmptyStateCard(
                                        icon = Icons.Rounded.SearchOff,
                                        title = "No matching ingredients",
                                        subtitle = "Try switching the stock filter or syncing your bar catalog.",
                                        actionLabel = if (hasActiveFilters) "Clear Filters" else null,
                                        onActionClick = if (hasActiveFilters) onClearFilters else null,
                                    )
                                }
                            }
                        } else {
                            items(
                                items = uiState.filteredIngredients,
                                key = { it.id },
                            ) { ingredient ->
                                val isShelfBusy = ingredient.id in uiState.inFlightShelfIds ||
                                    ingredient.id in uiState.inFlightPurchaseIds ||
                                    uiState.isBulkUpdating
                                val isShoppingBusy = ingredient.id in uiState.inFlightShoppingIds ||
                                    ingredient.id in uiState.inFlightPurchaseIds ||
                                    uiState.isBulkUpdating
                                IngredientShelfRow(
                                    ingredient = ingredient,
                                    lastSyncedEpochMillis = uiState.syncStatus.lastSyncedEpochMillis,
                                    isShelfBusy = isShelfBusy,
                                    isShoppingBusy = isShoppingBusy,
                                    onRowClick = {
                                        onOpenUnlockedCocktails(ingredient.id, ingredient.name)
                                    },
                                    onToggleShoppingList = { onToggleShoppingList(ingredient.id) },
                                    onToggleBarShelf = { inStock ->
                                        onToggleBarShelf(ingredient.id, inStock)
                                    },
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendedBottleCard(
    recommendation: RecommendedIngredient,
    isShelfBusy: Boolean,
    isShoppingBusy: Boolean,
    onCardClick: () -> Unit,
    onAddToShelf: () -> Unit,
    onToggleShoppingList: () -> Unit,
) {
    val readyColor = LocalBarFriendStatusColors.current.ready
    val unlockText = if (recommendation.potentialCocktails == 1) {
        "+1 cocktail"
    } else {
        "+${recommendation.potentialCocktails} cocktails"
    }

    ElevatedCard(
        onClick = onCardClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.width(210.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = recommendation.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = recommendation.categoryName?.takeIf { it.isNotBlank() } ?: "Ingredient",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Surface(
                shape = MaterialTheme.shapes.small,
                color = readyColor.copy(alpha = 0.16f),
                border = BorderStroke(1.dp, readyColor.copy(alpha = 0.4f)),
            ) {
                Text(
                    text = unlockText,
                    style = MaterialTheme.typography.labelMedium,
                    color = readyColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                FilledTonalButton(
                    onClick = onAddToShelf,
                    enabled = !isShelfBusy,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("+ Shelf", maxLines = 1)
                }

                IconButton(
                    onClick = onToggleShoppingList,
                    enabled = !isShoppingBusy,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (recommendation.inShoppingList) {
                            Icons.Rounded.ShoppingCart
                        } else {
                            Icons.Rounded.AddShoppingCart
                        },
                        contentDescription = if (recommendation.inShoppingList) {
                            "Remove ${recommendation.name} from shopping list"
                        } else {
                            "Add ${recommendation.name} to shopping list"
                        },
                        tint = if (recommendation.inShoppingList) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun IngredientShelfRow(
    ingredient: IngredientSummary,
    lastSyncedEpochMillis: Long?,
    isShelfBusy: Boolean,
    isShoppingBusy: Boolean,
    onRowClick: () -> Unit,
    onToggleShoppingList: () -> Unit,
    onToggleBarShelf: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metaText = remember(ingredient.categoryName, ingredient.strength) {
        listOfNotNull(
            ingredient.categoryName?.takeIf { it.isNotBlank() },
            ingredient.strength.takeIf { it > 0.0 }?.let(::formatStrengthAbv),
        ).joinToString(" · ")
    }

    OutlinedCard(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !ingredient.inBarShelf, onClick = onRowClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IngredientThumbnail(
                thumbUrl = ingredient.mainImageThumbUrl,
                lastSyncedEpochMillis = lastSyncedEpochMillis,
                size = 48.dp,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = ingredient.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (metaText.isNotEmpty()) {
                    Text(
                        text = metaText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            IconButton(
                onClick = onToggleShoppingList,
                enabled = !isShoppingBusy,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    imageVector = if (ingredient.inShoppingList) {
                        Icons.Rounded.ShoppingCart
                    } else {
                        Icons.Rounded.AddShoppingCart
                    },
                    contentDescription = if (ingredient.inShoppingList) {
                        "Remove ${ingredient.name} from shopping list"
                    } else {
                        "Add ${ingredient.name} to shopping list"
                    },
                    tint = if (ingredient.inShoppingList) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Switch(
                checked = ingredient.inBarShelf,
                onCheckedChange = onToggleBarShelf,
                enabled = !isShelfBusy,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShoppingListTabContent(
    uiState: InventoryUiState,
    onRefresh: () -> Unit,
    onOpenAddSheet: () -> Unit,
    onRequestMoveAll: () -> Unit,
    onRequestClear: () -> Unit,
    onUpdateQuantity: (Int, Int) -> Unit,
    onRemoveFromShoppingList: (Int) -> Unit,
    onMarkPurchased: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onOpenAddSheet,
                    enabled = !uiState.isBulkUpdating,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("+ Add Ingredient")
                }

                if (uiState.shoppingList.isNotEmpty()) {
                    FilledTonalButton(
                        onClick = onRequestMoveAll,
                        enabled = !uiState.isBulkUpdating,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Move All to Shelf")
                    }

                    TextButton(
                        onClick = onRequestClear,
                        enabled = !uiState.isBulkUpdating,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text("Clear")
                    }
                }
            }

            if (uiState.shoppingList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    EmptyStateCard(
                        icon = Icons.Rounded.ShoppingCart,
                        title = "Shopping list is empty",
                        subtitle = "Track missing ingredients and move purchased bottles straight to your shelf.",
                        actionLabel = "Add Ingredient",
                        onActionClick = onOpenAddSheet,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(
                        items = uiState.shoppingList,
                        key = { it.ingredientId },
                    ) { item ->
                        val isBusy = item.ingredientId in uiState.inFlightShoppingIds ||
                            item.ingredientId in uiState.inFlightPurchaseIds ||
                            uiState.isBulkUpdating
                        val isPurchasing = item.ingredientId in uiState.inFlightPurchaseIds
                        ShoppingListCard(
                            item = item,
                            lastSyncedEpochMillis = uiState.syncStatus.lastSyncedEpochMillis,
                            isBusy = isBusy,
                            isPurchasing = isPurchasing,
                            onDecrement = { onUpdateQuantity(item.ingredientId, item.quantity - 1) },
                            onIncrement = { onUpdateQuantity(item.ingredientId, item.quantity + 1) },
                            onMarkPurchased = { onMarkPurchased(item.ingredientId) },
                            onRemove = { onRemoveFromShoppingList(item.ingredientId) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShoppingListCard(
    item: ShoppingListItem,
    lastSyncedEpochMillis: Long?,
    isBusy: Boolean,
    isPurchasing: Boolean,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    onMarkPurchased: () -> Unit,
    onRemove: () -> Unit,
) {
    ElevatedCard(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                IngredientThumbnail(
                    thumbUrl = item.mainImageThumbUrl,
                    lastSyncedEpochMillis = lastSyncedEpochMillis,
                    size = 48.dp,
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = item.ingredientName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.categoryName?.takeIf { it.isNotBlank() }?.let { category ->
                        Text(
                            text = category,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                IconButton(
                    onClick = onRemove,
                    enabled = !isBusy,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Remove ${item.ingredientName}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onDecrement,
                            enabled = !isBusy,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Remove,
                                contentDescription = if (item.quantity <= 1) {
                                    "Remove ${item.ingredientName}"
                                } else {
                                    "Decrease quantity for ${item.ingredientName}"
                                },
                            )
                        }

                        Text(
                            text = item.quantity.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(min = 28.dp),
                        )

                        IconButton(
                            onClick = onIncrement,
                            enabled = !isBusy,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "Increase quantity for ${item.ingredientName}",
                            )
                        }
                    }
                }

                FilledTonalButton(
                    onClick = onMarkPurchased,
                    enabled = !isBusy,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    if (isPurchasing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Purchased", maxLines = 1)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddShoppingItemSheet(
    allIngredients: List<IngredientSummary>,
    shoppingList: List<ShoppingListItem>,
    inFlightShoppingIds: Set<Int>,
    inFlightPurchaseIds: Set<Int>,
    isBulkUpdating: Boolean,
    lastSyncedEpochMillis: Long?,
    onDismiss: () -> Unit,
    onAddIngredient: (Int) -> Unit,
) {
    val readyColor = LocalBarFriendStatusColors.current.ready
    var query by rememberSaveable { mutableStateOf("") }
    val shoppingIds = remember(shoppingList) {
        shoppingList.mapTo(HashSet(shoppingList.size)) { it.ingredientId }
    }
    val availableIngredients = remember(allIngredients, shoppingIds, query) {
        val needle = query.trim()
        allIngredients.filter { ingredient ->
            ingredient.id !in shoppingIds &&
                (
                    needle.isEmpty() ||
                        ingredient.name.contains(needle, ignoreCase = true) ||
                        (ingredient.categoryName?.contains(needle, ignoreCase = true) == true)
                    )
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Add to Shopping List",
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Done")
                }
            }

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
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            )

            if (availableIngredients.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            allIngredients.isEmpty() -> "Sync your bar catalog first to browse ingredients."
                            query.isBlank() -> "All catalog ingredients are already on your shopping list."
                            else -> "No ingredients match \"$query\"."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
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
                        items = availableIngredients,
                        key = { it.id },
                    ) { ingredient ->
                        val isBusy = ingredient.id in inFlightShoppingIds ||
                            ingredient.id in inFlightPurchaseIds ||
                            isBulkUpdating
                        ListItem(
                            headlineContent = { Text(ingredient.name) },
                            supportingContent = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    ingredient.categoryName?.takeIf { it.isNotBlank() }?.let { category ->
                                        Text(category)
                                    }
                                    if (ingredient.inBarShelf) {
                                        Surface(
                                            shape = MaterialTheme.shapes.small,
                                            color = readyColor.copy(alpha = 0.16f),
                                        ) {
                                            Text(
                                                text = "In Shelf",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = readyColor,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            )
                                        }
                                    }
                                }
                            },
                            leadingContent = {
                                IngredientThumbnail(
                                    thumbUrl = ingredient.mainImageThumbUrl,
                                    lastSyncedEpochMillis = lastSyncedEpochMillis,
                                    size = 40.dp,
                                )
                            },
                            trailingContent = {
                                if (ingredient.id in inFlightShoppingIds) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    IconButton(
                                        onClick = { onAddIngredient(ingredient.id) },
                                        enabled = !isBusy,
                                        modifier = Modifier.size(48.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.AddShoppingCart,
                                            contentDescription = "Add ${ingredient.name} to shopping list",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.clickable(enabled = !isBusy) {
                                onAddIngredient(ingredient.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnlockedCocktailsSheet(
    sheet: UnlockedCocktailsSheetState,
    inFlightShelfIds: Set<Int>,
    inFlightShoppingIds: Set<Int>,
    inFlightPurchaseIds: Set<Int>,
    isBulkUpdating: Boolean,
    onDismiss: () -> Unit,
    onToggleBarShelf: (Int, Boolean) -> Unit,
    onToggleShoppingList: (Int) -> Unit,
    onRetry: () -> Unit,
    onOpenCocktail: (Int) -> Unit,
) {
    val readyColor = LocalBarFriendStatusColors.current.ready
    val isShelfBusy = sheet.ingredientId in inFlightShelfIds ||
        sheet.ingredientId in inFlightPurchaseIds ||
        isBulkUpdating
    val isShoppingBusy = sheet.ingredientId in inFlightShoppingIds ||
        sheet.ingredientId in inFlightPurchaseIds ||
        isBulkUpdating

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp, max = 560.dp)
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = sheet.ingredientName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (sheet.inBarShelf) "In Bar Shelf" else "Not on Bar Shelf",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (sheet.inBarShelf) readyColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (sheet.inBarShelf) {
                    FilledTonalButton(
                        onClick = { onToggleBarShelf(sheet.ingredientId, false) },
                        enabled = !isShelfBusy,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("In Bar Shelf")
                    }
                } else {
                    Button(
                        onClick = { onToggleBarShelf(sheet.ingredientId, true) },
                        enabled = !isShelfBusy,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Liquor,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add to Bar Shelf")
                    }
                }

                if (sheet.inShoppingList) {
                    FilledTonalButton(
                        onClick = { onToggleShoppingList(sheet.ingredientId) },
                        enabled = !isShoppingBusy,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ShoppingCart,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("In Shopping List")
                    }
                } else {
                    OutlinedButton(
                        onClick = { onToggleShoppingList(sheet.ingredientId) },
                        enabled = !isShoppingBusy,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AddShoppingCart,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add to Shopping List")
                    }
                }
            }

            HorizontalDivider()

            when {
                sheet.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .padding(vertical = 28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = "Checking unlocked cocktails...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                sheet.errorMessage != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = sheet.errorMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        OutlinedButton(
                            onClick = onRetry,
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) {
                            Text("Retry")
                        }
                    }
                }

                sheet.cocktails.isEmpty() -> {
                    Text(
                        text = if (sheet.inBarShelf) {
                            "No cocktails exclusively depend on this bottle right now."
                        } else {
                            "No additional cocktails are unlocked by adding this ingredient alone."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }

                else -> {
                    SectionHeader(
                        title = "Cocktails Unlocked (${sheet.cocktails.size})",
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(
                            items = sheet.cocktails,
                            key = { it.id },
                        ) { cocktail ->
                            OutlinedCard(
                                onClick = { onOpenCocktail(cocktail.id) },
                                shape = MaterialTheme.shapes.medium,
                                colors = CardDefaults.outlinedCardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                                ),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.LocalBar,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Text(
                                        text = cocktail.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IngredientThumbnail(
    thumbUrl: String?,
    lastSyncedEpochMillis: Long?,
    size: Dp,
) {
    var imageFailed by remember(thumbUrl, lastSyncedEpochMillis) {
        mutableStateOf(false)
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Liquor,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
            modifier = Modifier.size(size * 0.5f),
        )
        if (!thumbUrl.isNullOrBlank() && !imageFailed) {
            AsyncImage(
                model = thumbUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { imageFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
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
            TextButton(
                onClick = onRetry,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Retry")
            }
        }
    }
}

private const val MAX_FORMATTABLE_STRENGTH_ABS = 9e17

private fun formatStrengthAbv(strength: Double): String {
    if (!strength.isFinite() || abs(strength) > MAX_FORMATTABLE_STRENGTH_ABS) return ""
    val tenths = (strength * 10.0).roundToLong()
    if (tenths <= 0L) return ""
    val whole = tenths / 10L
    val remainder = tenths % 10L
    return if (remainder == 0L) {
        "$whole% ABV"
    } else {
        "$whole.$remainder% ABV"
    }
}

private fun previewIngredients(): List<IngredientSummary> = listOf(
    IngredientSummary(
        id = 1,
        slug = "london-dry-gin",
        name = "London Dry Gin",
        strength = 40.0,
        categoryName = "Gin",
        mainImageThumbUrl = null,
        inBarShelf = true,
        inShoppingList = false,
    ),
    IngredientSummary(
        id = 2,
        slug = "campari",
        name = "Campari",
        strength = 25.0,
        categoryName = "Amaro",
        mainImageThumbUrl = null,
        inBarShelf = true,
        inShoppingList = false,
    ),
    IngredientSummary(
        id = 3,
        slug = "sweet-vermouth",
        name = "Sweet Vermouth",
        strength = 16.5,
        categoryName = "Vermouth",
        mainImageThumbUrl = null,
        inBarShelf = false,
        inShoppingList = true,
    ),
    IngredientSummary(
        id = 4,
        slug = "green-chartreuse",
        name = "Green Chartreuse",
        strength = 55.0,
        categoryName = "Liqueur",
        mainImageThumbUrl = null,
        inBarShelf = false,
        inShoppingList = false,
    ),
)

private fun previewRecommendations(): List<RecommendedIngredient> = listOf(
    RecommendedIngredient(
        id = 3,
        slug = "sweet-vermouth",
        name = "Sweet Vermouth",
        potentialCocktails = 4,
        categoryName = "Vermouth",
        inShoppingList = true,
    ),
    RecommendedIngredient(
        id = 4,
        slug = "green-chartreuse",
        name = "Green Chartreuse",
        potentialCocktails = 3,
        categoryName = "Liqueur",
        inShoppingList = false,
    ),
)

private fun previewShoppingList(): List<ShoppingListItem> = listOf(
    ShoppingListItem(
        ingredientId = 3,
        ingredientSlug = "sweet-vermouth",
        ingredientName = "Sweet Vermouth",
        categoryName = "Vermouth",
        quantity = 1,
        mainImageThumbUrl = null,
    ),
    ShoppingListItem(
        ingredientId = 5,
        ingredientSlug = "lime-juice",
        ingredientName = "Fresh Lime Juice",
        categoryName = "Citrus",
        quantity = 2,
        mainImageThumbUrl = null,
    ),
)

@Composable
private fun InventoryPreviewHost(uiState: InventoryUiState) {
    InventoryContent(
        uiState = uiState,
        onSelectTab = {},
        onSearchQueryChanged = {},
        onSelectStockFilter = {},
        onSelectCategory = {},
        onClearFilters = {},
        onRefresh = {},
        onRetryRecommendations = {},
        onToggleBarShelf = { _, _ -> },
        onToggleShoppingList = {},
        onAddToShoppingList = {},
        onUpdateQuantity = { _, _ -> },
        onRemoveFromShoppingList = {},
        onMarkPurchased = {},
        onMarkAllPurchased = {},
        onClearShoppingList = {},
        onOpenUnlockedCocktails = { _, _ -> },
        onDismissUnlockedCocktails = {},
        onOpenCocktail = {},
    )
}

@Preview
@Composable
private fun BarShelfPopulatedDarkPreview() {
    val items = previewIngredients()
    BarFriendTheme(themeMode = AppThemeMode.DARK) {
        InventoryPreviewHost(
            uiState = InventoryUiState(
                selectedTab = InventoryTab.BAR_SHELF,
                allIngredients = items,
                filteredIngredients = items,
                categories = listOf("Amaro", "Gin", "Liqueur", "Vermouth"),
                inStockCount = 2,
                totalIngredientsCount = 4,
                shoppingList = previewShoppingList(),
                recommendations = previewRecommendations(),
                syncStatus = SyncStatus(lastSyncedEpochMillis = 1_700_000_000_000L),
            ),
        )
    }
}

@Preview
@Composable
private fun BarShelfPopulatedLightPreview() {
    val items = previewIngredients()
    BarFriendTheme(themeMode = AppThemeMode.LIGHT) {
        InventoryPreviewHost(
            uiState = InventoryUiState(
                selectedTab = InventoryTab.BAR_SHELF,
                allIngredients = items,
                filteredIngredients = items,
                categories = listOf("Amaro", "Gin", "Liqueur", "Vermouth"),
                inStockCount = 2,
                totalIngredientsCount = 4,
                shoppingList = previewShoppingList(),
                recommendations = previewRecommendations(),
                syncStatus = SyncStatus(lastSyncedEpochMillis = 1_700_000_000_000L),
            ),
        )
    }
}

@Preview
@Composable
private fun ShoppingListPopulatedDarkPreview() {
    BarFriendTheme(themeMode = AppThemeMode.DARK) {
        InventoryPreviewHost(
            uiState = InventoryUiState(
                selectedTab = InventoryTab.SHOPPING_LIST,
                allIngredients = previewIngredients(),
                shoppingList = previewShoppingList(),
                syncStatus = SyncStatus(lastSyncedEpochMillis = 1_700_000_000_000L),
            ),
        )
    }
}

@Preview
@Composable
private fun ShoppingListPopulatedLightPreview() {
    BarFriendTheme(themeMode = AppThemeMode.LIGHT) {
        InventoryPreviewHost(
            uiState = InventoryUiState(
                selectedTab = InventoryTab.SHOPPING_LIST,
                allIngredients = previewIngredients(),
                shoppingList = previewShoppingList(),
                syncStatus = SyncStatus(lastSyncedEpochMillis = 1_700_000_000_000L),
            ),
        )
    }
}

@Preview
@Composable
private fun ShoppingListEmptyDarkPreview() {
    BarFriendTheme(themeMode = AppThemeMode.DARK) {
        InventoryPreviewHost(
            uiState = InventoryUiState(
                selectedTab = InventoryTab.SHOPPING_LIST,
                allIngredients = previewIngredients(),
                shoppingList = emptyList(),
                syncStatus = SyncStatus(lastSyncedEpochMillis = 1_700_000_000_000L),
            ),
        )
    }
}

@Preview
@Composable
private fun ShoppingListEmptyLightPreview() {
    BarFriendTheme(themeMode = AppThemeMode.LIGHT) {
        InventoryPreviewHost(
            uiState = InventoryUiState(
                selectedTab = InventoryTab.SHOPPING_LIST,
                allIngredients = previewIngredients(),
                shoppingList = emptyList(),
                syncStatus = SyncStatus(lastSyncedEpochMillis = 1_700_000_000_000L),
            ),
        )
    }
}
