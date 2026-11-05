package com.tvnext.app.core.player

import androidx.compose.runtime.Composable

/**
 * Android actual: video is rendered by Media3/ExoPlayer inside Compose, so
 * overlays are ordinary Compose content drawn directly above the player
 * surface — no separate overlay windows are needed.
 */
@Composable
actual fun HardwareOverlayHost(
    controller: PlaybackController,
    title: @Composable () -> Unit,
    status: @Composable () -> Unit,
    diagnostics: @Composable () -> Unit,
    showTitle: Boolean,
    clock: (@Composable () -> Unit)?,
    clockPosition: ClockPosition,
    showDiagnostics: Boolean,
) {
    // No-op on Android: regular Compose overlays already work.
}
