package dev.blanky.vinyl.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = VinylPrimaryDark,
    onPrimary = VinylOnPrimaryDark,
    primaryContainer = VinylPrimaryContainerDark,
    onPrimaryContainer = VinylOnPrimaryContainerDark,
    secondary = VinylSecondaryDark,
    onSecondary = VinylOnSecondaryDark,
    secondaryContainer = VinylSecondaryContainerDark,
    onSecondaryContainer = VinylOnSecondaryContainerDark,
    tertiary = VinylTertiaryDark,
    onTertiary = VinylOnTertiaryDark,
    tertiaryContainer = VinylTertiaryContainerDark,
    onTertiaryContainer = VinylOnTertiaryContainerDark,
    background = VinylBg,
    onBackground = VinylOnSurfaceDark,
    surface = VinylSurface,
    onSurface = VinylOnSurfaceDark,
    surfaceVariant = VinylSurfaceVariantDark,
    onSurfaceVariant = VinylOnSurfaceVariantDark,
    outline = VinylOutlineDark,
    outlineVariant = VinylOutlineVariantDark,
    inverseSurface = VinylInverseSurfaceDark,
    onInverseSurface = VinylOnInverseSurfaceDark,
    inverseOnSurface = VinylInverseSurfaceDark,
    error = VinylErrorDark,
    onError = VinylOnErrorDark,
    errorContainer = VinylErrorContainerDark,
    onErrorContainer = VinylOnErrorContainerDark,
    scrim = VinylScrim,
    surfaceContainerLowest = VinylSurfaceContainerLowest,
    surfaceContainerLow = VinylSurfaceContainerLow,
    surfaceContainer = VinylSurfaceContainer,
    surfaceContainerHigh = VinylSurfaceContainerHigh,
    surfaceContainerHighest = VinylSurfaceContainerHighest,
)

private val LightColors = lightColorScheme(
    primary = VinylPrimaryLight,
    onPrimary = VinylOnPrimaryLight,
    primaryContainer = VinylPrimaryContainerLight,
    onPrimaryContainer = VinylOnPrimaryContainerLight,
    secondary = VinylSecondaryLight,
    onSecondary = VinylOnSecondaryLight,
    secondaryContainer = VinylSecondaryContainerLight,
    onSecondaryContainer = VinylOnSecondaryContainerLight,
    tertiary = VinylTertiaryLight,
    onTertiary = VinylOnTertiaryLight,
    tertiaryContainer = VinylTertiaryContainerLight,
    onTertiaryContainer = VinylOnTertiaryContainerLight,
    background = VinylBgLight,
    onBackground = VinylOnSurfaceLight,
    surface = VinylSurfaceLight,
    onSurface = VinylOnSurfaceLight,
    surfaceVariant = VinylSurfaceVariantLight,
    onSurfaceVariant = VinylOnSurfaceVariantLight,
    outline = VinylOutlineLight,
    outlineVariant = VinylOutlineVariantLight,
    inverseSurface = VinylInverseSurfaceLight,
    onInverseSurface = VinylOnInverseSurfaceLight,
    inverseOnSurface = VinylInverseSurfaceLight,
    error = VinylErrorLight,
    onError = VinylOnErrorLight,
    errorContainer = VinylErrorContainerLight,
    onErrorContainer = VinylOnErrorContainerLight,
    surfaceContainerLowest = VinylSurfaceContainerLowestLight,
    surfaceContainerLow = VinylSurfaceContainerLowLight,
    surfaceContainer = VinylSurfaceContainerLight,
    surfaceContainerHigh = VinylSurfaceContainerHighLight,
    surfaceContainerHighest = VinylSurfaceContainerHighestLight,
)

@Composable
fun VinylTheme(
    themeMode: String = "dark",
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        "light" -> false
        "system" -> systemDark
        else -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = VinylTypography,
        content = content,
    )
}
