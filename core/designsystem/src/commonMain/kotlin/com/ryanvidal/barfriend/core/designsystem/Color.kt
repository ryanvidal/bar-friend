package com.ryanvidal.barfriend.core.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val CopperAmber = Color(0xFFE09145)
val EspressoInk = Color(0xFF3E2206)
val AgedAmberContainer = Color(0xFF5A3410)
val WarmCreamOnAmber = Color(0xFFFFDDC0)

val ChartreuseHerb = Color(0xFFA9C46C)
val OliveInk = Color(0xFF203007)
val HerbContainer = Color(0xFF364A15)
val PaleSage = Color(0xFFD2EC99)

val TerracottaBitters = Color(0xFFD96B52)
val BittersInk = Color(0xFF421208)
val BittersContainer = Color(0xFF5E2114)
val PeachTint = Color(0xFFFFDAD1)

val LoungeBackgroundDark = Color(0xFF14110F)
val LoungeSurfaceDark = Color(0xFF1A1512)
val LoungeSurfaceContainerLowDark = Color(0xFF1E1916)
val LoungeSurfaceContainerDark = Color(0xFF251F1B)
val LoungeSurfaceContainerHighDark = Color(0xFF2F2823)
val LoungeSurfaceContainerHighestDark = Color(0xFF3B322C)
val WarmIvory = Color(0xFFF3ECE6)
val MutedTaupe = Color(0xFFCEC2B8)
val OutlineTaupe = Color(0xFF978B81)
val OutlineSubtleDark = Color(0xFF4D433B)

val DeepCopper = Color(0xFF8F4C0E)
val CopperTintLight = Color(0xFFFFDCBE)
val DarkEspresso = Color(0xFF2F1500)
val DeepOlive = Color(0xFF4F6622)
val OliveTintLight = Color(0xFFD1EC9B)
val DeepHerbInk = Color(0xFF141F02)
val BurntBitters = Color(0xFF9E3A25)
val BittersTintLight = Color(0xFFFFDAD2)

val LinenBackgroundLight = Color(0xFFFAF6F1)
val LinenSurfaceContainerLowLight = Color(0xFFF4EFE8)
val LinenSurfaceContainerLight = Color(0xFFEEE7DF)
val LinenSurfaceContainerHighLight = Color(0xFFE8E0D7)
val LinenSurfaceContainerHighestLight = Color(0xFFE1D8CE)
val CharcoalText = Color(0xFF201A16)
val WarmUmberText = Color(0xFF51453D)
val OutlineLight = Color(0xFF83756B)
val OutlineSubtleLight = Color(0xFFD5C7BD)

val StatusReadyGreen = Color(0xFF7FB356)
val StatusSubstituteAmber = Color(0xFFE09145)
val StatusMissingTerracotta = Color(0xFFD96B52)

@Immutable
data class BarFriendStatusColors(
    val ready: Color,
    val substitute: Color,
    val missing: Color,
)

val BarFriendDarkStatusColors = BarFriendStatusColors(
    ready = StatusReadyGreen,
    substitute = StatusSubstituteAmber,
    missing = StatusMissingTerracotta,
)

val BarFriendLightStatusColors = BarFriendStatusColors(
    ready = Color(0xFF2B6419),
    substitute = Color(0xFF8F4700),
    missing = Color(0xFFB0351E),
)

val LocalBarFriendStatusColors = staticCompositionLocalOf { BarFriendDarkStatusColors }

val BarFriendDarkColorScheme = darkColorScheme(
    primary = CopperAmber,
    onPrimary = EspressoInk,
    primaryContainer = AgedAmberContainer,
    onPrimaryContainer = WarmCreamOnAmber,
    secondary = ChartreuseHerb,
    onSecondary = OliveInk,
    secondaryContainer = HerbContainer,
    onSecondaryContainer = PaleSage,
    tertiary = TerracottaBitters,
    onTertiary = BittersInk,
    tertiaryContainer = BittersContainer,
    onTertiaryContainer = PeachTint,
    background = LoungeBackgroundDark,
    onBackground = WarmIvory,
    surface = LoungeSurfaceDark,
    onSurface = WarmIvory,
    surfaceVariant = LoungeSurfaceContainerHighDark,
    onSurfaceVariant = MutedTaupe,
    surfaceContainerLowest = Color(0xFF0F0C0A),
    surfaceContainerLow = LoungeSurfaceContainerLowDark,
    surfaceContainer = LoungeSurfaceContainerDark,
    surfaceContainerHigh = LoungeSurfaceContainerHighDark,
    surfaceContainerHighest = LoungeSurfaceContainerHighestDark,
    outline = OutlineTaupe,
    outlineVariant = OutlineSubtleDark,
)

val BarFriendLightColorScheme = lightColorScheme(
    primary = DeepCopper,
    onPrimary = Color.White,
    primaryContainer = CopperTintLight,
    onPrimaryContainer = DarkEspresso,
    secondary = DeepOlive,
    onSecondary = Color.White,
    secondaryContainer = OliveTintLight,
    onSecondaryContainer = DeepHerbInk,
    tertiary = BurntBitters,
    onTertiary = Color.White,
    tertiaryContainer = BittersTintLight,
    onTertiaryContainer = Color(0xFF3C0901),
    background = LinenBackgroundLight,
    onBackground = CharcoalText,
    surface = LinenBackgroundLight,
    onSurface = CharcoalText,
    surfaceVariant = LinenSurfaceContainerHighLight,
    onSurfaceVariant = WarmUmberText,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = LinenSurfaceContainerLowLight,
    surfaceContainer = LinenSurfaceContainerLight,
    surfaceContainerHigh = LinenSurfaceContainerHighLight,
    surfaceContainerHighest = LinenSurfaceContainerHighestLight,
    outline = OutlineLight,
    outlineVariant = OutlineSubtleLight,
)
