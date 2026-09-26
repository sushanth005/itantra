package com.itantra.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

// ─── Brand Palette ────────────────────────────────────────────────────────────
// Deep space blues + ISRO amber/gold accent

val SpaceNavy     = Color(0xFF0A0E1F)
val SpaceBlue     = Color(0xFF0D1B3E)
val CosmoBlue     = Color(0xFF1A2B5C)
val StellarBlue   = Color(0xFF243B7A)
val NebulaPurple  = Color(0xFF2D1F6E)

val IsroAmber     = Color(0xFFFFA726)
val IsroGold      = Color(0xFFFFCC02)
val AlertRed      = Color(0xFFFF3B30)
val AlertRedDim   = Color(0xFF7F1D1D)

val SurfaceDark   = Color(0xFF111827)
val SurfaceMid    = Color(0xFF1F2937)
val SurfaceLight  = Color(0xFF374151)
val OnSurface     = Color(0xFFE5E7EB)
val OnSurfaceDim  = Color(0xFF9CA3AF)

val SignalGreen   = Color(0xFF34D399)
val SignalOrange  = Color(0xFFFBBF24)

// ─── Dark Color Scheme ────────────────────────────────────────────────────────

val iTantraDarkColorScheme = darkColorScheme(
    primary          = IsroAmber,
    onPrimary        = SpaceNavy,
    primaryContainer = StellarBlue,
    onPrimaryContainer = IsroGold,

    secondary        = SignalGreen,
    onSecondary      = SpaceNavy,
    secondaryContainer = Color(0xFF064E3B),
    onSecondaryContainer = SignalGreen,

    tertiary         = NebulaPurple,
    onTertiary       = OnSurface,

    error            = AlertRed,
    onError          = Color.White,
    errorContainer   = AlertRedDim,
    onErrorContainer = Color(0xFFFFB4AB),

    background       = SpaceNavy,
    onBackground     = OnSurface,
    surface          = SurfaceDark,
    onSurface        = OnSurface,
    surfaceVariant   = SurfaceMid,
    onSurfaceVariant = OnSurfaceDim,
    outline          = SurfaceLight,
    outlineVariant   = SurfaceMid,

    inverseSurface   = OnSurface,
    inverseOnSurface = SpaceNavy,
    inversePrimary   = StellarBlue,
)
