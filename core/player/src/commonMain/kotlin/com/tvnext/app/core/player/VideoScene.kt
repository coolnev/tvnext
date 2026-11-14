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
    var layoutRevision by mutableLongStateOf(0L)
        private set

    fun update(viewport: VideoViewport) {
        if (viewport.bounds.width <= 0 || viewport.bounds.height <= 0) return
        viewports[viewport.token] = viewport
        this.viewport = viewports.values.lastOrNull()
        layoutRevision++
    }
    fun remove(token: Any) {
        viewports.remove(token)
        viewport = viewports.values.lastOrNull()
    }
}

val LocalVideoScene = staticCompositionLocalOf<VideoScene?> { null }

@Composable
fun videoSurfaceBackground(fallback: Color): Color = if (LocalVideoScene.current != null) Color.Transparent else fallback

/** Restore composited animation for SDR up to Full HD; keep the native UHD/HDR/protected plane. */
fun useTextureForVideoTransition(animated:Boolean, android:Boolean, width:Int=0, height:Int=0,
    nativeRequired:Boolean=false):Boolean = animated && (!android || (!nativeRequired &&
    width>0 && height>0 && maxOf(width,height)<=1920 && minOf(width,height)<=1080))

/** Missing metadata during a channel switch must not swap surfaces twice. */
class VideoSurfaceSelection(initialTexture:Boolean=false) {
    var usesTextureView:Boolean=initialTexture
        private set
    fun update(animated:Boolean, android:Boolean, facts:PlayerDiagnostics?):Boolean {
        if (!animated || !android || facts?.nativeVideoSurfaceRequired==true ||
            (facts!=null && facts.videoWidth>0 && facts.videoHeight>0)) {
            usesTextureView=useTextureForVideoTransition(animated,android,facts?.videoWidth ?: 0,
                facts?.videoHeight ?: 0,facts?.nativeVideoSurfaceRequired==true)
        }
        return usesTextureView
    }
}
