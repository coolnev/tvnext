package com.tvnext.app.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Accent color schemes for the whole app (Windows setting, persisted).
 * The theme is built from the accent primary + the dark/light neutrals, so no
 * component hardcodes its own colors.
 */
enum class AccentColor {
    GREEN,
    BLUE,
    ORANGE,
    PURPLE,
}

internal data class AccentPalette(
    val primary: Color,
    val onPrimary: Color,
    val secondary: Color,
    val onSecondary: Color,
    val tertiary: Color,
    val onTertiary: Color,
)

internal fun AccentColor.palette(): AccentPalette = when (this) {
    AccentColor.GREEN -> AccentPalette(
        primary = Color(0xFF3DDC97),
        onPrimary = Color(0xFF00210F),
        secondary = Color(0xFF58A6FF),
        onSecondary = Color(0xFF002B57),
        tertiary = Color(0xFFE3B341),
        onTertiary = Color(0xFF3A2A00),
    )
    AccentColor.BLUE -> AccentPalette(
        primary = Color(0xFF58A6FF),
        onPrimary = Color(0xFF002B57),
        secondary = Color(0xFF3DDC97),
        onSecondary = Color(0xFF00210F),
        tertiary = Color(0xFFE3B341),
        onTertiary = Color(0xFF3A2A00),
    )
    AccentColor.ORANGE -> AccentPalette(
        primary = Color(0xFFF0883E),
        onPrimary = Color(0xFF3A1A00),
        secondary = Color(0xFF58A6FF),
        onSecondary = Color(0xFF002B57),
        tertiary = Color(0xFF3DDC97),
        onTertiary = Color(0xFF00210F),
    )
    AccentColor.PURPLE -> AccentPalette(
        primary = Color(0xFFB388FF),
        onPrimary = Color(0xFF2A0053),
        secondary = Color(0xFF58A6FF),
        onSecondary = Color(0xFF002B57),
        tertiary = Color(0xFF3DDC97),
        onTertiary = Color(0xFF00210F),
    )
}

/** Light variants of the accent colors (darker for light backgrounds). */
internal fun AccentColor.lightPalette(): AccentPalette = when (this) {
    AccentColor.GREEN -> AccentPalette(
        primary = Color(0xFF006B3F),
        onPrimary = Color(0xFFFFFFFF),
        secondary = Color(0xFF005BAC),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFF7A5800),
        onTertiary = Color(0xFFFFFFFF),
    )
    AccentColor.BLUE -> AccentPalette(
        primary = Color(0xFF005BAC),
        onPrimary = Color(0xFFFFFFFF),
        secondary = Color(0xFF006B3F),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFF7A5800),
        onTertiary = Color(0xFFFFFFFF),
    )
    AccentColor.ORANGE -> AccentPalette(
        primary = Color(0xFF9A4B00),
        onPrimary = Color(0xFFFFFFFF),
        secondary = Color(0xFF005BAC),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFF006B3F),
        onTertiary = Color(0xFFFFFFFF),
    )
    AccentColor.PURPLE -> AccentPalette(
        primary = Color(0xFF5E35B1),
        onPrimary = Color(0xFFFFFFFF),
        secondary = Color(0xFF005BAC),
        onSecondary = Color(0xFFFFFFFF),
        tertiary = Color(0xFF006B3F),
        onTertiary = Color(0xFFFFFFFF),
    )
}
