package com.netanelalbert.pokertimer.ui.theme

import androidx.compose.ui.graphics.Color

// Poker-table palette: a dim, felt-green table under a single gold light.

/** Primary felt green — used for surfaces that sit "on the table". */
val FeltGreen = Color(0xFF0B3D2E)

/** Darker felt green — the app background, deeper in shadow than [FeltGreen]. */
val FeltGreenDark = Color(0xFF07271E)

/** Gold accent — chips, highlights, primary actions. */
val Gold = Color(0xFFD4AF37)

/** Dimmed gold — secondary accents, disabled/inactive gold elements. */
val GoldDim = Color(0xFF8A7226)

/** Warm off-white — reads like a playing card under table light. */
val CardWhite = Color(0xFFF5F5F0)

/** Alert red — time-critical states (e.g. blinds about to go up). */
val AlertRed = Color(0xFFC62828)

/** Warning amber — the lead-up warning before blinds go up. */
val WarnAmber = Color(0xFFFFA000)

// Neutral surfaces/outlines built around the felt palette.

/** A slightly lighter surface than [FeltGreenDark], for cards/panels. */
val SurfaceElevated = Color(0xFF123A2E)

/** Subtle outline/divider color that stays visible on dark felt. */
val OutlineMuted = Color(0xFF3A5C4E)

/** Muted foreground text/icons, for secondary content on dark surfaces. */
val OnSurfaceMuted = Color(0xFFB8C4BE)

/** Near-black, used for text/icons drawn on top of [Gold]. */
val OnGoldDark = Color(0xFF1A1200)
