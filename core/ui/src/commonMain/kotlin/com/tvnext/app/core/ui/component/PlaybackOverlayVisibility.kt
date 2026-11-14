package com.tvnext.app.core.ui.component

import androidx.compose.runtime.*

/** Scoped to one AppShell. Disposed screens cannot leave progress banners hidden. */
@Stable
class PlaybackOverlayVisibility {
    private val panels = mutableStateMapOf<Any, Unit>()
    private val loadingVideos = mutableStateMapOf<Any, Unit>()
    val bottomPanelVisible: Boolean get() = panels.isNotEmpty()
    val videoLoading: Boolean get() = loadingVideos.isNotEmpty()

    internal fun update(owner: Any, bottomPanel: Boolean, loadingVideo: Boolean) {
        if (bottomPanel) panels[owner] = Unit else panels.remove(owner)
        if (loadingVideo) loadingVideos[owner] = Unit else loadingVideos.remove(owner)
    }
}

val LocalPlaybackOverlayVisibility = staticCompositionLocalOf<PlaybackOverlayVisibility?> { null }

@Composable
fun ReportPlaybackOverlays(bottomPanel: Boolean = false, loadingVideo: Boolean = false) {
    val visibility = LocalPlaybackOverlayVisibility.current
    val owner = remember { Any() }
    DisposableEffect(visibility, bottomPanel, loadingVideo) {
        visibility?.update(owner, bottomPanel, loadingVideo)
        onDispose { visibility?.update(owner, false, false) }
    }
}
