package com.tvnext.app.core.player

import androidx.compose.runtime.Composable

/**
 * Renders player overlays (channel name, loading/error status, diagnostics,
 * optional clock) ON TOP of the native hardware video surface.
 *
 * On desktop (libVLC D3D11 HWND video) Compose content inside the main window
 * cannot draw above the video — the HWND child window always covers it. The
 * desktop actual implementation shows the overlays in SEPARATE opaque top-level
 * windows (JWindow + ComposePanel) positioned over the video area, which DO
 * render above D3D11 video (verified experimentally, Sprint 7). On Android the
 * overlays are ordinary Compose content, so this is a no-op.
 *
 * The three lambdas provide the overlay content; the implementation decides
 * visibility from the player state (loading/error/ended → status; diagnostics
 * only when present). [showTitle] gates the title plaque (the channel name is
 * shown only briefly after a channel switch), [clock] / [clockPosition] render
 * the optional on-screen clock in a corner of the video area.
 */
@Composable
expect fun HardwareOverlayHost(
    controller: PlaybackController,
    title: @Composable () -> Unit,
    status: @Composable () -> Unit,
    diagnostics: @Composable () -> Unit,
    showTitle: Boolean = true,
    clock: (@Composable () -> Unit)? = null,
    clockPosition: ClockPosition = ClockPosition.OFF,
    /**
     * Whether the diagnostics plaque is shown at all (global toggle, key I).
     * The visibility is driven here, NOT by returning an empty diagnostic
     * content (which was unreliable).
     */
    showDiagnostics: Boolean = true,
)
