package com.tvnext.app.core.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged

/**
 * Desktop video surface.
 *
 * Two rendering paths (Sprint 5):
 * - **Software / callback**: frames produced by the libVLC backend
 *   ([DesktopPlaybackController]) are published as
 *   [androidx.compose.ui.graphics.ImageBitmap]s and drawn with [Image]
 *   (ContentScale.Fit), staying inside the Compose window.
 * - **Hardware / HWND**: the controller's AWT [java.awt.Canvas] (which libVLC
 *   renders into via `libvlc_media_player_set_hwnd` + D3D11) is embedded with
 *   [SwingPanel]. Compose overlays (spinner, error, channel name, controls)
 *   keep drawing above the native surface thanks to interop blending
 *   (`compose.interop.blending=true`, set by the application).
 *
 * The surface attaches/detaches the controller's native rendering pipeline on
 * composition enter/leave.
 *
 * The canvas is keyed on [DesktopPlaybackController.hardwareCanvasRevision]:
 * an in-place HARDWARE repair bumps the revision, which re-creates the canvas
 * (a brand-new HWND + D3D11 swapchain) and remounts the player on it — exactly
 * like an F11 window round-trip. Re-creating the swapchain on the SAME HWND is
 * what D3D11 cannot do, which is why the picture stayed white after big seeks.
 */
@Composable
actual fun PlayerSurface(
    controller: PlaybackController,
    modifier: Modifier,
) {
    val desktopController = controller as? DesktopVideoSurface
    if (desktopController == null) {
        Box(modifier = modifier.background(Color.Black))
        return
    }

    // The canvas lives in the CONTROLLER (DesktopVideoSurface.videoCanvas), NOT
    // in this composable. Tab switches (Channels ↔ Media) must NOT re-create it
    // — otherwise every tab change restarts the whole player (a blocking network
    // loadfile) and can freeze the UI. The controller swaps in a fresh canvas
    // only when the old one becomes undisplayable (window/F11 round-trip).
    val canvas = desktopController.videoCanvas

    DisposableEffect(desktopController, canvas) {
        desktopController.attachSurface(canvas)
        desktopController.onSurfaceAttached()
        onDispose {
            desktopController.onSurfaceDetached()
            desktopController.detachSurface(canvas)
        }
    }

    val hardwareActive by desktopController.hardwareSurfaceActive.collectAsState()

    if (hardwareActive) {
        // NOTE: no background modifier here — with interop blending enabled the
        // Compose canvas is drawn ABOVE the native video, so painting a black
        // background on this panel would hide the HWND-rendered video.
        SwingPanel(
            factory = { canvas },
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    val frame by desktopController.frames.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size ->
                desktopController.updateSurfaceSize(size.width, size.height)
            },
    ) {
        frame?.let { bitmap ->
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
