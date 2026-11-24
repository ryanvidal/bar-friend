package com.ryanvidal.barfriend.core.data

import com.ryanvidal.barfriend.core.model.BarCatalog
import com.ryanvidal.barfriend.core.model.CocktailDetail
import com.ryanvidal.barfriend.core.model.CocktailFilter
import com.ryanvidal.barfriend.core.model.CocktailSummary
import com.ryanvidal.barfriend.core.model.CocktailUpsertRequest
import com.ryanvidal.barfriend.core.model.ScrapedCocktail
import com.ryanvidal.barfriend.core.model.UploadedImage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultCocktailRepository(
    private val localDataSource: BarLocalDataSource,
    private val remoteDataSource: BarRemoteDataSource,
    private val sessionRepository: SessionRepository,
    private val mutationCoordinator: BarMutationCoordinator,
) : CocktailRepository {

    private val favoriteMutexMapLock = Mutex()
    private val favoriteMutexes = mutableMapOf<Int, Mutex>()
    private val activeBarIdFlow: Flow<Int?> = sessionRepository.activeBarIdFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeCocktails(filter: CocktailFilter): Flow<List<CocktailSummary>> =
        activeBarIdFlow.flatMapLatest { barId ->
            if (barId == null) {
                flowOf(emptyList())
            } else {
                localDataSource.observeCocktails(barId, filter)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeCocktailDetail(cocktailId: Int): Flow<CocktailDetail?> =
        activeBarIdFlow.flatMapLatest { barId ->
            if (barId == null) {
                flowOf(null)
            } else {
                localDataSource.observeCocktailDetail(barId, cocktailId)
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeCatalog(): Flow<BarCatalog> =
        activeBarIdFlow.flatMapLatest { barId ->
            if (barId == null) {
                flowOf(BarCatalog())
            } else {
                localDataSource.observeCatalog(barId)
            }
        }

    override suspend fun refreshCocktail(cocktailId: Int): Result<CocktailDetail> = runSuspendCatching {
        val barId = sessionRepository.requireActiveBarId()
        val serverUrl = sessionRepository.currentServerUrl()
        val dto = remoteDataSource.getCocktail(cocktailId)
        mutationCoordinator.withMutationLock {
            mutationCoordinator.bumpGenerationLocked()
            if (sessionRepository.currentActiveBarId() != barId) {
                throw IllegalStateException("Active bar changed during cocktail refresh.")
            }
            localDataSource.replaceCocktail(dto, barId, serverUrl)
        }
    }

    override suspend fun toggleFavorite(cocktailId: Int): Result<Boolean> =
        favoriteMutexFor(cocktailId).withLock {
            val barId = sessionRepository.currentActiveBarId()
                ?: return@withLock Result.failure(IllegalStateException("No active bar selected."))
            val existing = localDataSource.getCocktailWithIngredients(barId, cocktailId)
                ?: return@withLock runSuspendCatching {
                    val actual = remoteDataSource.toggleFavorite(cocktailId)
                    mutationCoordinator.withMutationLock { mutationCoordinator.bumpGenerationLocked() }
                    actual
                }

            val previous = existing.cocktail.isFavorite
            val optimistic = !previous
            mutationCoordinator.withMutationLock {
                mutationCoordinator.bumpGenerationLocked()
                localDataSource.updateFavorite(barId, cocktailId, optimistic)
            }

            try {
                val actual = remoteDataSource.toggleFavorite(cocktailId)
                mutationCoordinator.withMutationLock {
                    mutationCoordinator.bumpGenerationLocked()
                    if (sessionRepository.currentActiveBarId() == barId && actual != optimistic) {
                        localDataSource.updateFavorite(barId, cocktailId, actual)
                    }
                }
                Result.success(actual)
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    mutationCoordinator.withMutationLock {
                        mutationCoordinator.bumpGenerationLocked()
                        if (sessionRepository.currentActiveBarId() == barId) {
                            localDataSource.updateFavorite(barId, cocktailId, previous)
                        }
                    }
                }
                throw e
            } catch (e: Throwable) {
                mutationCoordinator.withMutationLock {
                    mutationCoordinator.bumpGenerationLocked()
                    if (sessionRepository.currentActiveBarId() == barId) {
                        localDataSource.updateFavorite(barId, cocktailId, previous)
                    }
                }
                Result.failure(e)
            }
        }

    override suspend fun saveCocktail(
        cocktailId: Int?,
        request: CocktailUpsertRequest,
    ): Result<CocktailDetail> = runSuspendCatching {
        val barId = sessionRepository.requireActiveBarId()
        val serverUrl = sessionRepository.currentServerUrl()
        val dto = remoteDataSource.saveCocktail(cocktailId, request)
        mutationCoordinator.withMutationLock {
            mutationCoordinator.bumpGenerationLocked()
            if (sessionRepository.currentActiveBarId() != barId) {
                throw IllegalStateException("Active bar changed while saving cocktail.")
            }
            localDataSource.replaceCocktail(dto, barId, serverUrl, upsertTags = true)
        }
    }

    override suspend fun deleteCocktail(cocktailId: Int): Result<Unit> = runSuspendCatching {
        val barId = sessionRepository.requireActiveBarId()
        remoteDataSource.deleteCocktail(cocktailId)
        mutationCoordinator.withMutationLock {
            mutationCoordinator.bumpGenerationLocked()
            if (sessionRepository.currentActiveBarId() == barId) {
                localDataSource.deleteCocktail(barId, cocktailId)
            }
        }
    }

    override suspend fun uploadImage(
        bytes: ByteArray,
        fileName: String,
        mimeType: String?,
        sort: Int,
    ): Result<UploadedImage> = runSuspendCatching {
        val serverUrl = sessionRepository.currentServerUrl()
        remoteDataSource.uploadImage(
            bytes = bytes,
            fileName = fileName,
            mimeType = mimeType,
            sort = sort,
            serverUrl = serverUrl,
        )
    }

    override suspend fun uploadImageFromUrl(
        imageUrl: String,
        copyright: String?,
        sort: Int,
    ): Result<UploadedImage> = runSuspendCatching {
        val serverUrl = sessionRepository.currentServerUrl()
        remoteDataSource.uploadImageFromUrl(
            imageUrl = imageUrl,
            copyright = copyright,
            sort = sort,
            serverUrl = serverUrl,
        )
    }

    override suspend fun scrapeRecipeFromUrl(url: String): Result<ScrapedCocktail> = runSuspendCatching {
        remoteDataSource.scrapeRecipeFromUrl(url)
    }

    private suspend fun favoriteMutexFor(cocktailId: Int): Mutex =
        favoriteMutexMapLock.withLock {
            favoriteMutexes.getOrPut(cocktailId) { Mutex() }
        }
}
