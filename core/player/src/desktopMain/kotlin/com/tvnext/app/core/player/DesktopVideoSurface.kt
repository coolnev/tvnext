package com.tvnext.app.core.player

import androidx.compose.ui.graphics.ImageBitmap
import java.awt.Canvas
import kotlinx.coroutines.flow.StateFlow

/**
 * Contract between the desktop video surface ([PlayerSurface]) and the actual
 * playback backend. Implemented by both the libVLC backend
 * ([DesktopPlaybackController]) and the libmpv backend
 * ([MpvPlaybackController]), so the Compose surface stays backend-agnostic.
 */
internal interface DesktopVideoSurface {
    /** Hardware path active (video renders into a native HWND, not ImageBitmaps). */
    val hardwareSurfaceActive: StateFlow<Boolean>

    /** Software frames (callback path); null when not producing Compose frames. */
    val frames: StateFlow<ImageBitmap?>

    /**
     * Bumped to force [PlayerSurface] to re-create its AWT canvas (new HWND) —
     * the in-place repair for a lost D3D picture.
     */
    val hardwareCanvasRevision: StateFlow<Long>

    /** The AWT canvas currently backing the video (anchor for overlay windows). */
    val videoCanvas: Canvas

    fun attachSurface(canvas: Canvas)
    fun detachSurface(canvas: Canvas)
    fun onSurfaceAttached()
    fun onSurfaceDetached()
    fun updateSurfaceSize(width: Int, height: Int)
}

/**
 * Playback settings applied from the app Settings screen (non-disruptive: both
 * take effect on the next play()). Public because the desktop application
 * (apps:desktop) wires the Settings screen into the playback backend.
 */
interface PlaybackSettingsSink {
    fun updateDecodeMode(mode: VideoDecodeMode)
    fun updateNetworkCachingMs(ms: Long)
}
