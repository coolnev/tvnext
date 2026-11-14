package com.tvnext.app.core.player

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color

data class VideoViewport(val token: Any, val bounds: Rect, val clip: Rect, val controller: PlaybackController)

/** Viewports share one native video surface, owned by the app rather than a destination screen. */
@Stable
class VideoScene {
    private val viewports = linkedMapOf<Any, VideoViewport>()
    var viewport by mutableStateOf<VideoViewport?>(null)
        private set
    var origin by mutableStateOf(Offset.Zero)

    fun update(viewport: VideoViewport) {
        if (viewport.bounds.width <= 0 || viewport.bounds.height <= 0) return
        viewports[viewport.token] = viewport
        this.viewport = viewports.values.lastOrNull()
    }
    fun remove(token: Any) {
        viewports.remove(token)
        viewport = viewports.values.lastOrNull()
    }
}

val LocalVideoScene = staticCompositionLocalOf<VideoScene?> { null }

@Composable
fun videoSurfaceBackground(fallback: Color): Color = if (LocalVideoScene.current != null) Color.Transparent else fallback

/** API24+ SurfaceView synchronizes view animation and retains the native UHD video plane. */
fun useTextureForVideoTransition(animated:Boolean, android:Boolean):Boolean = animated && !android
