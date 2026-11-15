package com.tvnext.app

import android.app.Application
import android.os.Looper
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import com.tvnext.app.core.iptv.archive.ArchiveRepository
import com.tvnext.app.core.iptv.data.ChannelsRepository
import com.tvnext.app.core.player.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.Proxy
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28,35],application=Application::class)
class BufferedPlaybackRecoveryTest {
    private class Fixture(val buffered:Long) {
        val item=PlaybackItem("box","BOX Premiere 4K","file:///fixture-box.ts")
        var releases=0;var prepares=0;var starts=0
        var playReady=true
        var trackParameters=TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
        val player=Proxy.newProxyInstance(ExoPlayer::class.java.classLoader,arrayOf(ExoPlayer::class.java)) {p,m,args->when(m.name) {
            "getPlaybackState"->Player.STATE_BUFFERING
            "getPlayWhenReady"->playReady
            "isPlaying"->false
            "getTotalBufferedDuration","getBufferedPosition"->buffered
            "getCurrentPosition","getContentPosition"->0L
            "getDuration"->C.TIME_UNSET
            "getCurrentTracks"->Tracks.EMPTY
            "getTrackSelectionParameters"->trackParameters
            "setTrackSelectionParameters"->{trackParameters=args!![0] as TrackSelectionParameters;null}
            "getCurrentTimeline"->Timeline.EMPTY
            "getCurrentMediaItem"->MediaItem.Builder().setMediaId(item.id).setUri(item.url).build()
            "getVideoFormat"->Format.Builder().setSampleMimeType("video/hevc").setWidth(3840).setHeight(2160).build()
            "getAudioFormat","getVideoDecoderCounters","getAudioDecoderCounters","getPlayerError"->null
            "getApplicationLooper","getPlaybackLooper"->Looper.getMainLooper()
            "getVolume"->1f
            "release"->{releases++;null}
            "prepare"->{prepares++;null}
            "play"->{starts++;null}
            "removeListener","removeAnalyticsListener","stop","clearMediaItems","setMediaSource","setVolume"->null
            "equals"->p===args?.firstOrNull()
            "hashCode"->System.identityHashCode(p)
            "toString"->"Controlled buffered backend"
            else->error("Unexpected backend operation ${m.name}")
        }} as ExoPlayer
    }
    private fun field(c:AndroidPlaybackController,name:String)=c.javaClass.getDeclaredField(name).apply{isAccessible=true}
    @Suppress("UNCHECKED_CAST")
    private fun player(c:AndroidPlaybackController)=(field(c,"_player").get(c) as MutableStateFlow<ExoPlayer?>).value
    @Suppress("UNCHECKED_CAST")
    private fun controller(f:Fixture):AndroidPlaybackController {
        val context=RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(context.packageName+".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val channels=Proxy.newProxyInstance(ChannelsRepository::class.java.classLoader,arrayOf(ChannelsRepository::class.java)){_,m,_->
            if(m.name=="getChannels")emptyList<com.tvnext.app.core.iptv.domain.Channel>() else error(m.name)} as ChannelsRepository
        val archive=Proxy.newProxyInstance(ArchiveRepository::class.java.classLoader,arrayOf(ArchiveRepository::class.java)){_,m,_->error(m.name)} as ArchiveRepository
        return AndroidPlaybackController(context,channels,archive).also {c->
            c.setVolume(.6f);c.setMuted(true)
            (field(c,"_player").get(c) as MutableStateFlow<ExoPlayer?>).value=f.player
            (field(c,"_state").get(c) as MutableStateFlow<PlayerState>).value=PlayerState(PlaybackState.BUFFERING,f.item,volume=.6f,isMuted=true)
            field(c,"activeKind").set(c,PlaybackContentKind.LIVE)
            field(c,"activeAudioOutputMode").set(c,AudioOutputMode.AUTO)
            val path=field(c,"activeDecodePath").type.enumConstants.first{(it as Enum<*>).name=="HARDWARE"}
            field(c,"activeDecodePath").set(c,path)
            field(c,"activeDecoderName").set(c,"OMX.realtek.video.decoder")
        }
    }
    private fun watch(c:AndroidPlaybackController) {
        c.javaClass.getDeclaredMethod("watchLiveBuffering").apply{isAccessible=true}.invoke(c)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
    }

    @Test fun thePhotographedLoadedBufferReleasesTheStuckPipelineAndPreservesHardwareAndAudioSettings() {
        val f=Fixture(20_833);val c=controller(f)
        try {
            watch(c)
            assertEquals("A loaded stream needs a fresh pipeline, not a prepare on the stuck decoder",1,f.releases)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
            assertNotNull(player(c));assertNotSame(f.player,player(c))
            assertEquals(f.item,c.state.value.item)
            assertEquals(.6f,c.state.value.volume);assertTrue(c.state.value.isMuted)
            assertEquals("HARDWARE",(field(c,"decoderPath").get(c) as Enum<*>).name)
        } finally {c.release()}
    }
    @Test fun aRealNetworkStallStillUsesTheExistingLightweightRetry() {
        val f=Fixture(0);val c=controller(f)
        try {
            watch(c)
            assertSame(f.player,player(c));assertEquals(0,f.releases)
            assertEquals(1,f.prepares);assertEquals(1,f.starts)
        } finally {c.release()}
    }
    @Test fun switchingDuringDecoderReleaseCannotResurrectTheOldChannel() {
        val f=Fixture(20_833);val c=controller(f)
        try {
            watch(c)
            assertEquals(1,f.releases)
            val next=PlaybackItem("next","Next channel","file:///fixture-next.ts")
            c.play(next)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
            assertEquals(next,c.state.value.item)
            assertEquals("next",player(c)?.currentMediaItem?.mediaId)
            assertEquals(0,f.starts)
            assertEquals("The next channel must not inherit the old source's buffered-stall budget",0,field(c,"bufferedRestarts").getInt(c))
        } finally {c.release()}
    }
    @Test fun aPausedBufferedSourceDoesNotTriggerRecovery() {
        val f=Fixture(20_833);val c=controller(f)
        try {
            f.playReady=false
            watch(c)
            assertEquals(0,f.releases);assertEquals(0,f.prepares)
        } finally {c.release()}
    }
    @Test fun aRepeatedLoadedStallWaitsForTheSlowCadenceInsteadOfRebuildingEveryTwentySeconds() {
        val f=Fixture(20_833);val c=controller(f)
        try {
            field(c,"bufferedRestarts").setInt(c,1)
            watch(c)
            assertEquals(0,f.releases);assertEquals(0,f.prepares)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(59))
            assertEquals(0,f.releases)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(1,f.releases)
        } finally {c.release()}
    }
}
