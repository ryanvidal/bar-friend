package com.ryanvidal.barfriend.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import com.ryanvidal.barfriend.core.model.AppThemeMode

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BarFriendTheme(
    themeMode: AppThemeMode = AppThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    MaterialExpressiveTheme(
        colorScheme = if (darkTheme) BarFriendDarkColorScheme else BarFriendLightColorScheme,
        shapes = BarFriendShapes,
        typography = BarFriendTypography,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
