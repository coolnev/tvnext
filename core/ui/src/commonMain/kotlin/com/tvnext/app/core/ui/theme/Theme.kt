package com.tvnext.app.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import com.tvnext.app.core.common.platform.isAndroidPlatform

object TVNextDimensLocal {
    val default = TVNextDimens
}

val LocalTVNextDimens = staticCompositionLocalOf { TVNextDimensLocal.default }

/**
 * Single design-token entry point: the whole app theme (dark/light + accent).
 * All screens read `MaterialTheme.colorScheme`, so a theme change is applied
 * app-wide without touching individual components.
 */
@Composable
fun TVNextTheme(
    darkTheme: Boolean = true,
    accentColor: AccentColor = AccentColor.GREEN,
    panelTransparencyPercent: Int = 20,
    content: @Composable () -> Unit,
) {
    val baseScheme = buildColorScheme(darkTheme = darkTheme, accentColor = accentColor)
    val panelAlpha = 1f - panelTransparencyPercent.coerceIn(0, 80) / 100f
    val colorScheme = baseScheme.copy(
        surface = baseScheme.surface.copy(alpha = panelAlpha),
        surfaceVariant = baseScheme.surfaceVariant.copy(alpha = panelAlpha),
    )
    val dimens = LocalTVNextDimens.current

    CompositionLocalProvider(
        LocalTVNextDimens provides dimens,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            // Android TV (especially 4K): denser typography so lists fit more
            // rows; desktop keeps the larger default sizes.
            typography = if (isAndroidPlatform()) {
                TVNextTypography.AndroidCompact
            } else {
                TVNextTypography.Default
            },
            shapes = TVNextShapes.Default,
            content = content,
        )
    }
}

@Composable
private fun buildColorScheme(darkTheme: Boolean, accentColor: AccentColor) =
    if (darkTheme) darkScheme(accentColor) else lightScheme(accentColor)

private fun darkScheme(accent: AccentColor) = darkColorScheme(
    primary = accent.palette().primary,
    onPrimary = accent.palette().onPrimary,
    secondary = accent.palette().secondary,
    onSecondary = accent.palette().onSecondary,
    tertiary = accent.palette().tertiary,
    onTertiary = accent.palette().onTertiary,
    background = TVNextColors.DarkBackground,
    onBackground = TVNextColors.DarkOnBackground,
    surface = TVNextColors.DarkSurface,
    onSurface = TVNextColors.DarkOnSurface,
    surfaceVariant = TVNextColors.DarkSurfaceVariant,
    error = TVNextColors.DarkError,
    outline = TVNextColors.DarkOutline,
    outlineVariant = TVNextColors.DarkOutlineVariant,
)

private fun lightScheme(accent: AccentColor) = lightColorScheme(
    primary = accent.lightPalette().primary,
    onPrimary = accent.lightPalette().onPrimary,
    secondary = accent.lightPalette().secondary,
    onSecondary = accent.lightPalette().onSecondary,
    tertiary = accent.lightPalette().tertiary,
    onTertiary = accent.lightPalette().onTertiary,
    background = TVNextColors.LightBackground,
    onBackground = TVNextColors.LightOnBackground,
    surface = TVNextColors.LightSurface,
    onSurface = TVNextColors.LightOnSurface,
    surfaceVariant = TVNextColors.LightSurfaceVariant,
    error = TVNextColors.LightError,
    outline = TVNextColors.LightOutline,
    outlineVariant = TVNextColors.LightOutlineVariant,
)
