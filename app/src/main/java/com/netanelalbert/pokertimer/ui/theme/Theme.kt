package com.netanelalbert.pokertimer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// Poker Timer always favors a dark, felt-table look: the timer typically sits
// on a table in a dim room, and a bright white theme would be both out of
// place and harsh to look at during a long session. The `darkTheme` parameter
// is still accepted (and honored) for callers/tests that want to force it,
// but the default is dark rather than following the system setting.
private val PokerTimerDarkColorScheme = darkColorScheme(
    primary = Gold,
    onPrimary = OnGoldDark,
    primaryContainer = GoldDim,
    onPrimaryContainer = CardWhite,
    secondary = GoldDim,
    onSecondary = CardWhite,
    background = FeltGreenDark,
    onBackground = CardWhite,
    surface = FeltGreenDark,
    onSurface = CardWhite,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = OnSurfaceMuted,
    outline = OutlineMuted,
    error = AlertRed,
    onError = CardWhite,
)

private val PokerTimerLightColorScheme = darkColorScheme(
    primary = Gold,
    onPrimary = OnGoldDark,
    primaryContainer = GoldDim,
    onPrimaryContainer = CardWhite,
    secondary = GoldDim,
    onSecondary = CardWhite,
    background = FeltGreen,
    onBackground = CardWhite,
    surface = FeltGreen,
    onSurface = CardWhite,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = OnSurfaceMuted,
    outline = OutlineMuted,
    error = AlertRed,
    onError = CardWhite,
)

@Composable
fun PokerTimerTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) PokerTimerDarkColorScheme else PokerTimerLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
