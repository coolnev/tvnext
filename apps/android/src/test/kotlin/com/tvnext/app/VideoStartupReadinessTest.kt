package com.tvnext.app

import android.app.Application
import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.Format
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.*
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.video.*
import com.tvnext.app.core.player.AudioOutputMode
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28,35],application=Application::class)
class VideoStartupReadinessTest {
    @Test fun audioIsNotAllowedToRunFiveSecondsAheadOfAnUnreadyVideoRenderer() {
        val context=RuntimeEnvironment.getApplication()
        val path=Class.forName("com.tvnext.app.core.player.AndroidVideoDecoderPath")
        val hardware=path.enumConstants!!.first{(it as Enum<*>).name=="HARDWARE"}
        val factoryClass=Class.forName("com.tvnext.app.core.player.AndroidAudioRenderersFactory")
        val factory=factoryClass.getDeclaredConstructor(Context::class.java,path,AudioOutputMode::class.java)
            .apply{isAccessible=true}.newInstance(context,hardware,AudioOutputMode.AUTO)
        val joining=DefaultRenderersFactory::class.java.getDeclaredField("allowedVideoJoiningTimeMs").apply{isAccessible=true}.getLong(factory)
        val out=ArrayList<Renderer>()
        factoryClass.getDeclaredMethod("buildVideoRenderers",Context::class.java,Int::class.javaPrimitiveType,
            MediaCodecSelector::class.java,Boolean::class.javaPrimitiveType,Handler::class.java,
            VideoRendererEventListener::class.java,Long::class.javaPrimitiveType,ArrayList::class.java)
            .apply{isAccessible=true}.invoke(factory,context,2,MediaCodecSelector.DEFAULT,true,Handler(Looper.getMainLooper()),
                object:VideoRendererEventListener{},joining,out)
        val renderer=out.filterIsInstance<MediaCodecVideoRenderer>().single()
        val texture=SurfaceTexture(0);val surface=Surface(texture)
        try {
            renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT,surface)
            val codec=Proxy.newProxyInstance(MediaCodecAdapter::class.java.classLoader,arrayOf(MediaCodecAdapter::class.java)) {_,m,_->
                if(m.name=="release")null else error("Unexpected codec operation ${m.name}")
            }
            val stream=Proxy.newProxyInstance(SampleStream::class.java.classLoader,arrayOf(SampleStream::class.java)) {_,m,_->
                if(m.name=="isReady")true else if(m.name=="getFlags")0 else error("Unexpected sample operation ${m.name}")
            }
            BaseRenderer::class.java.getDeclaredField("stream").apply{isAccessible=true}.set(renderer,stream)
            BaseRenderer::class.java.getDeclaredField("readingPositionUs").apply{isAccessible=true}.setLong(renderer,0L)
            for((name,value) in listOf("codec" to codec,"inputFormat" to Format.Builder().setSampleMimeType("video/avc").build()))
                MediaCodecRenderer::class.java.getDeclaredField(name).apply{isAccessible=true}.set(renderer,value)
            MediaCodecVideoRenderer::class.java.getDeclaredMethod("onPositionReset",Long::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(renderer,0L,true,false)
            assertFalse("Video without its first frame must not claim readiness during the 5000 ms joining grace",renderer.isReady)
            val control=MediaCodecVideoRenderer::class.java.getDeclaredField("videoFrameReleaseControl").apply{isAccessible=true}.get(renderer)
            control.javaClass.getMethod("onFrameReleasedIsFirstFrame").invoke(control)
            assertTrue("Once the first frame is available, audio and video can start together",renderer.isReady)
        } finally {surface.release();texture.release()}
    }
}
