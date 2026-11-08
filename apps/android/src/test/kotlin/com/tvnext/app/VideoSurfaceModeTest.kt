package com.tvnext.app

import android.app.Application
import android.content.Context
import android.view.SurfaceView
import android.view.TextureView
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28,35],application=Application::class)
class VideoSurfaceModeTest {
    @Test fun animationsUseTextureWhileTheDefaultUsesSurfaceAndResizingKeepsTheSameVideoView() {
        val context=RuntimeEnvironment.getApplication()
        val constructor=Class.forName("com.tvnext.app.core.player.AndroidVideoViewport")
            .getDeclaredConstructor(Context::class.java,Boolean::class.javaPrimitiveType).apply {isAccessible=true}
        for(animated in listOf(false,true)) {
            val viewport=constructor.newInstance(context,animated) as ViewGroup
            val playerView=viewport.getChildAt(0)
            val surface=playerView.javaClass.getMethod("getVideoSurfaceView").invoke(playerView)
            assertTrue(if(animated) surface is TextureView else surface is SurfaceView)
            viewport.layout(0,0,960,540);viewport.layout(700,16,924,142);viewport.layout(0,0,960,540)
            assertSame(surface,playerView.javaClass.getMethod("getVideoSurfaceView").invoke(playerView))
        }
    }
}
