package com.tvnext.app.core.ui.theme

import androidx.compose.ui.text.font.FontFamily

/**
 * Narrow/compact font family used for the Android TV typography so more text
 * fits in lists (channel names, EPG, media cards). Android resolves the
 * built-in `sans-serif-condensed` family (Roboto Condensed, supports Cyrillic);
 * other platforms fall back to the default font.
 */
expect fun compactFontFamily(): FontFamily
