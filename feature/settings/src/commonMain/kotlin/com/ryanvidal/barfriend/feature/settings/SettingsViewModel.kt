package com.ryanvidal.barfriend.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ryanvidal.barfriend.core.data.AppPreferences
import com.ryanvidal.barfriend.core.data.AuthRepository
import com.ryanvidal.barfriend.core.data.BarSyncCoordinator
import com.ryanvidal.barfriend.core.data.SessionRepository
import com.ryanvidal.barfriend.core.data.SessionState
import com.ryanvidal.barfriend.core.data.SyncStatus
import com.ryanvidal.barfriend.core.model.AppThemeMode
import com.ryanvidal.barfriend.core.model.VolumeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val serverUrl: String? = null,
    val activeBarName: String? = null,
    val isConnected: Boolean = false,
    val preferredUnit: VolumeUnit = VolumeUnit.OZ,
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val syncStatus: SyncStatus = SyncStatus(),
    val isSigningOut: Boolean = false,
    val errorMessage: String? = null,
)

class SettingsViewModel(
    private val appPreferences: AppPreferences,
    private val sessionRepository: SessionRepository,
    private val authRepository: AuthRepository,
    private val syncCoordinator: BarSyncCoordinator,
) : ViewModel() {
    private val isSigningOut = MutableStateFlow(false)
    private val errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        appPreferences.settings,
        sessionRepository.sessionState,
        syncCoordinator.syncStatus,
        isSigningOut,
        errorMessage,
    ) { settings, session, syncStatus, signingOut, error ->
        val serverUrl = when (session) {
            is SessionState.Authenticated -> session.serverUrl
            is SessionState.Unauthenticated -> session.lastServerUrl ?: settings.serverUrl
            SessionState.Loading -> settings.serverUrl
        }
        val activeBarName = when (session) {
            is SessionState.Authenticated -> session.activeBarName ?: settings.activeBarName
            is SessionState.Unauthenticated -> null
            SessionState.Loading -> settings.activeBarName
        }
        SettingsUiState(
            serverUrl = serverUrl,
            activeBarName = activeBarName,
            isConnected = session is SessionState.Authenticated,
            preferredUnit = settings.preferredUnit,
            themeMode = settings.themeMode,
            syncStatus = syncStatus,
            isSigningOut = signingOut,
            errorMessage = error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState(),
    )

    fun setPreferredUnit(unit: VolumeUnit) {
        errorMessage.value = null
        viewModelScope.launch {
            runCatching {
                appPreferences.setPreferredUnit(unit)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                errorMessage.value = error.message?.takeIf { it.isNotBlank() }
                    ?: "Could not save measurement unit."
            }
        }
    }

    fun setThemeMode(mode: AppThemeMode) {
        errorMessage.value = null
        viewModelScope.launch {
            runCatching {
                appPreferences.setThemeMode(mode)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                errorMessage.value = error.message?.takeIf { it.isNotBlank() }
                    ?: "Could not save theme preference."
            }
        }
    }

    fun syncNow() {
        if (syncCoordinator.syncStatus.value.isSyncing || isSigningOut.value) return
        errorMessage.value = null
        viewModelScope.launch {
            try {
                syncCoordinator.syncAll(force = true).onFailure { error ->
                    errorMessage.value = error.message?.takeIf { it.isNotBlank() } ?: "Sync failed."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorMessage.value = e.message?.takeIf { it.isNotBlank() } ?: "Sync failed."
            }
        }
    }

    fun signOut() {
        if (isSigningOut.value) return
        errorMessage.value = null
        viewModelScope.launch {
            isSigningOut.value = true
            try {
                authRepository.signOut().onFailure { error ->
                    errorMessage.value = error.message?.takeIf { it.isNotBlank() } ?: "Sign out failed."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorMessage.value = e.message?.takeIf { it.isNotBlank() } ?: "Sign out failed."
            } finally {
                isSigningOut.value = false
            }
        }
    }
}
