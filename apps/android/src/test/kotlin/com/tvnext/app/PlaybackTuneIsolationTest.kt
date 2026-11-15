package com.tvnext.app

import android.app.Application
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.mediacodec.*
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import com.tvnext.app.core.iptv.archive.ArchiveRepository
import com.tvnext.app.core.iptv.data.ChannelsRepository
import com.tvnext.app.core.player.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28,35],application=Application::class)
class PlaybackTuneIsolationTest {
    private fun field(c:Any,name:String)=c.javaClass.getDeclaredField(name).apply{isAccessible=true}
    private fun controller():AndroidPlaybackController {
        val context=RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(context.packageName+".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val channels=Proxy.newProxyInstance(ChannelsRepository::class.java.classLoader,arrayOf(ChannelsRepository::class.java)){_,m,_->
            if(m.name=="getChannels")emptyList<com.tvnext.app.core.iptv.domain.Channel>() else error(m.name)} as ChannelsRepository
        val archive=Proxy.newProxyInstance(ArchiveRepository::class.java.classLoader,arrayOf(ArchiveRepository::class.java)){_,m,_->error(m.name)} as ArchiveRepository
        return AndroidPlaybackController(context,channels,archive)
    }
    private inner class Backend(val c:AndroidPlaybackController) {
        val policy=field(c,"videoOutputPolicy").get(c)
        val rendererClass=Class.forName("com.tvnext.app.core.player.TextureSafeVideoRenderer")
        val renderer=rendererClass.getDeclaredConstructor(MediaCodecVideoRenderer.Builder::class.java,policy.javaClass)
            .apply{isAccessible=true}.newInstance(MediaCodecVideoRenderer.Builder(RuntimeEnvironment.getApplication()),policy) as MediaCodecVideoRenderer
        val codecField=MediaCodecRenderer::class.java.getDeclaredField("codec").apply{isAccessible=true}
        var releases=0;var stops=0;var prepares=0
        var incomingInheritedDecoder=false
        var preferencesTouchedOldDecoder=false
        var current:MediaItem?=MediaItem.Builder().setMediaId("old").setUri("file:///old.ts").build()
        var tracks=TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
        val exo=Proxy.newProxyInstance(ExoPlayer::class.java.classLoader,arrayOf(ExoPlayer::class.java)){p,m,args->when(m.name) {
            "getPlaybackState"->Player.STATE_READY
            "getCurrentMediaItem"->current
            "getTotalBufferedDuration","getBufferedPosition"->8_000L
            "getCurrentPosition","getContentPosition"->0L
            "getDuration"->C.TIME_UNSET
            "getPlayWhenReady","isPlaying"->true
            "getCurrentTracks"->Tracks.EMPTY
            "getTrackSelectionParameters"->tracks
            "setTrackSelectionParameters"->{preferencesTouchedOldDecoder=codecField.get(renderer)!=null;tracks=args!![0] as TrackSelectionParameters;null}
            "getApplicationLooper","getPlaybackLooper"->Looper.getMainLooper()
            "getVolume"->.6f
            "stop"->{stops++;if(renderer.state==Renderer.STATE_ENABLED)renderer.disable();null}
            "clearMediaItems"->{current=null;null}
            "setMediaSource"->{incomingInheritedDecoder=codecField.get(renderer)!=null;current=(args!![0] as MediaSource).mediaItem;null}
            "prepare"->{prepares++;null}
            "release"->{if(renderer.state==Renderer.STATE_ENABLED)renderer.disable();null}
            "play","setVolume","removeListener","removeAnalyticsListener"->null
            "equals"->p===args?.firstOrNull()
            "hashCode"->System.identityHashCode(p)
            "toString"->"Tune backend with a real Media3 video renderer"
            else->error("Unexpected player operation ${m.name}")
        }} as ExoPlayer
        @Suppress("UNCHECKED_CAST")
        fun attach() {
            policy.javaClass.getDeclaredMethod("setTextureOutput",Boolean::class.javaPrimitiveType).invoke(policy,true)
            (field(c,"_player").get(c) as MutableStateFlow<ExoPlayer?>).value=exo
            val item=PlaybackItem("old","Old","file:///old.ts")
            (field(c,"_state").get(c) as MutableStateFlow<PlayerState>).value=PlayerState(PlaybackState.PLAYING,item)
            field(c,"activeKind").set(c,PlaybackContentKind.LIVE)
            field(c,"activeAudioOutputMode").set(c,AudioOutputMode.AUTO)
            field(c,"activeDecodePath").set(c,field(c,"activeDecodePath").type.enumConstants!!.first{(it as Enum<*>).name=="HARDWARE"})
            c.setVolume(.6f);c.setMuted(true)
        }
        fun installQueuedVideo() {
            val codec=Proxy.newProxyInstance(MediaCodecAdapter::class.java.classLoader,arrayOf(MediaCodecAdapter::class.java)){_,m,_->
                if(m.name=="release"){releases++;null}else error("Old codec must not be flushed or used for the incoming source: ${m.name}")}
            val format=Format.Builder().setSampleMimeType("video/avc").setWidth(1280).setHeight(720).build()
            for((name,value) in listOf("codec" to codec,"codecInfo" to MediaCodecInfo.newInstance("OMX.realtek.video.decoder",
                "video/avc","video/avc",null,true,false,true,false,false),"inputFormat" to format,"codecInputFormat" to format))
                MediaCodecRenderer::class.java.getDeclaredField(name).apply{isAccessible=true}.set(renderer,value)
            MediaCodecRenderer::class.java.getDeclaredField("codecReceivedBuffers").apply{isAccessible=true}.setBoolean(renderer,true)
            BaseRenderer::class.java.getDeclaredField("state").apply{isAccessible=true}.setInt(renderer,Renderer.STATE_ENABLED)
        }
    }
    @Test fun normalAndRepeatedTunesCannotPassAnOldQueuedRealtekDecoderToTheIncomingSource() {
        val c=controller();val backend=Backend(c)
        try {
            backend.attach()
            for((index,id) in listOf("new","old","old","third").withIndex()) {
                backend.installQueuedVideo()
                val item=PlaybackItem(id,id,"file:///$id.ts")
                c.play(item)
                assertFalse("Incoming $id must not drain or reuse the departing channel's queued video",backend.incomingInheritedDecoder)
                assertFalse("Incoming track preferences must not reconfigure the departing decoder",backend.preferencesTouchedOldDecoder)
                assertEquals(index+1,backend.releases);assertEquals(index+1,backend.prepares)
                assertSame(backend.exo,(field(c,"_player").get(c) as MutableStateFlow<*>).value)
                assertEquals(id,backend.current?.mediaId);assertEquals(item,c.state.value.item)
                assertEquals(.6f,c.state.value.volume);assertTrue(c.state.value.isMuted)
                assertNull("Normal tuning must not enter a delayed recovery",field(c,"retryJob").get(c))
                assertEquals(0L,field(c,"rendererReleaseUntilMs").getLong(c))
            }
        } finally {c.release()}
    }
    @Test fun aNetworkRetryCutsTheSourceOnlyOnceAndKeepsTheSamePlayer() {
        val c=controller();val backend=Backend(c)
        try {
            backend.attach();backend.installQueuedVideo()
            c.javaClass.getDeclaredMethod("autoRestart",PlaybackItem::class.java,PlaybackError::class.java).apply{isAccessible=true}
                .invoke(c,c.state.value.item,PlaybackError.Network)
            assertFalse(backend.incomingInheritedDecoder);assertEquals(1,backend.stops);assertEquals(1,backend.releases)
            assertEquals(1,backend.prepares)
            assertSame(backend.exo,(field(c,"_player").get(c) as MutableStateFlow<*>).value)
        } finally {c.release()}
    }
}
