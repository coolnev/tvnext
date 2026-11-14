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
    @Test fun hdAnimationUsesTheOriginalTextureViewWhileUhdAndHdrKeepTheNativePlane() {
        val context=RuntimeEnvironment.getApplication()
        val constructor=Class.forName("com.tvnext.app.core.player.AndroidVideoViewport")
            .getDeclaredConstructor(Context::class.java,Boolean::class.javaPrimitiveType).apply {isAccessible=true}
        for(animated in listOf(false,true)) for(afr in listOf(false,true))
            for((w,h,hdr) in listOf(Triple(1920,1080,false),Triple(3840,2160,false),Triple(1920,1080,true))) {
            val texture=com.tvnext.app.core.player.useTextureForVideoTransition(animated,true,w,h,hdr)
            val viewport=constructor.newInstance(context,texture) as ViewGroup
            val playerView=viewport.getChildAt(0)
            val surface=playerView.javaClass.getMethod("getVideoSurfaceView").invoke(playerView)
            val expectedTexture=animated && w==1920 && !hdr
            assertEquals("Select the actual video view for ${w}x$h HDR=$hdr animation=$animated AFR=$afr",expectedTexture,surface is TextureView)
            if(!expectedTexture)assertTrue(surface is SurfaceView)
            viewport.layout(0,0,960,540);viewport.layout(700,16,924,142);viewport.layout(0,0,960,540)
            assertSame(surface,playerView.javaClass.getMethod("getVideoSurfaceView").invoke(playerView))
        }
    }
}
