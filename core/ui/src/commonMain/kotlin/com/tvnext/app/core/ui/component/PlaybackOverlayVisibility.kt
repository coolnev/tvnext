package com.tvnext.app.core.ui.component

import androidx.compose.runtime.*

/** Scoped to one AppShell. Disposed screens cannot leave progress banners hidden. */
@Stable
class PlaybackOverlayVisibility {
    private val panels = mutableStateMapOf<Any, Unit>()
    private val loadingVideos = mutableStateMapOf<Any, Unit>()
    private val contentOverlays = mutableStateMapOf<Any, Unit>()
    val bottomPanelVisible: Boolean get() = panels.isNotEmpty()
    val videoLoading: Boolean get() = loadingVideos.isNotEmpty()
    val contentOverlayVisible: Boolean get() = contentOverlays.isNotEmpty()

    internal fun update(owner: Any, bottomPanel: Boolean, loadingVideo: Boolean, contentOverlay: Boolean) {
        if (bottomPanel) panels[owner] = Unit else panels.remove(owner)
        if (loadingVideo) loadingVideos[owner] = Unit else loadingVideos.remove(owner)
        if (contentOverlay) contentOverlays[owner] = Unit else contentOverlays.remove(owner)
    }
}

val LocalPlaybackOverlayVisibility = staticCompositionLocalOf<PlaybackOverlayVisibility?> { null }

@Composable
fun ReportPlaybackOverlays(bottomPanel: Boolean = false, loadingVideo: Boolean = false, contentOverlay: Boolean = false) {
    val visibility = LocalPlaybackOverlayVisibility.current
    val owner = remember { Any() }
    DisposableEffect(visibility, bottomPanel, loadingVideo, contentOverlay) {
        visibility?.update(owner, bottomPanel, loadingVideo, contentOverlay)
        onDispose { visibility?.update(owner, false, false, false) }
    }
}
