package com.ryanvidal.barfriend

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ryanvidal.barfriend.core.data.UserSettings
import com.ryanvidal.barfriend.core.model.AppThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appGraph = (application as BarFriendApplication).appGraph
        setContent {
            val settings by appGraph.appPreferences.settings.collectAsStateWithLifecycle(UserSettings())
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (settings.themeMode) {
                AppThemeMode.DARK -> true
                AppThemeMode.LIGHT -> false
                AppThemeMode.SYSTEM -> systemDark
            }
            DisposableEffect(darkTheme) {
                val style = if (darkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(
                    statusBarStyle = style,
                    navigationBarStyle = style,
                )
                onDispose {}
            }
            BarFriendApp(appGraph = appGraph)
        }
    }
}
