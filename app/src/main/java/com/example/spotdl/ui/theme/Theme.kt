package com.example.spotdl.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val EditorialLightColorScheme = lightColorScheme(
    primary = EditorialPrimary,
    onPrimary = EditorialOnPrimary,
    primaryContainer = EditorialPrimaryContainer,
    onPrimaryContainer = EditorialOnPrimaryContainer,
    secondary = EditorialSecondary,
    onSecondary = EditorialSurface,
    tertiary = EditorialTertiary,
    onTertiary = EditorialOnTertiary,
    tertiaryContainer = EditorialTertiaryContainer,
    onTertiaryContainer = EditorialOnTertiaryFixedVariant,
    background = EditorialCanvas,
    onBackground = EditorialOnSurface,
    surface = EditorialSurface,
    onSurface = EditorialOnSurface,
    surfaceVariant = EditorialSurfaceContainerHigh,
    onSurfaceVariant = EditorialOnSurfaceVariant,
    surfaceContainer = EditorialSurfaceContainer,
    surfaceContainerLow = EditorialSurfaceContainerLow,
    surfaceContainerHigh = EditorialSurfaceContainerHigh,
    surfaceContainerHighest = EditorialSurfaceContainerHighest,
    outline = EditorialOutline,
    outlineVariant = EditorialKeyline,
    error = EditorialError,
    errorContainer = EditorialErrorContainer,
    onErrorContainer = EditorialOnErrorContainer
)

@Composable
fun SpotdlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // In accordance with Google Stitch "Editorial Audio" design specification,
    // we use the tactile paper warm tones (#FAF9F5) as the primary aesthetic.
    MaterialTheme(
        colorScheme = EditorialLightColorScheme,
        typography = EditorialTypography,
        content = content
    )
}
