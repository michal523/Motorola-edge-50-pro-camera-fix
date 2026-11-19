package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val KadrDarkColorScheme = darkColorScheme(
    primary = AmberPro,
    onPrimary = VoidBlack,
    primaryContainer = AmberProContainer,
    onPrimaryContainer = AmberPro,
    secondary = CyanOptic,
    onSecondary = VoidBlack,
    secondaryContainer = CyanOpticContainer,
    onSecondaryContainer = CyanOptic,
    tertiary = EmeraldStable,
    onTertiary = VoidBlack,
    background = VoidBlack,
    onBackground = TextPrimary,
    surface = SurfaceObsidian,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    outline = BorderSubtle,
    error = CrimsonRecord,
    onError = TextPrimary
)

val KadrShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp)
)

@Composable
fun KadrRawTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = KadrDarkColorScheme,
        typography = KadrTypography,
        shapes = KadrShapes,
        content = content
    )
}
