package com.ryanvidal.barfriend.core.database

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ryanvidal.barfriend.core.database.dao.CatalogDao
import com.ryanvidal.barfriend.core.database.dao.CocktailDao
import com.ryanvidal.barfriend.core.database.dao.InventoryDao
import com.ryanvidal.barfriend.core.database.dao.SyncDao
import com.ryanvidal.barfriend.core.database.entity.BarShelfEntity
import com.ryanvidal.barfriend.core.database.entity.CocktailEntity
import com.ryanvidal.barfriend.core.database.entity.CocktailIngredientEntity
import com.ryanvidal.barfriend.core.database.entity.CocktailMethodEntity
import com.ryanvidal.barfriend.core.database.entity.GlassEntity
import com.ryanvidal.barfriend.core.database.entity.IngredientEntity
import com.ryanvidal.barfriend.core.database.entity.ShoppingListEntity
import com.ryanvidal.barfriend.core.database.entity.SyncMetadataEntity
import com.ryanvidal.barfriend.core.database.entity.TagEntity
import com.ryanvidal.barfriend.core.database.entity.UtensilEntity
import com.ryanvidal.barfriend.core.platform.PlatformContext
import com.ryanvidal.barfriend.core.platform.PlatformPaths
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

@Database(
    entities = [
        CocktailEntity::class,
        CocktailIngredientEntity::class,
        IngredientEntity::class,
        BarShelfEntity::class,
        ShoppingListEntity::class,
        GlassEntity::class,
        CocktailMethodEntity::class,
        TagEntity::class,
        UtensilEntity::class,
        SyncMetadataEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@ConstructedBy(BarFriendDatabaseConstructor::class)
abstract class BarFriendDatabase : RoomDatabase() {
    abstract fun cocktailDao(): CocktailDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun catalogDao(): CatalogDao
    abstract fun syncDao(): SyncDao
}

@Suppress("KotlinNoActualForExpect")
expect object BarFriendDatabaseConstructor : RoomDatabaseConstructor<BarFriendDatabase> {
    override fun initialize(): BarFriendDatabase
}

expect fun createDatabaseBuilder(
    context: PlatformContext,
    dbPath: String,
): RoomDatabase.Builder<BarFriendDatabase>

@ContributesTo(AppScope::class)
@BindingContainer
object DatabaseBindings {
    @SingleIn(AppScope::class)
    @Provides
    fun provideDatabase(
        platformContext: PlatformContext,
        platformPaths: PlatformPaths,
    ): BarFriendDatabase {
        val dbPath = platformPaths.databasePath("bar_friend.db")
        return createDatabaseBuilder(platformContext, dbPath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @Provides
    fun provideCocktailDao(database: BarFriendDatabase): CocktailDao = database.cocktailDao()

    @Provides
    fun provideInventoryDao(database: BarFriendDatabase): InventoryDao = database.inventoryDao()

    @Provides
    fun provideCatalogDao(database: BarFriendDatabase): CatalogDao = database.catalogDao()

    @Provides
    fun provideSyncDao(database: BarFriendDatabase): SyncDao = database.syncDao()
}
