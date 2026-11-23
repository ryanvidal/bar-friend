package com.ryanvidal.barfriend.feature.inventory

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Liquor
import androidx.compose.material.icons.rounded.ShoppingCart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.ryanvidal.barfriend.core.designsystem.EmptyStateCard
import kotlinx.serialization.Serializable

@Serializable
data object InventoryRoute : NavKey

private enum class InventoryTab(val label: String) {
    BAR_SHELF("Bar Shelf"),
    SHOPPING_LIST("Shopping List"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    modifier: Modifier = Modifier,
) {
    var selectedTab by rememberSaveable { mutableStateOf(InventoryTab.BAR_SHELF) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text("Inventory") })
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                InventoryTab.entries.forEachIndexed { index, tab ->
                    SegmentedButton(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = InventoryTab.entries.size,
                        ),
                        label = { Text(tab.label) },
                    )
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            when (selectedTab) {
                InventoryTab.BAR_SHELF -> EmptyStateCard(
                    icon = Icons.Rounded.Liquor,
                    title = "Bar Shelf",
                    subtitle = "Toggle bottles in stock and see which cocktails each bottle unlocks.",
                )
                InventoryTab.SHOPPING_LIST -> EmptyStateCard(
                    icon = Icons.Rounded.ShoppingCart,
                    title = "Shopping List",
                    subtitle = "Track missing ingredients and move purchased bottles straight to your shelf.",
                )
            }
        }
    }
}
