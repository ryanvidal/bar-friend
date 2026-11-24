package com.ryanvidal.barfriend.core.data

import com.ryanvidal.barfriend.core.database.dao.CatalogDao
import com.ryanvidal.barfriend.core.database.dao.CocktailDao
import com.ryanvidal.barfriend.core.database.dao.CocktailWithIngredients
import com.ryanvidal.barfriend.core.database.dao.InventoryDao
import com.ryanvidal.barfriend.core.database.dao.SyncDao
import com.ryanvidal.barfriend.core.database.entity.IngredientEntity
import com.ryanvidal.barfriend.core.database.entity.ShoppingListEntity
import com.ryanvidal.barfriend.core.model.BarCatalog
import com.ryanvidal.barfriend.core.model.CocktailDetail
import com.ryanvidal.barfriend.core.model.CocktailFilter
import com.ryanvidal.barfriend.core.model.CocktailMethod
import com.ryanvidal.barfriend.core.model.CocktailSummary
import com.ryanvidal.barfriend.core.model.Glass
import com.ryanvidal.barfriend.core.model.IngredientSummary
import com.ryanvidal.barfriend.core.model.ShoppingListItem
import com.ryanvidal.barfriend.core.network.dto.CocktailDto
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class BarShelfMutationPlan(
    val previousShelf: Set<Int>,
    val ids: List<Int>,
    val inStock: Boolean,
    val recalculation: CocktailAvailabilityRecalculation,
)

interface BarLocalDataSource {
    fun observeCocktails(barId: Int, filter: CocktailFilter): Flow<List<CocktailSummary>>
    fun observeCocktailDetail(barId: Int, cocktailId: Int): Flow<CocktailDetail?>
    fun observeCatalog(barId: Int): Flow<BarCatalog>
    fun observeBarShelfIngredients(barId: Int): Flow<List<IngredientSummary>>
    fun observeShoppingList(barId: Int): Flow<List<ShoppingListItem>>
    fun observeShoppingListIds(barId: Int): Flow<Set<Int>>

    suspend fun getCocktailWithIngredients(barId: Int, cocktailId: Int): CocktailWithIngredients?
    suspend fun getIngredientsForBar(barId: Int): List<IngredientEntity>
    suspend fun getShoppingListSnapshot(barId: Int): List<ShoppingListEntity>
    suspend fun getSyncTimestamp(barId: Int): Long?

    suspend fun replaceCocktail(
        dto: CocktailDto,
        barId: Int,
        serverUrl: String?,
        upsertTags: Boolean = false,
    ): CocktailDetail
    suspend fun updateFavorite(barId: Int, cocktailId: Int, isFavorite: Boolean)
    suspend fun deleteCocktail(barId: Int, cocktailId: Int)

    suspend fun prepareBarShelfMutation(
        barId: Int,
        ids: List<Int>,
        inStock: Boolean,
    ): BarShelfMutationPlan
    suspend fun commitBarShelfMutation(barId: Int, plan: BarShelfMutationPlan)
    suspend fun restoreBarShelfAndRecalculate(
        barId: Int,
        previousShelf: Set<Int>,
        toggledIngredientIds: Set<Int>,
    )

    suspend fun upsertShoppingListItems(items: List<ShoppingListEntity>)
    suspend fun deleteShoppingListItems(barId: Int, ingredientIds: List<Int>)
    suspend fun replaceShoppingList(barId: Int, items: List<ShoppingListEntity>)

    suspend fun replaceBarSnapshot(
        barId: Int,
        serverUrl: String,
        payload: BarSyncPayload,
        syncedAtEpochMillis: Long,
    )
}

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class RoomBarLocalDataSource(
    private val cocktailDao: CocktailDao,
    private val inventoryDao: InventoryDao,
    private val catalogDao: CatalogDao,
    private val syncDao: SyncDao,
) : BarLocalDataSource {

    private fun observeIngredientsWithStatus(barId: Int): Flow<List<IngredientSummary>> = combine(
        catalogDao.observeIngredients(barId),
        inventoryDao.observeBarShelfIngredientIds(barId),
        inventoryDao.observeShoppingList(barId),
    ) { ingredients, shelfIds, shoppingItems ->
        val shelfSet = shelfIds.toSet()
        val shoppingSet = shoppingItems.mapTo(HashSet(shoppingItems.size)) { it.ingredientId }
        ingredients.map { it.toSummary(shelfSet, shoppingSet) }
    }

    override fun observeCocktails(barId: Int, filter: CocktailFilter): Flow<List<CocktailSummary>> =
        cocktailDao.observeCocktailsWithIngredients(barId)
            .map { items -> filterAndSortCocktails(items, filter) }

    override fun observeCocktailDetail(barId: Int, cocktailId: Int): Flow<CocktailDetail?> =
        combine(
            cocktailDao.observeCocktailWithIngredients(barId, cocktailId),
            catalogDao.observeGlasses(barId),
            catalogDao.observeMethods(barId),
        ) { cocktail, glasses, methods ->
            if (cocktail == null) {
                null
            } else {
                val glass = cocktail.cocktail.glassId?.let { glassId ->
                    glasses.firstOrNull { it.id == glassId }?.toModel()
                }
                val method = cocktail.cocktail.methodId?.let { methodId ->
                    methods.firstOrNull { it.id == methodId }?.toModel()
                }
                cocktail.toCocktailDetail(glass = glass, method = method)
            }
        }

    override fun observeCatalog(barId: Int): Flow<BarCatalog> =
        combine(
            observeIngredientsWithStatus(barId),
            catalogDao.observeGlasses(barId),
            catalogDao.observeMethods(barId),
            catalogDao.observeTags(barId),
            catalogDao.observeUtensils(barId),
        ) { ingredients, glasses, methods, tags, utensils ->
            BarCatalog(
                ingredients = ingredients,
                glasses = glasses.map { it.toModel() },
                methods = methods.map { it.toModel() },
                tags = tags.map { it.toModel() },
                utensils = utensils.map { it.toModel() },
            )
        }

    override fun observeBarShelfIngredients(barId: Int): Flow<List<IngredientSummary>> =
        observeIngredientsWithStatus(barId)

    override fun observeShoppingList(barId: Int): Flow<List<ShoppingListItem>> =
        combine(
            inventoryDao.observeShoppingList(barId),
            catalogDao.observeIngredients(barId),
        ) { shoppingItems, ingredients ->
            val ingredientsById = ingredients.associateBy { it.id }
            shoppingItems
                .map { it.toModel(ingredientsById[it.ingredientId]) }
                .sortedWith(
                    compareBy<ShoppingListItem> { it.ingredientName.lowercase() }
                        .thenBy { it.ingredientName }
                        .thenBy { it.ingredientId },
                )
        }

    override fun observeShoppingListIds(barId: Int): Flow<Set<Int>> =
        inventoryDao.observeShoppingList(barId)
            .map { items -> items.mapTo(HashSet(items.size)) { it.ingredientId } }

    override suspend fun getCocktailWithIngredients(
        barId: Int,
        cocktailId: Int,
    ): CocktailWithIngredients? =
        cocktailDao.observeCocktailWithIngredients(barId, cocktailId).first()

    override suspend fun getIngredientsForBar(barId: Int): List<IngredientEntity> =
        catalogDao.getIngredientsForBar(barId)

    override suspend fun getShoppingListSnapshot(barId: Int): List<ShoppingListEntity> =
        inventoryDao.getShoppingListSnapshot(barId)

    override suspend fun getSyncTimestamp(barId: Int): Long? =
        catalogDao.getSyncTimestamp(syncKey(barId))

    override suspend fun replaceCocktail(
        dto: CocktailDto,
        barId: Int,
        serverUrl: String?,
        upsertTags: Boolean,
    ): CocktailDetail {
        if (upsertTags && dto.tags.isNotEmpty()) {
            catalogDao.upsertTags(dto.tags.map { it.toEntity(barId) }.distinctBy { it.id })
        }
        val (entity, ingredients) = dto.toEntityAndIngredients(barId, serverUrl)
        cocktailDao.replaceCocktailWithIngredients(entity, ingredients)
        val glass = dto.glass?.let {
            Glass(
                id = it.id,
                name = it.name,
                volume = it.volume,
                volumeUnits = it.volumeUnits,
            )
        }
        val method = dto.method?.let {
            CocktailMethod(
                id = it.id,
                name = it.name,
                dilutionPercentage = it.dilutionPercentage,
            )
        }
        return CocktailWithIngredients(entity, ingredients).toCocktailDetail(glass = glass, method = method)
    }

    override suspend fun updateFavorite(barId: Int, cocktailId: Int, isFavorite: Boolean) {
        cocktailDao.updateFavorite(barId, cocktailId, isFavorite)
    }

    override suspend fun deleteCocktail(barId: Int, cocktailId: Int) {
        cocktailDao.deleteCocktailById(barId, cocktailId)
    }

    override suspend fun prepareBarShelfMutation(
        barId: Int,
        ids: List<Int>,
        inStock: Boolean,
    ): BarShelfMutationPlan {
        val previousShelf = inventoryDao.getBarShelfIngredientIds(barId).toSet()
        val toggledSet = ids.toSet()
        val nextShelf = if (inStock) previousShelf + toggledSet else previousShelf - toggledSet
        val catalogIngredients = catalogDao.getIngredientsForBar(barId)
        val cocktails = cocktailDao.getCocktailsWithIngredientsForBar(barId)
        val recalc = recalculateCocktailAvailability(
            items = cocktails,
            shelfIngredientIds = nextShelf,
            catalogIngredients = catalogIngredients,
            toggledIngredientIds = toggledSet,
        )
        return BarShelfMutationPlan(
            previousShelf = previousShelf,
            ids = ids,
            inStock = inStock,
            recalculation = recalc,
        )
    }

    override suspend fun commitBarShelfMutation(barId: Int, plan: BarShelfMutationPlan) {
        syncDao.applyBarShelfAndAvailability(
            barId = barId,
            addShelfIds = if (plan.inStock) plan.ids else emptyList(),
            removeShelfIds = if (plan.inStock) emptyList() else plan.ids,
            cocktailUpdates = plan.recalculation.cocktailUpdates,
            ingredientUpdates = plan.recalculation.ingredientUpdates,
        )
    }

    override suspend fun restoreBarShelfAndRecalculate(
        barId: Int,
        previousShelf: Set<Int>,
        toggledIngredientIds: Set<Int>,
    ) {
        val catalogIngredients = catalogDao.getIngredientsForBar(barId)
        val cocktails = cocktailDao.getCocktailsWithIngredientsForBar(barId)
        val recalc = recalculateCocktailAvailability(
            items = cocktails,
            shelfIngredientIds = previousShelf,
            catalogIngredients = catalogIngredients,
            toggledIngredientIds = toggledIngredientIds,
        )
        syncDao.applyBarShelfAndAvailability(
            barId = barId,
            replaceAllShelfIds = previousShelf.toList(),
            cocktailUpdates = recalc.cocktailUpdates,
            ingredientUpdates = recalc.ingredientUpdates,
        )
    }

    override suspend fun upsertShoppingListItems(items: List<ShoppingListEntity>) {
        inventoryDao.upsertShoppingListItems(items)
    }

    override suspend fun deleteShoppingListItems(barId: Int, ingredientIds: List<Int>) {
        inventoryDao.deleteShoppingListItems(barId, ingredientIds)
    }

    override suspend fun replaceShoppingList(barId: Int, items: List<ShoppingListEntity>) {
        inventoryDao.replaceShoppingList(barId, items)
    }

    override suspend fun replaceBarSnapshot(
        barId: Int,
        serverUrl: String,
        payload: BarSyncPayload,
        syncedAtEpochMillis: Long,
    ) {
        val mappedCocktails = payload.cocktails.distinctBy { it.id }
            .map { it.toEntityAndIngredients(barId, serverUrl) }
        val cocktailEntities = mappedCocktails.map { it.first }
        val cocktailIngredientEntities = mappedCocktails.flatMap { it.second }
        val ingredientEntities = payload.ingredients.distinctBy { it.id }
            .map { it.toEntity(barId, serverUrl) }
        val glassEntities = payload.glasses.distinctBy { it.id }
            .map { it.toEntity(barId) }
        val methodEntities = payload.methods.distinctBy { it.id }
            .map { it.toEntity(barId) }
        val tagEntities = payload.tags.distinctBy { it.id }
            .map { it.toEntity(barId) }
        val utensilEntities = payload.utensils.distinctBy { it.id }
            .map { it.toEntity(barId) }
        val shelfIds = payload.shelfIngredients.map { it.id }.distinct()
        val shoppingListEntities = payload.shoppingListItems
            ?.distinctBy { it.ingredient.id }
            ?.map { it.toEntity(barId) }

        syncDao.replaceBarSnapshot(
            barId = barId,
            cocktails = cocktailEntities,
            cocktailIngredients = cocktailIngredientEntities,
            ingredients = ingredientEntities,
            glasses = glassEntities,
            methods = methodEntities,
            tags = tagEntities,
            utensils = utensilEntities,
            shelfIngredientIds = shelfIds,
            syncKey = syncKey(barId),
            syncedAtEpochMillis = syncedAtEpochMillis,
            shoppingListItems = shoppingListEntities,
        )
    }

    private fun syncKey(barId: Int): String = "bar_${barId}_sync"
}
