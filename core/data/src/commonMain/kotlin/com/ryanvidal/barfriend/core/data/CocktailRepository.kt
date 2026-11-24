package com.ryanvidal.barfriend.core.data

import com.ryanvidal.barfriend.core.model.BarCatalog
import com.ryanvidal.barfriend.core.model.CocktailDetail
import com.ryanvidal.barfriend.core.model.CocktailFilter
import com.ryanvidal.barfriend.core.model.CocktailSummary
import com.ryanvidal.barfriend.core.model.CocktailUpsertRequest
import com.ryanvidal.barfriend.core.model.ScrapedCocktail
import com.ryanvidal.barfriend.core.model.UploadedImage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class SyncStatus(
    val isSyncing: Boolean = false,
    val lastSyncedEpochMillis: Long? = null,
    val errorMessage: String? = null,
)

interface CocktailRepository {
    val syncStatus: StateFlow<SyncStatus>

    fun observeCocktails(filter: CocktailFilter): Flow<List<CocktailSummary>>
    fun observeCocktailDetail(cocktailId: Int): Flow<CocktailDetail?>
    fun observeCatalog(): Flow<BarCatalog>

    suspend fun syncAll(force: Boolean = false): Result<Unit>
    suspend fun refreshCocktail(cocktailId: Int): Result<CocktailDetail>
    suspend fun toggleFavorite(cocktailId: Int): Result<Boolean>
    suspend fun saveCocktail(cocktailId: Int?, request: CocktailUpsertRequest): Result<CocktailDetail>
    suspend fun deleteCocktail(cocktailId: Int): Result<Unit>
    suspend fun uploadImage(
        bytes: ByteArray,
        fileName: String,
        mimeType: String?,
        sort: Int = 1,
    ): Result<UploadedImage>
    suspend fun uploadImageFromUrl(
        imageUrl: String,
        copyright: String? = null,
        sort: Int = 1,
    ): Result<UploadedImage>
    suspend fun scrapeRecipeFromUrl(url: String): Result<ScrapedCocktail>
}
