package com.ryanvidal.barfriend.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.LocalBar
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ryanvidal.barfriend.core.model.AppThemeMode

@Composable
fun InventoryStatusChip(
    inBarShelf: Boolean,
    missingIngredientsCount: Int,
    modifier: Modifier = Modifier,
) {
    val statusColors = LocalBarFriendStatusColors.current
    val isReady = inBarShelf || missingIngredientsCount == 0
    val contentColor = if (isReady) statusColors.ready else statusColors.missing
    val containerColor = contentColor.copy(alpha = 0.16f)
    val label = if (isReady) {
        "Ready to make"
    } else if (missingIngredientsCount == 1) {
        "Missing 1"
    } else {
        "Missing $missingIngredientsCount"
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        border = BorderStroke(1.dp, contentColor.copy(alpha = 0.4f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = if (isReady) Icons.Rounded.CheckCircle else Icons.Rounded.ShoppingBag,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
            )
        }
    }
}

@Composable
fun EmptyStateCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    OutlinedCard(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .padding(14.dp)
                        .size(28.dp),
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onActionClick != null) {
                Spacer(modifier = Modifier.height(20.dp))
                Button(onClick = onActionClick) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        trailing?.invoke()
    }
}

@Composable
private fun DesignSystemComponentsPreviewContent() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader(
                title = "Bar Shelf Status",
                trailing = {
                    TextButton(onClick = {}) {
                        Text("See All")
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InventoryStatusChip(inBarShelf = true, missingIngredientsCount = 0)
                InventoryStatusChip(inBarShelf = false, missingIngredientsCount = 1)
                InventoryStatusChip(inBarShelf = false, missingIngredientsCount = 3)
            }
            EmptyStateCard(
                icon = Icons.Rounded.LocalBar,
                title = "No cocktails found",
                subtitle = "Try clearing a filter or syncing your bar inventory.",
                actionLabel = "Reset Filters",
                onActionClick = {},
            )
        }
    }
}

@Preview
@Composable
private fun DesignSystemComponentsDarkPreview() {
    BarFriendTheme(themeMode = AppThemeMode.DARK) {
        DesignSystemComponentsPreviewContent()
    }
}

@Preview
@Composable
private fun DesignSystemComponentsLightPreview() {
    BarFriendTheme(themeMode = AppThemeMode.LIGHT) {
        DesignSystemComponentsPreviewContent()
    }
}
