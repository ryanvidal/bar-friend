package com.ryanvidal.barfriend

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.rememberViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ryanvidal.barfriend.core.data.SessionState
import com.ryanvidal.barfriend.core.data.UserSettings
import com.ryanvidal.barfriend.core.designsystem.BarFriendTheme
import com.ryanvidal.barfriend.di.AppGraph
import com.ryanvidal.barfriend.feature.auth.AuthScreen
import com.ryanvidal.barfriend.feature.auth.AuthViewModel
import com.ryanvidal.barfriend.navigation.MainShell

@Composable
fun BarFriendApp(appGraph: AppGraph) {
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
                val authStoreOwner = rememberViewModelStoreOwner()
                CompositionLocalProvider(LocalViewModelStoreOwner provides authStoreOwner) {
                    val authViewModel = viewModel(key = "auth") {
                        AuthViewModel(appGraph.authRepository)
                    }
                    AuthScreen(
                        viewModel = authViewModel,
                        initialServerUrl = session.lastServerUrl,
                    )
                }
            }
            is SessionState.Authenticated -> {
                key(session.serverUrl, session.activeBarId) {
                    MainShell(
                        appGraph = appGraph,
                        serverUrl = session.serverUrl,
                        activeBarId = session.activeBarId,
                    )
                }
            }
        }
    }
}
