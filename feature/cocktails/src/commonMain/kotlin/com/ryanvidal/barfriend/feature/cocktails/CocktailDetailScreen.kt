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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LocalBar
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.ryanvidal.barfriend.core.designsystem.EmptyStateCard
import com.ryanvidal.barfriend.core.designsystem.InventoryStatusChip
import com.ryanvidal.barfriend.core.designsystem.SectionHeader
import com.ryanvidal.barfriend.core.designsystem.StatusMissingTerracotta
import com.ryanvidal.barfriend.core.designsystem.StatusReadyGreen
import com.ryanvidal.barfriend.core.designsystem.StatusSubstituteAmber
import com.ryanvidal.barfriend.core.model.CocktailDetail
import com.ryanvidal.barfriend.core.model.CocktailImage
import com.ryanvidal.barfriend.core.model.CocktailIngredient
import com.ryanvidal.barfriend.core.model.UnitFormatter
import com.ryanvidal.barfriend.core.model.VolumeUnit
import com.ryanvidal.barfriend.core.platform.KeepScreenOnEffect

private val SCALE_PRESETS = listOf(
    0.5 to "0.5×",
    1.0 to "1×",
    2.0 to "2×",
    4.0 to "4×",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CocktailDetailScreen(
    viewModel: CocktailDetailViewModel,
    onBack: () -> Unit,
    onEditCocktail: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteConfirmation by rememberSaveable { mutableStateOf(false) }

    KeepScreenOnEffect(enabled = uiState.isMakeMode)

    LaunchedEffect(uiState.deleted) {
        if (uiState.deleted) {
            onBack()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        val message = uiState.errorMessage
        if (message != null && uiState.cocktail != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    val cocktail = uiState.cocktail

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = cocktail?.name ?: "Recipe",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (cocktail != null) {
                        IconButton(
                            onClick = viewModel::toggleFavorite,
                            enabled = !uiState.isDeleting,
                        ) {
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
                        IconButton(
                            onClick = { onEditCocktail(cocktail.id) },
                            enabled = !uiState.isDeleting,
                        ) {
                            Icon(Icons.Rounded.Edit, contentDescription = "Edit recipe")
                        }
                        IconButton(
                            onClick = { showDeleteConfirmation = true },
                            enabled = !uiState.isDeleting,
                        ) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete recipe")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            cocktail == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp),
                ) {
                    EmptyStateCard(
                        icon = Icons.Rounded.LocalBar,
                        title = "Recipe unavailable",
                        subtitle = uiState.errorMessage ?: "Connect to your Bar Assistant server to load this recipe.",
                        actionLabel = "Retry",
                        onActionClick = viewModel::refresh,
                    )
                }
            }

            else -> {
                val sortedIngredients = remember(cocktail.ingredients) {
                    cocktail.ingredients.sortedBy { it.sort }
                }
                val steps = remember(cocktail.instructions) {
                    parseInstructionSteps(cocktail.instructions)
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item(key = "make_mode_banner") {
                        MakeModeBanner(
                            isMakeMode = uiState.isMakeMode,
                            onToggle = viewModel::toggleMakeMode,
                        )
                    }

                    item(key = "hero_images") {
                        HeroImageCarousel(
                            cocktailName = cocktail.name,
                            images = cocktail.images,
                        )
                    }

                    item(key = "header_meta") {
                        CocktailHeaderSection(cocktail = cocktail)
                    }

                    if (!cocktail.description.isNullOrBlank() || !cocktail.shortDescription.isNullOrBlank()) {
                        item(key = "description") {
                            val descriptionText = cocktail.description?.takeIf { it.isNotBlank() }
                                ?: cocktail.shortDescription.orEmpty()
                            OutlinedCard(
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = descriptionText,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(14.dp),
                                )
                            }
                        }
                    }

                    if (!cocktail.garnish.isNullOrBlank() || !cocktail.source.isNullOrBlank() || cocktail.utensils.isNotEmpty()) {
                        item(key = "extras") {
                            CocktailExtrasCard(cocktail = cocktail)
                        }
                    }

                    item(key = "scaler_units") {
                        ScaleAndUnitControls(
                            scaleFactor = uiState.scaleFactor,
                            selectedUnit = uiState.selectedUnit,
                            onSelectScale = viewModel::setScaleFactor,
                            onSelectUnit = viewModel::setUnit,
                        )
                    }

                    item(key = "ingredients_header") {
                        SectionHeader(
                            title = "Ingredients (${sortedIngredients.size})",
                        )
                    }

                    if (sortedIngredients.isEmpty()) {
                        item(key = "no_ingredients") {
                            Text(
                                text = "No ingredients listed.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = sortedIngredients,
                            key = { _, item -> "ing_${item.id}_${item.ingredientId}_${item.sort}" },
                        ) { _, ingredient ->
                            DetailIngredientRow(
                                ingredient = ingredient,
                                selectedUnit = uiState.selectedUnit,
                                scaleFactor = uiState.scaleFactor,
                                isMakeMode = uiState.isMakeMode,
                            )
                        }
                    }

                    item(key = "instructions_header") {
                        Spacer(modifier = Modifier.height(4.dp))
                        SectionHeader(
                            title = "Instructions",
                            trailing = if (uiState.isMakeMode && steps.isNotEmpty()) {
                                {
                                    Text(
                                        text = "${uiState.checkedSteps.size}/${steps.size} done",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }

                    if (steps.isEmpty()) {
                        item(key = "raw_instructions") {
                            Text(
                                text = cocktail.instructions,
                                style = if (uiState.isMakeMode) {
                                    MaterialTheme.typography.bodyLarge.copy(fontSize = 19.sp, lineHeight = 28.sp)
                                } else {
                                    MaterialTheme.typography.bodyLarge
                                },
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = steps,
                            key = { index, _ -> "step_$index" },
                        ) { index, stepText ->
                            InstructionStepCard(
                                stepNumber = index + 1,
                                text = stepText,
                                isMakeMode = uiState.isMakeMode,
                                isChecked = index in uiState.checkedSteps,
                                onToggleChecked = { viewModel.toggleStepChecked(index) },
                            )
                        }
                    }

                    item(key = "bottom_spacer") {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation && cocktail != null) {
        AlertDialog(
            onDismissRequest = { if (!uiState.isDeleting) showDeleteConfirmation = false },
            title = { Text("Delete ${cocktail.name}?") },
            text = {
                Text("This permanently deletes the recipe from your Bar Assistant server.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        viewModel.deleteCocktail()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun MakeModeBanner(
    isMakeMode: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (isMakeMode) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (isMakeMode) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Visibility,
                contentDescription = null,
                tint = if (isMakeMode) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isMakeMode) "Make Mode · Screen stays awake" else "Make Mode",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isMakeMode) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Text(
                    text = if (isMakeMode) {
                        "Tap any step below to check it off"
                    } else {
                        "Keep screen awake and enlarge recipe steps"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isMakeMode) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Switch(
                checked = isMakeMode,
                onCheckedChange = { onToggle() },
            )
        }
    }
}

@Composable
private fun HeroImageCarousel(
    cocktailName: String,
    images: List<CocktailImage>,
) {
    if (images.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.LocalBar,
                contentDescription = cocktailName,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                modifier = Modifier.size(56.dp),
            )
        }
        return
    }

    val sortedImages = remember(images) { images.sortedBy { it.sort } }
    val pagerState = rememberPagerState(pageCount = { sortedImages.size })

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(248.dp)
                .clip(MaterialTheme.shapes.large),
        ) { page ->
            val image = sortedImages[page]
            var loadFailed by remember(image.url) { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.LocalBar,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    modifier = Modifier.size(48.dp),
                )
                if (!loadFailed) {
                    AsyncImage(
                        model = image.url,
                        contentDescription = cocktailName,
                        contentScale = ContentScale.Crop,
                        onError = { loadFailed = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                image.copyright?.takeIf { it.isNotBlank() }?.let { copyright ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp),
                    ) {
                        Text(
                            text = copyright,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }

        if (sortedImages.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(sortedImages.size) { index ->
                    val selected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (selected) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun CocktailHeaderSection(cocktail: CocktailDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = cocktail.name,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        InventoryStatusChip(
            inBarShelf = cocktail.inBarShelf,
            missingIngredientsCount = cocktail.missingIngredientsCount,
        )

        val metadataItems = buildList {
            cocktail.abv?.let { add(formatAbv(it)) }
            cocktail.glass?.name?.let { add(it) }
            cocktail.method?.name?.let { add(it) }
            cocktail.year?.let { add(it.toString()) }
            cocktail.author?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (metadataItems.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                metadataItems.forEach { label ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        if (cocktail.tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                cocktail.tags.forEach { tag ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            text = tag.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CocktailExtrasCard(cocktail: CocktailDetail) {
    OutlinedCard(
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            cocktail.garnish?.takeIf { it.isNotBlank() }?.let { garnish ->
                Text(
                    text = "Garnish: $garnish",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            if (cocktail.utensils.isNotEmpty()) {
                Text(
                    text = "Tools: ${cocktail.utensils.joinToString(", ") { it.name }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            cocktail.source?.takeIf { it.isNotBlank() }?.let { source ->
                Text(
                    text = "Source: $source",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ScaleAndUnitControls(
    scaleFactor: Double,
    selectedUnit: VolumeUnit,
    onSelectScale: (Double) -> Unit,
    onSelectUnit: (VolumeUnit) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Servings",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SCALE_PRESETS.forEach { (factor, label) ->
                        FilterChip(
                            selected = kotlin.math.abs(scaleFactor - factor) < 1e-4,
                            onClick = { onSelectScale(factor) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Volume Units",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    val units = VolumeUnit.entries
                    units.forEachIndexed { index, unit ->
                        SegmentedButton(
                            selected = selectedUnit == unit,
                            onClick = { onSelectUnit(unit) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = units.size,
                            ),
                            label = { Text(unit.label) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailIngredientRow(
    ingredient: CocktailIngredient,
    selectedUnit: VolumeUnit,
    scaleFactor: Double,
    isMakeMode: Boolean,
) {
    val formattedAmount = UnitFormatter.formatIngredientAmount(
        amount = ingredient.amount,
        amountMax = ingredient.amountMax,
        units = ingredient.units,
        targetUnit = selectedUnit,
        scaleFactor = scaleFactor,
    )

    val hasSubstitute = !ingredient.inBarShelf &&
        (ingredient.inBarShelfAsSubstitute || ingredient.inBarShelfAsVariant || ingredient.inBarShelfAsComplex)

    val statusColor = when {
        ingredient.inBarShelf -> StatusReadyGreen
        hasSubstitute -> StatusSubstituteAmber
        else -> StatusMissingTerracotta
    }
    val statusIcon = when {
        ingredient.inBarShelf -> Icons.Rounded.CheckCircle
        hasSubstitute -> Icons.Rounded.SwapHoriz
        else -> Icons.Rounded.Cancel
    }
    val statusLabel = when {
        ingredient.inBarShelf -> "In stock"
        ingredient.inBarShelfAsSubstitute -> "Substitute available"
        ingredient.inBarShelfAsVariant -> "Variant in bar"
        ingredient.inBarShelfAsComplex -> "Homemade ingredient"
        else -> "Missing"
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = statusIcon,
                contentDescription = statusLabel,
                tint = statusColor,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(20.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = ingredient.name,
                        style = if (isMakeMode) {
                            MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp)
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (ingredient.optional) {
                        Text(
                            text = "(optional)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                )
                ingredient.note?.takeIf { it.isNotBlank() }?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (ingredient.substitutes.isNotEmpty()) {
                    val subsText = ingredient.substitutes.joinToString(", ") { sub ->
                        if (sub.inBarShelf) "${sub.name} (in bar)" else sub.name
                    }
                    Text(
                        text = "Substitutes: $subsText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (formattedAmount.isNotEmpty()) {
                Text(
                    text = formattedAmount,
                    style = if (isMakeMode) {
                        MaterialTheme.typography.titleMedium.copy(
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun InstructionStepCard(
    stepNumber: Int,
    text: String,
    isMakeMode: Boolean,
    isChecked: Boolean,
    onToggleChecked: () -> Unit,
) {
    val containerColor = when {
        isMakeMode && isChecked -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f)
        isMakeMode -> MaterialTheme.colorScheme.surfaceContainer
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = containerColor,
        border = BorderStroke(
            width = 1.dp,
            color = if (isMakeMode && isChecked) {
                StatusReadyGreen.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(if (isMakeMode) Modifier.clickable(onClick = onToggleChecked) else Modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = if (isMakeMode) 16.dp else 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = when {
                    isMakeMode && isChecked -> StatusReadyGreen
                    isMakeMode -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.primaryContainer
                },
                modifier = Modifier.size(if (isMakeMode) 32.dp else 28.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isMakeMode && isChecked) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Completed step",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Text(
                            text = stepNumber.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isMakeMode) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                        )
                    }
                }
            }
            Text(
                text = text,
                style = if (isMakeMode) {
                    MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 19.sp,
                        lineHeight = 27.sp,
                    )
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                textDecoration = if (isMakeMode && isChecked) {
                    TextDecoration.LineThrough
                } else {
                    TextDecoration.None
                },
                color = if (isMakeMode && isChecked) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}
