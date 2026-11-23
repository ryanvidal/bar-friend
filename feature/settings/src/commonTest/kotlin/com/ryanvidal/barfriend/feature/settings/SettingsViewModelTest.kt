package com.ryanvidal.barfriend.feature.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.ryanvidal.barfriend.core.data.AppPreferences
import com.ryanvidal.barfriend.core.data.AuthRepository
import com.ryanvidal.barfriend.core.data.CocktailRepository
import com.ryanvidal.barfriend.core.data.ServerCheckResult
import com.ryanvidal.barfriend.core.data.SessionRepository
import com.ryanvidal.barfriend.core.data.SessionState
import com.ryanvidal.barfriend.core.data.SyncStatus
import com.ryanvidal.barfriend.core.model.AppThemeMode
import com.ryanvidal.barfriend.core.model.Bar
import com.ryanvidal.barfriend.core.model.BarCatalog
import com.ryanvidal.barfriend.core.model.CocktailDetail
import com.ryanvidal.barfriend.core.model.CocktailFilter
import com.ryanvidal.barfriend.core.model.CocktailSummary
import com.ryanvidal.barfriend.core.model.CocktailUpsertRequest
import com.ryanvidal.barfriend.core.model.ScrapedCocktail
import com.ryanvidal.barfriend.core.model.UploadedImage
import com.ryanvidal.barfriend.core.model.VolumeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `uiState combines preferences, session state, and sync status`() = runTest(testDispatcher) {
        val preferences = AppPreferences(FakePreferencesDataStore())
        preferences.setServerConnection("https://bar.example.com", barId = 7, barName = "Speakeasy")
        val sessionRepository = FakeSessionRepository(
            SessionState.Authenticated(
                serverUrl = "https://bar.example.com",
                activeBarId = 7,
                activeBarName = "Speakeasy",
            ),
        )
        val cocktailRepository = FakeCocktailRepository(
            SyncStatus(isSyncing = false, lastSyncedEpochMillis = 1_700_000_000_000L),
        )
        val viewModel = SettingsViewModel(
            appPreferences = preferences,
            sessionRepository = sessionRepository,
            authRepository = FakeAuthRepository(),
            cocktailRepository = cocktailRepository,
        )

        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("https://bar.example.com", state.serverUrl)
        assertEquals("Speakeasy", state.activeBarName)
        assertTrue(state.isConnected)
        assertEquals(VolumeUnit.OZ, state.preferredUnit)
        assertEquals(AppThemeMode.DARK, state.themeMode)
        assertEquals(1_700_000_000_000L, state.syncStatus.lastSyncedEpochMillis)
    }

    @Test
    fun `setPreferredUnit and setThemeMode update preferences and uiState`() = runTest(testDispatcher) {
        val preferences = AppPreferences(FakePreferencesDataStore())
        val viewModel = SettingsViewModel(
            appPreferences = preferences,
            sessionRepository = FakeSessionRepository(),
            authRepository = FakeAuthRepository(),
            cocktailRepository = FakeCocktailRepository(),
        )

        viewModel.setPreferredUnit(VolumeUnit.CL)
        viewModel.setThemeMode(AppThemeMode.LIGHT)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(VolumeUnit.CL, state.preferredUnit)
        assertEquals(AppThemeMode.LIGHT, state.themeMode)
    }

    @Test
    fun `syncNow invokes force sync and reflects updated syncStatus`() = runTest(testDispatcher) {
        val cocktailRepository = FakeCocktailRepository()
        val viewModel = SettingsViewModel(
            appPreferences = AppPreferences(FakePreferencesDataStore()),
            sessionRepository = FakeSessionRepository(),
            authRepository = FakeAuthRepository(),
            cocktailRepository = cocktailRepository,
        )

        viewModel.syncNow()
        advanceUntilIdle()

        assertEquals(listOf(true), cocktailRepository.syncForceCalls)
        assertEquals(1_800_000_000_000L, viewModel.uiState.value.syncStatus.lastSyncedEpochMillis)
    }

    @Test
    fun `signOut calls authRepository and updates session state`() = runTest(testDispatcher) {
        val sessionRepository = FakeSessionRepository(
            SessionState.Authenticated("https://bar.example.com", 1, "Home Bar"),
        )
        val authRepository = FakeAuthRepository(
            onSignOut = {
                sessionRepository.clearSession()
                Result.success(Unit)
            },
        )
        val viewModel = SettingsViewModel(
            appPreferences = AppPreferences(FakePreferencesDataStore()),
            sessionRepository = sessionRepository,
            authRepository = authRepository,
            cocktailRepository = FakeCocktailRepository(),
        )

        viewModel.signOut()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, authRepository.signOutCallCount)
        assertFalse(state.isSigningOut)
        assertFalse(state.isConnected)
        assertNull(state.activeBarName)
        assertNull(state.errorMessage)
    }

    @Test
    fun `signOut failure surfaces errorMessage and clears isSigningOut`() = runTest(testDispatcher) {
        val authRepository = FakeAuthRepository(
            onSignOut = { Result.failure(IllegalStateException("Network error")) },
        )
        val viewModel = SettingsViewModel(
            appPreferences = AppPreferences(FakePreferencesDataStore()),
            sessionRepository = FakeSessionRepository(),
            authRepository = authRepository,
            cocktailRepository = FakeCocktailRepository(),
        )

        viewModel.signOut()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isSigningOut)
        assertEquals("Network error", state.errorMessage)
    }

    private class FakePreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val updated = transform(state.value)
            state.value = updated
            return updated
        }
    }

    private class FakeSessionRepository(
        initialState: SessionState = SessionState.Unauthenticated(),
    ) : SessionRepository {
        private val _sessionState = MutableStateFlow(initialState)
        override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

        override suspend fun saveSession(serverUrl: String, token: String, barId: Int?, barName: String?) {
            _sessionState.value = SessionState.Authenticated(serverUrl, barId, barName)
        }

        override suspend fun clearSession() {
            val lastUrl = (_sessionState.value as? SessionState.Authenticated)?.serverUrl
            _sessionState.value = SessionState.Unauthenticated(lastServerUrl = lastUrl)
        }

        override suspend fun currentServerUrl(): String? =
            (_sessionState.value as? SessionState.Authenticated)?.serverUrl

        override suspend fun currentToken(): String? = null

        override suspend fun currentBarId(): Int? =
            (_sessionState.value as? SessionState.Authenticated)?.activeBarId

        override suspend fun onUnauthorized() {
            clearSession()
        }
    }

    private class FakeAuthRepository(
        private val onSignOut: suspend () -> Result<Unit> = { Result.success(Unit) },
    ) : AuthRepository {
        var signOutCallCount = 0

        override suspend fun checkServer(serverUrl: String): ServerCheckResult =
            ServerCheckResult.Error("Unused")

        override suspend fun signIn(serverUrl: String, email: String, password: String): Result<Bar> =
            Result.failure(IllegalStateException("Unused"))

        override suspend fun signOut(): Result<Unit> {
            signOutCallCount += 1
            return onSignOut()
        }
    }

    private class FakeCocktailRepository(
        initialStatus: SyncStatus = SyncStatus(),
    ) : CocktailRepository {
        private val _syncStatus = MutableStateFlow(initialStatus)
        override val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()
        val syncForceCalls = mutableListOf<Boolean>()

        override fun observeCocktails(filter: CocktailFilter): Flow<List<CocktailSummary>> =
            flowOf(emptyList())

        override fun observeCocktailDetail(cocktailId: Int): Flow<CocktailDetail?> =
            flowOf(null)

        override fun observeCatalog(): Flow<BarCatalog> =
            flowOf(BarCatalog())

        override suspend fun syncAll(force: Boolean): Result<Unit> {
            syncForceCalls += force
            _syncStatus.value = SyncStatus(isSyncing = false, lastSyncedEpochMillis = 1_800_000_000_000L)
            return Result.success(Unit)
        }

        override suspend fun refreshCocktail(cocktailId: Int): Result<CocktailDetail> =
            Result.failure(IllegalStateException("Unused"))

        override suspend fun toggleFavorite(cocktailId: Int): Result<Boolean> =
            Result.failure(IllegalStateException("Unused"))

        override suspend fun saveCocktail(
            cocktailId: Int?,
            request: CocktailUpsertRequest,
        ): Result<CocktailDetail> = Result.failure(IllegalStateException("Unused"))

        override suspend fun deleteCocktail(cocktailId: Int): Result<Unit> =
            Result.failure(IllegalStateException("Unused"))

        override suspend fun uploadImage(
            bytes: ByteArray,
            fileName: String,
            mimeType: String?,
            sort: Int,
        ): Result<UploadedImage> = Result.failure(IllegalStateException("Unused"))

        override suspend fun uploadImageFromUrl(
            imageUrl: String,
            copyright: String?,
            sort: Int,
        ): Result<UploadedImage> = Result.failure(IllegalStateException("Unused"))

        override suspend fun scrapeRecipeFromUrl(url: String): Result<ScrapedCocktail> =
            Result.failure(IllegalStateException("Unused"))
    }
}
