package com.camillanapoles.droidauditor.ui.theme

import androidx.compose.ui.graphics.Color

val CriticalRed = Color(0xFFD32F2F)
val HighOrange = Color(0xFFF57C00)
val MediumAmber = Color(0xFFF9A825)
val InfoBlue = Color(0xFF1976D2)
val OkGreen = Color(0xFF388E3C)

val LightPrimary = Color(0xFF0277BD)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFE1F5FE)
val LightOnPrimaryContainer = Color(0xFF0D3C5C)
val LightBackground = Color(0xFFFBFDFF)
val LightSurface = Color(0xFFFBFDFF)
val LightOnSurface = Color(0xFF171C20)
val LightOnSurfaceVariant = Color(0xFF41484E)

val DarkPrimary = Color(0xFF81D4FA)
val DarkOnPrimary = Color(0xFF00344D)
val DarkPrimaryContainer = Color(0xFF154B68)
val DarkOnPrimaryContainer = Color(0xFFBEE9FF)
val DarkBackground = Color(0xFF101418)
val DarkSurface = Color(0xFF101418)
val DarkOnSurface = Color(0xFFE1E2E8)
val DarkOnSurfaceVariant = Color(0xFFC3C7CF)

fun severityColor(severity: String): Color = when (severity) {
    "CRITICAL" -> CriticalRed
    "HIGH" -> HighOrange
    "MEDIUM" -> MediumAmber
    else -> InfoBlue
}
