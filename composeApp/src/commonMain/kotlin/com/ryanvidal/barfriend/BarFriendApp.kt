package com.ryanvidal.barfriend

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Liquor
import androidx.compose.material.icons.rounded.LocalBar
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import com.ryanvidal.barfriend.core.data.SessionState
import com.ryanvidal.barfriend.core.data.UserSettings
import com.ryanvidal.barfriend.core.designsystem.BarFriendTheme
import com.ryanvidal.barfriend.di.AppGraph
import com.ryanvidal.barfriend.feature.auth.AuthRoute
import com.ryanvidal.barfriend.feature.auth.AuthScreen
import com.ryanvidal.barfriend.feature.auth.AuthViewModel
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
import okio.Path.Companion.toPath

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
fun BarFriendApp(appGraph: AppGraph) {
    setSingletonImageLoaderFactory { coilContext ->
        ImageLoader.Builder(coilContext)
            .components {
                add(KtorNetworkFetcherFactory(httpClient = { appGraph.imageHttpClient.client }))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(coilContext, percent = 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(appGraph.platformPaths.cachePath("coil3_image_cache").toPath())
                    .maxSizeBytes(100L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    val settings by appGraph.appPreferences.settings.collectAsStateWithLifecycle(UserSettings())
    val sessionState by appGraph.sessionRepository.sessionState.collectAsStateWithLifecycle()

    BarFriendTheme(themeMode = settings.themeMode) {
        when (val session = sessionState) {
            SessionState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            is SessionState.Unauthenticated -> {
                val authViewModel = viewModel(key = "auth") {
                    AuthViewModel(appGraph.authRepository)
                }
                AuthScreen(
                    viewModel = authViewModel,
                    initialServerUrl = session.lastServerUrl,
                )
            }
            is SessionState.Authenticated -> {
                LaunchedEffect(session.activeBarId) {
                    appGraph.cocktailRepository.syncAll()
                }
                MainShell(appGraph = appGraph)
            }
        }
    }
}

@Composable
private fun MainShell(appGraph: AppGraph) {
    var activeTab by rememberSaveable { mutableStateOf(BarFriendTab.COCKTAILS) }

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

    val provider = remember(appGraph) {
        entryProvider<NavKey> {
            entry<CocktailsListRoute> {
                val listViewModel = viewModel(key = "cocktails_list") {
                    CocktailsListViewModel(appGraph.cocktailRepository)
                }
                CocktailsListScreen(
                    viewModel = listViewModel,
                    onOpenCocktail = { id -> cocktailsStack.add(CocktailDetailRoute(id)) },
                    onAddRecipe = { cocktailsStack.add(CocktailEditorRoute()) },
                    onImportFromUrl = { url ->
                        cocktailsStack.add(CocktailEditorRoute(initialImportUrl = url))
                    },
                )
            }
            entry<CocktailDetailRoute> { route ->
                val detailViewModel = viewModel(key = "cocktail_detail_${route.cocktailId}") {
                    CocktailDetailViewModel(
                        cocktailId = route.cocktailId,
                        cocktailRepository = appGraph.cocktailRepository,
                        appPreferences = appGraph.appPreferences,
                    )
                }
                CocktailDetailScreen(
                    viewModel = detailViewModel,
                    onBack = { cocktailsStack.removeLastOrNull() },
                    onEditCocktail = { id ->
                        cocktailsStack.add(CocktailEditorRoute(cocktailId = id))
                    },
                )
            }
            entry<CocktailEditorRoute> { route ->
                val editorViewModel = viewModel(
                    key = "cocktail_editor_${route.cocktailId}_${route.initialImportUrl}",
                ) {
                    CocktailEditorViewModel(
                        cocktailId = route.cocktailId,
                        initialImportUrl = route.initialImportUrl,
                        cocktailRepository = appGraph.cocktailRepository,
                    )
                }
                CocktailEditorScreen(
                    viewModel = editorViewModel,
                    onBack = { cocktailsStack.removeLastOrNull() },
                    onSaved = { savedId ->
                        cocktailsStack.removeLastOrNull()
                        if (route.cocktailId == null && cocktailsStack.lastOrNull() !is CocktailDetailRoute) {
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

    val decoratedTabs: Map<BarFriendTab, List<NavEntry<NavKey>>> = backStacks.mapValues { (_, stack) ->
        rememberDecoratedNavEntries(
            backStack = stack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = provider,
        )
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
                .padding(innerPadding),
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
