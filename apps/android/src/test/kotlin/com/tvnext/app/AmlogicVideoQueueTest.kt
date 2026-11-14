package com.tvnext.app

import android.app.Application
import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import com.tvnext.app.core.player.AudioOutputMode
import java.io.IOException
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,35],application=Application::class)
class AmlogicVideoQueueTest {
    private class Factory(var failure: Exception? = null) : MediaCodecAdapter.Factory {
        val calls=mutableListOf<MediaCodecAdapter.Configuration>()
        val adapter=Proxy.newProxyInstance(MediaCodecAdapter::class.java.classLoader,arrayOf(MediaCodecAdapter::class.java)) { proxy,method,args ->
            when(method.name) {
                "toString" -> "Fixture MediaCodecAdapter"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy===args?.firstOrNull()
                else -> error("No codec operation is needed for factory dispatch: ${method.name}")
            }
        } as MediaCodecAdapter
        override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
            calls+=configuration
            failure?.let{throw it}
            return adapter
        }
    }
    private fun factory(sdk: Int, defaults:Factory, async:Factory, sync:Factory): MediaCodecAdapter.Factory =
        Class.forName("com.tvnext.app.core.player.AmlogicMediaCodecAdapterFactory")
            .getDeclaredConstructor(Int::class.javaPrimitiveType,MediaCodecAdapter.Factory::class.java,
                MediaCodecAdapter.Factory::class.java,MediaCodecAdapter.Factory::class.java)
            .apply{isAccessible=true}.newInstance(sdk,defaults,async,sync) as MediaCodecAdapter.Factory

    private fun configuration(name:String="OMX.amlogic.hevc.decoder.awesome",mime:String="video/hevc",
        hardware:Boolean=true,software:Boolean=false):MediaCodecAdapter.Configuration {
        val codec=MediaCodecInfo.newInstance(name,mime,mime,null,hardware,software,true,false,false)
        val format=Format.Builder().setSampleMimeType(mime).setWidth(3840).setHeight(2160).setFrameRate(50f).build()
        return if(mime.startsWith("video/")) MediaCodecAdapter.Configuration.createForVideoDecoding(
            codec,MediaFormat.createVideoFormat(mime,3840,2160),format,null,null)
        else MediaCodecAdapter.Configuration.createForAudioDecoding(codec,MediaFormat.createAudioFormat(mime,48000,2),format,null,null)
    }
    @Test fun legacyAmlogicHardwareUsesAsyncWithTheOriginalUhdConfiguration() {
        for(sdk in listOf(23,28,30)) for(name in listOf("OMX.amlogic.hevc.decoder.awesome","OMX.amlogic.avc.decoder.awesome","c2.amlogic.hevc.decoder")) {
            val defaults=Factory();val async=Factory();val sync=Factory()
            val config=configuration(name)
            assertSame(async.adapter,factory(sdk,defaults,async,sync).createAdapter(config))
            assertSame(config,async.calls.single())
            assertTrue(defaults.calls.isEmpty());assertTrue(sync.calls.isEmpty())
            assertEquals(3840,config.mediaFormat.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(2160,config.mediaFormat.getInteger(MediaFormat.KEY_HEIGHT))
            assertEquals(50f,config.format.frameRate)
        }
    }
    @Test fun audioSoftwareOtherVendorsAndModernAndroidKeepMedia3Defaults() {
        val defaults=Factory();val async=Factory();val sync=Factory()
        for(sdk in listOf(22,28,31,35)) {
            val selection=factory(sdk,defaults,async,sync)
            val cases=mutableListOf(configuration("OMX.amlogic.aac.decoder","audio/mp4a-latm"),
                configuration("OMX.google.hevc.decoder",hardware=false,software=true),
                configuration("OMX.amlogic.hevc.decoder",hardware=false,software=true),
                configuration("c2.mtk.hevc.decoder"),configuration("OMX.MTK.VIDEO.DECODER.HEVC"))
            if(sdk !in 23..30)cases+=configuration()
            for(config in cases)assertSame(defaults.adapter,selection.createAdapter(config))
        }
        assertTrue(async.calls.isEmpty());assertTrue(sync.calls.isEmpty())
    }
    @Test fun failedAsyncInitializationRetriesTheSameHardwareOnceAndRemembersItsCompatibilityPath() {
        for(failure in listOf(IOException("unsupported callback"),IllegalStateException("vendor init failed"))) {
            val defaults=Factory();val async=Factory(failure);val sync=Factory()
            val selection=factory(28,defaults,async,sync);val config=configuration()
            repeat(2){assertSame(sync.adapter,selection.createAdapter(config))}
            assertEquals(1,async.calls.size);assertEquals(2,sync.calls.size)
            assertTrue(sync.calls.all{it===config});assertTrue(defaults.calls.isEmpty())
            val description=selection.javaClass.getDeclaredMethod("getVideoOutputDescription").invoke(selection) as String
            assertTrue(description.contains("sync fallback"))
        }
    }
    @Test fun aBrokenCodecDoesNotLoopAndPreservesBothInitializationErrors() {
        val asyncFailure=IOException("async init failed");val syncFailure=IllegalStateException("sync init failed")
        val defaults=Factory();val async=Factory(asyncFailure);val sync=Factory(syncFailure)
        try {
            factory(28,defaults,async,sync).createAdapter(configuration())
            fail("The original hardware error must reach Media3")
        } catch(failure:IllegalStateException) {
            assertSame(syncFailure,failure);assertSame(asyncFailure,failure.suppressed.single())
        }
        assertEquals(1,async.calls.size);assertEquals(1,sync.calls.size);assertTrue(defaults.calls.isEmpty())
    }
    @Test fun asyncFailureForOneCodecDoesNotDisableItForAnotherAmlogicDecoder() {
        val defaults=Factory();val async=Factory(IOException("HEVC callback unavailable"));val sync=Factory()
        val selection=factory(28,defaults,async,sync)
        assertSame(sync.adapter,selection.createAdapter(configuration()))
        async.failure=null
        assertSame(async.adapter,selection.createAdapter(configuration("OMX.amlogic.avc.decoder.awesome","video/avc")))
    }
    @Test fun rendererFactoryInstallsThisVideoPolicyInEveryAudioOutputMode() {
        val path=Class.forName("com.tvnext.app.core.player.AndroidVideoDecoderPath")
        val hardware=path.enumConstants.first{(it as Enum<*>).name=="HARDWARE"}
        val renderer=Class.forName("com.tvnext.app.core.player.AndroidAudioRenderersFactory")
        val constructor=renderer.getDeclaredConstructor(android.content.Context::class.java,path,AudioOutputMode::class.java)
            .apply{isAccessible=true}
        val method=renderer.getDeclaredMethod("getCodecAdapterFactory").apply{isAccessible=true}
        for(mode in AudioOutputMode.entries) {
            val instance=constructor.newInstance(RuntimeEnvironment.getApplication(),hardware,mode)
            val actual=method.invoke(instance)
            assertEquals("AmlogicMediaCodecAdapterFactory",actual.javaClass.simpleName)
            val video=ArrayList<androidx.media3.exoplayer.Renderer>()
            renderer.getDeclaredMethod("buildVideoRenderers",android.content.Context::class.java,Int::class.javaPrimitiveType,
                androidx.media3.exoplayer.mediacodec.MediaCodecSelector::class.java,Boolean::class.javaPrimitiveType,
                android.os.Handler::class.java,androidx.media3.exoplayer.video.VideoRendererEventListener::class.java,
                Long::class.javaPrimitiveType,ArrayList::class.java).apply{isAccessible=true}.invoke(instance,
                    RuntimeEnvironment.getApplication(),2,androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT,
                    true,android.os.Handler(android.os.Looper.getMainLooper()),
                    object:androidx.media3.exoplayer.video.VideoRendererEventListener{},5000L,video)
            assertEquals("TextureSafeVideoRenderer",video.single().javaClass.simpleName)
        }
    }
}
