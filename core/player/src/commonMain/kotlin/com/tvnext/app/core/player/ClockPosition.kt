package com.tvnext.app.core.player

/**
 * On-screen clock position over the video area (Windows setting).
 * The clock renders inside the existing overlay mechanism: Compose content on
 * top of the video in software mode, a dedicated overlay window in hardware
 * (D3D11) mode. No separate always-on-top window is ever created.
 */
enum class ClockPosition {
    OFF,
    TOP_LEFT,
    TOP_RIGHT,
}
