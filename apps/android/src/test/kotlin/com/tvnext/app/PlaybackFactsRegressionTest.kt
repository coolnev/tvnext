package com.tvnext.app

import android.app.Application
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlayerId
import com.tvnext.app.core.player.*
import com.tvnext.app.core.iptv.data.ChannelsRepository
import com.tvnext.app.core.iptv.archive.ArchiveRepository
import kotlinx.coroutines.flow.StateFlow
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,35],application=Application::class)
class PlaybackFactsRegressionTest {
    private fun controller():AndroidPlaybackController {
        val channels=Proxy.newProxyInstance(ChannelsRepository::class.java.classLoader,arrayOf(ChannelsRepository::class.java)){_,m,_->
            if(m.name=="getChannels")emptyList<com.tvnext.app.core.iptv.domain.Channel>() else error(m.name)} as ChannelsRepository
        val archive=Proxy.newProxyInstance(ArchiveRepository::class.java.classLoader,arrayOf(ArchiveRepository::class.java)){_,m,_->error(m.name)} as ArchiveRepository
        return AndroidPlaybackController(RuntimeEnvironment.getApplication(),channels,archive)
    }
    private fun field(c:AndroidPlaybackController,name:String)=c.javaClass.getDeclaredField(name).apply{isAccessible=true}
    private fun player(c:AndroidPlaybackController)=(field(c,"_player").get(c) as StateFlow<*>).value as ExoPlayer
    private fun facts(c:AndroidPlaybackController)=c.javaClass.getDeclaredMethod("buildDiagnostics",ExoPlayer::class.java).apply{isAccessible=true}.invoke(c,player(c)) as PlayerDiagnostics
    @Test fun reuseAcrossChannelsDoesNotForgetTheActualVideoDecoderWhenTrackMetadataIsMissing() {
        val c=controller()
        try {
            c.play(PlaybackItem("a","A","file:///missing-a.ts"))
            val original=player(c);field(c,"activeDecoderName").set(c,"c2.vendor.hevc.decoder")
            c.play(PlaybackItem("b","B","file:///missing-b.ts"))
            assertSame(original,player(c));assertEquals("c2.vendor.hevc.decoder",facts(c).decoder)
            assertEquals(PlayerDiagnostics.MODE_HARDWARE,facts(c).actualMode)
            assertEquals("n/a",facts(c).resolution)
        }finally{c.release()}
    }
    @Test fun inputFormatAndReuseEvaluationSupplyUhdFactsWithoutSelectedTrackGroups() {
        val c=controller()
        try {
            c.play(PlaybackItem("a","A","file:///missing-a.ts"))
            val analytics=field(c,"analyticsListener").get(c) as AnalyticsListener
            val old=Format.Builder().setSampleMimeType("video/hevc").setWidth(1920).setHeight(1080).build()
            val current=old.buildUpon().setWidth(3840).setHeight(2160).setFrameRate(24f).build()
            val event=AnalyticsListener.EventTime(0,Timeline.EMPTY,0,null,0,Timeline.EMPTY,0,null,0,0)
            analytics.onVideoInputFormatChanged(event,current,DecoderReuseEvaluation("c2.vendor.hevc.decoder",old,current,
                DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_RECONFIGURATION,0))
            assertEquals("3840x2160",facts(c).resolution);assertEquals("24",facts(c).fps)
            assertEquals(PlayerDiagnostics.MODE_HARDWARE,facts(c).actualMode)
            analytics.onAudioInputFormatChanged(event,Format.Builder().setSampleMimeType("audio/ac3").setSampleRate(48000).setChannelCount(6).build(),null)
            assertTrue(facts(c).audio!!.contains("AC3"))
        }finally{c.release()}
    }
    @Test fun switchingAudioOutputRebuildsOnlyTheSessionAndKeepsChannelVolumeAndMute() {
        val c=controller()
        try {
            val item=PlaybackItem("a","A","file:///missing-a.ts")
            c.play(item);c.setVolume(.7f);c.setMuted(true)
            val before=player(c);c.updateAudioOutputMode(AudioOutputMode.PCM_STEREO)
            assertNotSame(before,player(c));assertEquals(item,c.state.value.item)
            assertEquals(.7f,c.state.value.volume);assertTrue(c.state.value.isMuted)
        }finally{c.release()}
    }
    @Test fun stereoCompatibilityMixesTheCentreDialogueIntoBothSpeakers() {
        val mixer=Class.forName("com.tvnext.app.core.player.AndroidAudioRenderersFactoryKt")
            .getDeclaredMethod("createStereoPcmMixer").apply{isAccessible=true}.invoke(null) as androidx.media3.common.audio.ChannelMixingAudioProcessor
        val out=mixer.configure(androidx.media3.common.audio.AudioProcessor.AudioFormat(48000,6,C.ENCODING_PCM_16BIT))
        assertEquals(2,out.channelCount);mixer.flush()
        val input=ByteBuffer.allocateDirect(12).order(ByteOrder.nativeOrder())
        for(ch in 0..5)input.putShort((if(ch==2)10000 else 0).toShort())
        input.flip();mixer.queueInput(input)
        val pcm=mixer.output.order(ByteOrder.nativeOrder());val left=pcm.short;val right=pcm.short
        assertTrue(left>2000);assertEquals(left,right);mixer.reset()
    }
    @Test fun uhdLiveBufferKeepsLoadingToReachTheMinimumTimeEvenAfterTheByteTarget() {
        val c=controller()
        val load=c.javaClass.getDeclaredMethod("loadControlFor",PlaybackContentKind::class.java).apply{isAccessible=true}
            .invoke(c,PlaybackContentKind.LIVE) as LoadControl
        val id=PlayerId.UNSET
        load.onPrepared(id)
        val allocator=load.getAllocator(id)
        val allocated=(0 until 340).map{allocator.allocate()}
        try {
            val timeline=androidx.media3.exoplayer.source.SinglePeriodTimeline(60_000_000,true,true,false,null,
                MediaItem.fromUri("https://fixture.invalid/live.ts"))
            val period=androidx.media3.exoplayer.source.MediaSource.MediaPeriodId(timeline.getUidOfPeriod(0))
            val params=LoadControl.Parameters(id,timeline,period,0,1_000_000,1f,true,false,C.TIME_UNSET,C.TIME_UNSET)
            assertTrue("A UHD stream must not stop at a tiny byte buffer before 3 seconds are ready",load.shouldContinueLoading(params))
            assertFalse(load.shouldContinueLoading(LoadControl.Parameters(id,timeline,period,
                0,4_000_000,1f,true,false,C.TIME_UNSET,C.TIME_UNSET)))
        }finally{allocated.forEach{allocator.release(it)};load.onReleased(id);c.release()}
    }
}
