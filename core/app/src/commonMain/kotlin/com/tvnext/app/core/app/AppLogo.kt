package com.tvnext.app.core.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The product logo in the top bar. Platform-specific resource: an SVG on
 * desktop (crisp vector scaling) and a PNG on Android (Android cannot render
 * SVG drawables from Compose resources).
 */
@Composable
expect fun AppLogo(modifier: Modifier = Modifier)
