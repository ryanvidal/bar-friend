package com.ryanvidal.barfriend.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Liquor
import androidx.compose.material.icons.rounded.LocalBar
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.savedstate.serialization.SavedStateConfiguration
import androidx.window.core.layout.WindowSizeClass
import com.ryanvidal.barfriend.di.AppGraph
import com.ryanvidal.barfriend.feature.auth.AuthRoute
import com.ryanvidal.barfriend.feature.cocktails.CocktailDetailRoute
import com.ryanvidal.barfriend.feature.cocktails.CocktailDetailScreen
import com.ryanvidal.barfriend.feature.cocktails.CocktailDetailViewModel
import com.ryanvidal.barfriend.feature.cocktails.CocktailEditorRoute
import com.ryanvidal.barfriend.feature.cocktails.CocktailEditorScreen
import com.ryanvidal.barfriend.feature.cocktails.CocktailEditorViewModel
import com.ryanvidal.barfriend.feature.cocktails.CocktailsListRoute
import com.ryanvidal.barfriend.feature.cocktails.CocktailsListScreen
import com.ryanvidal.barfriend.feature.cocktails.CocktailsListViewModel
import com.ryanvidal.barfriend.feature.inventory.InventoryRoute
import com.ryanvidal.barfriend.feature.inventory.InventoryScreen
import com.ryanvidal.barfriend.feature.settings.SettingsRoute
import com.ryanvidal.barfriend.feature.settings.SettingsScreen
import com.ryanvidal.barfriend.feature.settings.SettingsViewModel
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

private enum class BarFriendTab(
    val label: String,
    val icon: ImageVector,
    val startRoute: NavKey,
) {
    COCKTAILS("Cocktails", Icons.Rounded.LocalBar, CocktailsListRoute),
    INVENTORY("Inventory", Icons.Rounded.Liquor, InventoryRoute),
    BAR("Bar", Icons.Rounded.Tune, SettingsRoute),
}

private val BarFriendNavSavedStateConfig = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(AuthRoute::class, AuthRoute.serializer())
            subclass(CocktailsListRoute::class, CocktailsListRoute.serializer())
            subclass(CocktailDetailRoute::class, CocktailDetailRoute.serializer())
            subclass(CocktailEditorRoute::class, CocktailEditorRoute.serializer())
            subclass(InventoryRoute::class, InventoryRoute.serializer())
            subclass(SettingsRoute::class, SettingsRoute.serializer())
        }
    }
}

@Composable
internal fun MainShell(
    appGraph: AppGraph,
    activeBarId: Int?,
) {
    var activeTab by rememberSaveable { mutableStateOf(BarFriendTab.COCKTAILS) }
    var nextEditorSessionId by rememberSaveable { mutableLongStateOf(1L) }

    val cocktailsStack = rememberNavBackStack(BarFriendNavSavedStateConfig, CocktailsListRoute)
    val inventoryStack = rememberNavBackStack(BarFriendNavSavedStateConfig, InventoryRoute)
    val settingsStack = rememberNavBackStack(BarFriendNavSavedStateConfig, SettingsRoute)

    val backStacks: Map<BarFriendTab, NavBackStack<NavKey>> = remember(
        cocktailsStack,
        inventoryStack,
        settingsStack,
    ) {
        mapOf(
            BarFriendTab.COCKTAILS to cocktailsStack,
            BarFriendTab.INVENTORY to inventoryStack,
            BarFriendTab.BAR to settingsStack,
        )
    }

    val provider = remember(appGraph, cocktailsStack, activeBarId) {
        entryProvider<NavKey> {
            entry<CocktailsListRoute> {
                val listViewModel = viewModel(key = "cocktails_list_${activeBarId ?: "none"}") {
                    CocktailsListViewModel(appGraph.cocktailRepository)
                }
                CocktailsListScreen(
                    viewModel = listViewModel,
                    onOpenCocktail = { id ->
                        if (cocktailsStack.lastOrNull() == CocktailsListRoute) {
                            cocktailsStack.add(CocktailDetailRoute(id))
                        }
                    },
                    onAddRecipe = {
                        if (cocktailsStack.lastOrNull() == CocktailsListRoute) {
                            val sessionId = nextEditorSessionId++
                            cocktailsStack.add(CocktailEditorRoute(sessionId = sessionId))
                        }
                    },
                    onImportFromUrl = { url ->
                        if (cocktailsStack.lastOrNull() == CocktailsListRoute) {
                            val sessionId = nextEditorSessionId++
                            cocktailsStack.add(
                                CocktailEditorRoute(
                                    initialImportUrl = url,
                                    sessionId = sessionId,
                                ),
                            )
                        }
                    },
                )
            }
            entry<CocktailDetailRoute> { route ->
                val detailViewModel = viewModel(
                    key = "cocktail_detail_${activeBarId ?: "none"}_${route.cocktailId}",
                ) {
                    CocktailDetailViewModel(
                        cocktailId = route.cocktailId,
                        cocktailRepository = appGraph.cocktailRepository,
                        appPreferences = appGraph.appPreferences,
                    )
                }
                CocktailDetailScreen(
                    viewModel = detailViewModel,
                    onBack = { popExpectedRoute(cocktailsStack, route) },
                    onEditCocktail = { id ->
                        if (cocktailsStack.lastOrNull() == route) {
                            val sessionId = nextEditorSessionId++
                            cocktailsStack.add(
                                CocktailEditorRoute(
                                    cocktailId = id,
                                    sessionId = sessionId,
                                ),
                            )
                        }
                    },
                )
            }
            entry<CocktailEditorRoute> { route ->
                val editorViewModel = viewModel(
                    key = "cocktail_editor_${activeBarId ?: "none"}_${route.sessionId}_${route.cocktailId}_${route.initialImportUrl}",
                ) {
                    CocktailEditorViewModel(
                        cocktailId = route.cocktailId,
                        initialImportUrl = route.initialImportUrl,
                        cocktailRepository = appGraph.cocktailRepository,
                    )
                }
                CocktailEditorScreen(
                    viewModel = editorViewModel,
                    onBack = { popExpectedRoute(cocktailsStack, route) },
                    onSaved = { savedId ->
                        popExpectedRoute(cocktailsStack, route)
                        if (route.cocktailId == null && cocktailsStack.lastOrNull() == CocktailsListRoute) {
                            cocktailsStack.add(CocktailDetailRoute(savedId))
                        }
                    },
                )
            }
            entry<InventoryRoute> {
                InventoryScreen()
            }
            entry<SettingsRoute> {
                val settingsViewModel = viewModel(key = "settings") {
                    SettingsViewModel(
                        appPreferences = appGraph.appPreferences,
                        sessionRepository = appGraph.sessionRepository,
                        authRepository = appGraph.authRepository,
                        cocktailRepository = appGraph.cocktailRepository,
                    )
                }
                SettingsScreen(viewModel = settingsViewModel)
            }
        }
    }

    val decoratedTabs: Map<BarFriendTab, List<NavEntry<NavKey>>> = backStacks.mapValues { (tab, stack) ->
        key(tab) {
            val viewModelStoreProvider = rememberViewModelStoreProvider(key = "tab_${tab.name}")
            rememberDecoratedNavEntries(
                backStack = stack,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(viewModelStoreProvider),
                ),
                entryProvider = provider,
            )
        }
    }

    val displayedEntries = remember(activeTab, decoratedTabs) {
        if (activeTab == BarFriendTab.COCKTAILS) {
            decoratedTabs[BarFriendTab.COCKTAILS].orEmpty()
        } else {
            decoratedTabs[BarFriendTab.COCKTAILS].orEmpty() + decoratedTabs[activeTab].orEmpty()
        }
    }

    val currentStack = backStacks.getValue(activeTab)
    val showTopLevelChrome = currentStack.size <= 1
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val useNavigationRail = windowSizeClass.isWidthAtLeastBreakpoint(
        WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
    )

    val onBack: () -> Unit = {
        val stack = backStacks.getValue(activeTab)
        if (stack.size > 1) {
            stack.removeLastOrNull()
        } else if (activeTab != BarFriendTab.COCKTAILS) {
            activeTab = BarFriendTab.COCKTAILS
        }
    }

    val onSelectTab: (BarFriendTab) -> Unit = { tab ->
        if (tab == activeTab) {
            val stack = backStacks.getValue(tab)
            while (stack.size > 1) {
                stack.removeLastOrNull()
            }
        } else {
            activeTab = tab
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            if (showTopLevelChrome && !useNavigationRail) {
                NavigationBar {
                    BarFriendTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = activeTab == tab,
                            onClick = { onSelectTab(tab) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding),
        ) {
            if (showTopLevelChrome && useNavigationRail) {
                NavigationRail {
                    BarFriendTab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = activeTab == tab,
                            onClick = { onSelectTab(tab) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
            NavDisplay(
                entries = displayedEntries,
                onBack = onBack,
                modifier = Modifier.weight(1f).fillMaxSize(),
            )
        }
    }
}

private fun popExpectedRoute(
    stack: NavBackStack<NavKey>,
    expected: NavKey,
) {
    if (stack.lastOrNull() == expected && stack.size > 1) {
        stack.removeLastOrNull()
    }
}
