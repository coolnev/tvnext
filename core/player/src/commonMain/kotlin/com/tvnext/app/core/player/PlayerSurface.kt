package com.tvnext.app.core.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Platform-specific video surface bound to a [PlaybackController].
 * The concrete rendering backend is provided per platform (desktop/android).
 */
@Composable
expect fun PlayerSurface(
    controller: PlaybackController,
    modifier: Modifier,
)
