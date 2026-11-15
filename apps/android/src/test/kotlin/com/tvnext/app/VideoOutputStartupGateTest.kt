package com.tvnext.app

import android.app.Application
import android.graphics.SurfaceTexture
import android.view.Surface
import android.os.Handler
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.common.util.Clock
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.mediacodec.*
import androidx.media3.exoplayer.source.*
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28,35],application=Application::class)
class VideoOutputStartupGateTest {
    private class Source(val format:Format):SampleStream {
        var sentFormat=false;var skipped=0;var consumed=0
        override fun isReady()=true
        override fun maybeThrowError() {}
        override fun skipData(positionUs:Long):Int {skipped++;return 1}
        override fun readData(holder:FormatHolder,buffer:DecoderInputBuffer,flags:Int):Int {
            if(!sentFormat){sentFormat=true;holder.format=format;return C.RESULT_FORMAT_READ}
            buffer.timeUs=0L;buffer.setFlags(C.BUFFER_FLAG_KEY_FRAME)
            if(flags and SampleStream.FLAG_PEEK==0)consumed++
            return C.RESULT_BUFFER_READ
        }
    }
    private class AdapterReached:Error("Codec adapter creation boundary")
    @Test fun theFirstKeyframeAndAudioClockWaitForTheActualMatchingSurfaceAcrossStartupAndHdUhdHdrChanges() {
        val hd=Format.Builder().setSampleMimeType("video/avc").setWidth(1280).setHeight(720).build()
        val uhd=hd.buildUpon().setSampleMimeType("video/hevc").setWidth(3840).setHeight(2160).build()
        val hdr=hd.buildUpon().setColorInfo(ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).build()).build()
        for((initialTexture,format,desiredTexture) in listOf(Triple<Boolean?,Format,Boolean>(false,hd,true),
            Triple(true,uhd,false),Triple(true,hdr,false),Triple(null,hd,true),Triple(true,hd,true),Triple(false,uhd,false))) {
            val policyClass=Class.forName("com.tvnext.app.core.player.AndroidVideoOutputPolicy")
            val policy=policyClass.getDeclaredConstructor(Boolean::class.javaPrimitiveType,Function0::class.java)
                .apply{isAccessible=true}.newInstance(true,{true})
            policyClass.getDeclaredMethod("setCoordinateOutput",Boolean::class.javaPrimitiveType).invoke(policy,true)
            fun type(texture:Boolean) {policyClass.getDeclaredMethod("setTextureOutput",Boolean::class.javaPrimitiveType).invoke(policy,texture)}
            val codec=MediaCodecInfo.newInstance("OMX.realtek.video.decoder",format.sampleMimeType!!,format.sampleMimeType!!,
                null,true,false,true,false,false)
            var configured:MediaCodecAdapter.Configuration?=null
            var observedFormat:Format?=null
            val reached=AdapterReached()
            val builder=MediaCodecVideoRenderer.Builder(RuntimeEnvironment.getApplication())
                .setMediaCodecSelector {_,_,_->listOf(codec)}
                .setCodecAdapterFactory {configuration->configured=configuration;throw reached}
                .setEventHandler(Handler(Looper.getMainLooper()))
                .setEventListener(object:VideoRendererEventListener {
                    override fun onVideoInputFormatChanged(format:Format,evaluation:DecoderReuseEvaluation?) {observedFormat=format}
                })
            val rendererClass=Class.forName("com.tvnext.app.core.player.TextureSafeVideoRenderer")
            val renderer=rendererClass.getDeclaredConstructor(MediaCodecVideoRenderer.Builder::class.java,policyClass)
                .apply{isAccessible=true}.newInstance(builder,policy) as MediaCodecVideoRenderer
            val source=Source(format)
            val st=SurfaceTexture(0);val old=Surface(st);val target=Surface(st)
            try {
                renderer.init(0,PlayerId.UNSET,Clock.DEFAULT)
                if(initialTexture!=null){type(initialTexture);renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT,old)}
                renderer.enable(RendererConfiguration.DEFAULT,arrayOf(format),source,0L,true,true,0L,0L,MediaSource.MediaPeriodId("new-channel"))
                if(initialTexture==desiredTexture) {
                    try {renderer.render(0L,0L);fail("A matching output must initialize immediately")}
                    catch(error:AdapterReached){assertSame(reached,error)}
                    assertSame(old,configured!!.surface);assertEquals(0,source.skipped);assertEquals(0,source.consumed)
                    continue
                }
                renderer.render(0L,0L)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("The UI must receive the format while codec creation is waiting",format,observedFormat)
                assertNull("Never configure against the previous channel's view",configured)
                assertFalse("Audio must not start while video waits for its output",renderer.isReady)
                assertEquals("The first IDR must not be skipped during UI handoff",0,source.skipped)
                assertEquals(0,source.consumed)
                type(desiredTexture)
                renderer.render(0L,0L)
                assertNull("A Compose policy flag is not proof of Surface attachment",configured)
                assertFalse(renderer.isReady);assertEquals(0,source.skipped);assertEquals(0,source.consumed)
                renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT,target)
                try {renderer.render(0L,0L);fail("The codec should now initialize on the correct surface")}
                catch(error:AdapterReached){assertSame(reached,error)}
                assertNotNull(configured);assertSame(target,configured!!.surface)
                assertEquals(0,source.skipped);assertEquals(0,source.consumed)
            } finally {
                if(renderer.state==Renderer.STATE_ENABLED)renderer.disable()
                renderer.reset();old.release();target.release();st.release()
            }
        }
    }
}
