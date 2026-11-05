package com.tvnext.app.core.ui.theme

import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

/**
 * Uses the Android system's `sans-serif-condensed` family (Roboto Condensed)
 * as the primary typeface, falling back to `sans-serif` if a device ever
 * removes it. System fonts resolve synchronously and never trigger text
 * reflow, and they add nothing to the APK.
 */
actual fun compactFontFamily(): FontFamily = FontFamily(
    Font(DeviceFontFamilyName("sans-serif-condensed")),
    Font(DeviceFontFamilyName("sans-serif")),
)
