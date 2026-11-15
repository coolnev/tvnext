package com.tvnext.app

import android.app.Application
import android.content.Context
import android.app.Activity
import android.view.View
import android.view.KeyEvent
import android.widget.FrameLayout
import android.view.SurfaceView
import android.view.TextureView
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28,35],application=Application::class)
class VideoSurfaceModeTest {
    @Test fun nativeVideoCannotTakeTheFocusOrConsumeTheFirstRemoteRightPress() {
        for(texture in listOf(false,true)) {
            val activity=Robolectric.buildActivity(Activity::class.java).setup().visible()
            try {
                val context=activity.get()
                val constructor=Class.forName("com.tvnext.app.core.player.AndroidVideoViewport")
                    .getDeclaredConstructor(Context::class.java,Boolean::class.javaPrimitiveType).apply{isAccessible=true}
                val viewport=constructor.newInstance(context,texture) as ViewGroup
                val root=FrameLayout(context)
                var rights=0
                val controls=View(context).apply {
                    isFocusable=true;isFocusableInTouchMode=true
                    setOnKeyListener {_,code,event->
                        if(code==KeyEvent.KEYCODE_DPAD_RIGHT){if(event.action==KeyEvent.ACTION_DOWN)rights++;true}else false
                    }
                }
                root.addView(controls,FrameLayout.LayoutParams(50,50))
                root.addView(viewport,FrameLayout.LayoutParams(960,540))
                context.setContentView(root)
                root.measure(View.MeasureSpec.makeMeasureSpec(960,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY))
                root.layout(0,0,960,540)
                assertTrue(controls.requestFocus())
                // Reattachment/vendor code may change the inner view's flags;
                // the video host must still keep key ownership in Compose.
                viewport.getChildAt(0).apply{isFocusable=true;isFocusableInTouchMode=true;requestFocus()}
                context.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT))
                context.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT))
                assertEquals("The first RIGHT must reach controls for TextureView=$texture",1,rights)
                assertTrue(controls.hasFocus())
            } finally {activity.pause().stop().destroy()}
        }
    }
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
