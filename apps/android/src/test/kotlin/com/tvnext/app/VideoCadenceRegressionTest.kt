package com.tvnext.app

import android.app.Application
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.*
import androidx.media3.common.util.Clock
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.mediacodec.*
import androidx.media3.exoplayer.source.*
import androidx.media3.exoplayer.video.*
import com.tvnext.app.core.iptv.archive.ArchiveRepository
import com.tvnext.app.core.iptv.data.ChannelsRepository
import com.tvnext.app.core.player.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlin.math.round
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,35], application=Application::class)
class VideoCadenceRegressionTest {
    private fun controller():AndroidPlaybackController {
        val channels=Proxy.newProxyInstance(ChannelsRepository::class.java.classLoader,arrayOf(ChannelsRepository::class.java)){_,m,_->
            if(m.name=="getChannels")emptyList<com.tvnext.app.core.iptv.domain.Channel>() else error(m.name)} as ChannelsRepository
        val archive=Proxy.newProxyInstance(ArchiveRepository::class.java.classLoader,arrayOf(ArchiveRepository::class.java)){_,m,_->error(m.name)} as ArchiveRepository
        return AndroidPlaybackController(RuntimeEnvironment.getApplication(),channels,archive)
    }
    private fun field(c:AndroidPlaybackController,name:String)=c.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(c)
    private fun startSession(c:AndroidPlaybackController) {
        // Keep the controller's real ExoPlayer idle: this test feeds the separate real renderer,
        // so a missing network/file source must not race it into ERROR/retry state.
        c.javaClass.getDeclaredMethod("ensurePlayer",PlaybackContentKind::class.java).apply {isAccessible=true}
            .invoke(c,PlaybackContentKind.LIVE)
        @Suppress("UNCHECKED_CAST")
        (field(c,"_state") as MutableStateFlow<PlayerState>).value=PlayerState(
            item=PlaybackItem("a","A","file:///cadence.ts"),state=PlaybackState.LOADING)
        c.javaClass.getDeclaredMethod("resetVideoSignal").apply {isAccessible=true}.invoke(c)
    }
    private val event=AnalyticsListener.EventTime(0,Timeline.EMPTY,0,null,0,Timeline.EMPTY,0,null,0,0)
    private fun formatChanged(c:AndroidPlaybackController,format:Format,sourceId:String?=null) {
        val sourceEvent=if(sourceId==null)event else {
            val timeline=SinglePeriodTimeline(60_000_000L,true,true,false,null,
                MediaItem.Builder().setUri("file:///cadence.ts").setMediaId(sourceId).build())
            AnalyticsListener.EventTime(0,timeline,0,null,0,timeline,0,null,0,0)
        }
        (field(c,"analyticsListener") as AnalyticsListener).onVideoInputFormatChanged(sourceEvent,format,null)
    }
    private class Source(val base:Format,val fps:Double,val bFrames:Boolean):SampleStream {
        var count=0;var limit=240;var headerFor=-1
        override fun isReady()=true
        override fun maybeThrowError() {}
        override fun skipData(positionUs:Long):Int = error("Cadence sampling must not skip input")
        override fun readData(holder:FormatHolder,buffer:DecoderInputBuffer,flags:Int):Int {
            if(count>=limit) return C.RESULT_NOTHING_READ
            if(count%12==0 && headerFor!=count) {
                headerFor=count
                holder.format=base.buildUpon().setId("segment-$count").setAverageBitrate(1_000_000+count).build()
                return C.RESULT_FORMAT_READ
            }
            val index=if(bFrames)count/4*4+intArrayOf(0,3,1,2)[count%4] else count
            buffer.timeUs=round(index*1000.0/fps).toLong()*1000L
            buffer.setFlags(C.BUFFER_FLAG_KEY_FRAME)
            if(flags and SampleStream.FLAG_OMIT_SAMPLE_DATA==0) {buffer.ensureSpaceForWrite(8);buffer.data!!.putLong(0L)}
            if(flags and SampleStream.FLAG_PEEK==0)count++
            return C.RESULT_BUFFER_READ
        }
    }
    /** Real Media3 input/format handling with an adapter that accepts input but emits NO output. */
    private inner class Pipeline(val c:AndroidPlaybackController,format:Format,fps:Double,bFrames:Boolean):AutoCloseable {
        private val sourceId=c.state.value.item!!.id
        val source=Source(format,fps,bFrames)
        private val texture=SurfaceTexture(0)
        private val surface=Surface(texture)
        val renderer:MediaCodecVideoRenderer
        var queued=0
        init {
            val policy=field(c,"videoOutputPolicy")!!
            val codec=MediaCodecInfo.newInstance("OMX.realtek.video.decoder",format.sampleMimeType!!,format.sampleMimeType!!,
                null,true,false,true,false,false)
            val input=ByteBuffer.allocate(4096)
            val adapter=Proxy.newProxyInstance(MediaCodecAdapter::class.java.classLoader,arrayOf(MediaCodecAdapter::class.java)){_,method,args->
                when(method.name) {
                    "dequeueInputBufferIndex"->if(source.count<source.limit)0 else -1
                    "getInputBuffer"->input.apply { clear() }
                    "queueInputBuffer"->{queued++;Unit}
                    "useInputBuffer"->{(args!![0] as Runnable).run();Unit}
                    "dequeueOutputBufferIndex"->-1
                    "needsReconfiguration","registerOnBufferAvailableListener"->false
                    "setVideoScalingMode","setParameters","setOnFrameRenderedListener","release","flush"->Unit
                    else->error("Unexpected adapter operation ${method.name}")
                }
            } as MediaCodecAdapter
            val builder=MediaCodecVideoRenderer.Builder(RuntimeEnvironment.getApplication())
                .setMediaCodecSelector {_,_,_->listOf(codec)}
                .setCodecAdapterFactory { adapter }
                .setEventHandler(Handler(Looper.getMainLooper()))
                .setEventListener(object:VideoRendererEventListener {
                    override fun onVideoInputFormatChanged(format:Format,evaluation:DecoderReuseEvaluation?) {formatChanged(c,format,sourceId)}
                })
            val type=Class.forName("com.tvnext.app.core.player.TextureSafeVideoRenderer")
            renderer=type.getDeclaredConstructor(MediaCodecVideoRenderer.Builder::class.java,policy.javaClass)
                .apply { isAccessible=true }.newInstance(builder,policy) as MediaCodecVideoRenderer
            renderer.init(0,PlayerId.UNSET,Clock.DEFAULT)
            renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT,surface)
            renderer.enable(RendererConfiguration.DEFAULT,arrayOf(format),source,0L,true,true,0L,0L,MediaSource.MediaPeriodId("cadence"))
        }
        fun feed() { repeat(12) {renderer.render(0L,0L)};assertEquals(source.limit,queued) }
        override fun close() {
            if(renderer.state==Renderer.STATE_ENABLED)renderer.disable()
            renderer.reset();surface.release();texture.release()
        }
    }
    private fun hevc(nominal:Float=Format.NO_VALUE.toFloat())=Format.Builder().setSampleMimeType("video/hevc")
        .setWidth(1280).setHeight(720).setFrameRate(nominal).build()

    @Test fun hevcCadenceSurvivesBFramesRoundedPtsAndShortHlsHeadersEvenWithoutDecodedOutput() {
        val c=controller()
        try {
            startSession(c)
            Pipeline(c,hevc(),24000.0/1001,true).use { pipe ->
                pipe.feed();shadowOf(Looper.getMainLooper()).idle()
                assertEquals(240,pipe.queued)
                assertEquals(24000.0/1001,c.videoSignal.value!!.fps,.006)
                assertEquals("23.976",c.state.value.diagnostics!!.fps)
                // Simulate the next segment's id/bitrate notification after the result was published.
                formatChanged(c,hevc().buildUpon().setId("next-segment").setAverageBitrate(9_000_000).build())
                assertEquals("23.976",c.state.value.diagnostics!!.fps)
            }
        } finally {c.release()}
    }
    @Test fun inputCadenceCorrectsWrongNominal60AndDoesNotUseThe60HzDisplay() {
        val c=controller()
        try {
            startSession(c)
            Pipeline(c,hevc(60f),25.0,false).use { pipe ->
                pipe.feed();shadowOf(Looper.getMainLooper()).idle()
                assertEquals(25.0,c.videoSignal.value!!.fps,.001)
                assertEquals("25",c.state.value.diagnostics!!.fps)
                formatChanged(c,hevc(60f).buildUpon().setInitializationData(listOf(byteArrayOf(1,2,3))).build())
                assertEquals("60",c.state.value.diagnostics!!.fps)
            }
        } finally {c.release()}
    }
    @Test fun aPendingCadenceFromTheDepartingChannelCannotPopulateTheNewChannel() {
        val c=controller()
        try {
            startSession(c)
            Pipeline(c,hevc(),25.0,false).use { pipe ->
                pipe.feed() // Main-thread cadence posts have not run yet.
                c.play(PlaybackItem("b","B","file:///missing-cadence-b.ts"))
                shadowOf(Looper.getMainLooper()).idle()
                assertNull(c.videoSignal.value)
                assertNull(c.state.value.diagnostics?.fps)
            }
        } finally {c.release()}
    }
}
