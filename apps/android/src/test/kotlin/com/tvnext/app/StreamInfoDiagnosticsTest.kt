package com.tvnext.app

import android.app.Application
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.tvnext.app.core.iptv.archive.ArchiveRepository
import com.tvnext.app.core.iptv.data.ChannelsRepository
import com.tvnext.app.core.player.*
import kotlinx.coroutines.flow.StateFlow
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28,35],application=Application::class)
class StreamInfoDiagnosticsTest {
    private fun controller():AndroidPlaybackController {
        val context=RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(context.packageName+".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val channels=Proxy.newProxyInstance(ChannelsRepository::class.java.classLoader,arrayOf(ChannelsRepository::class.java)) {_,m,_->
            if(m.name=="getChannels")emptyList<com.tvnext.app.core.iptv.domain.Channel>() else error(m.name)} as ChannelsRepository
        val archive=Proxy.newProxyInstance(ArchiveRepository::class.java.classLoader,arrayOf(ArchiveRepository::class.java)){_,m,_->error(m.name)} as ArchiveRepository
        return AndroidPlaybackController(context,channels,archive)
    }
    private fun field(c:AndroidPlaybackController,name:String)=c.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(c)
    private val event get()=AnalyticsListener.EventTime(0,Timeline.EMPTY,0,null,0,Timeline.EMPTY,0,null,0,0)

    @Test fun snapshotContainsActualFormatsResolvedUrlAndHttpFailureWhileRecoveryIsLoading() {
        val c=controller()
        try {
            c.play(PlaybackItem("box","Box Premier 4K","file:///fixture.ts"))
            val exo=(field(c,"_player") as StateFlow<*>).value as ExoPlayer
            val analytics=field(c,"analyticsListener") as AnalyticsListener
            analytics.onVideoInputFormatChanged(event,Format.Builder().setSampleMimeType("video/hevc")
                .setCodecs("hvc1.2.4.L153").setWidth(3840).setHeight(2160).setFrameRate(23.976f).build(),null)
            analytics.onVideoDecoderInitialized(event,"c2.mtk.hevc.decoder",0,20)
            val started=field(c,"tuneStartedElapsedMs") as Long
            analytics.onRenderedFirstFrame(event,Any(),started+137)
            analytics.onRenderedFirstFrame(event,Any(),started+500)
            analytics.onAudioInputFormatChanged(event,Format.Builder().setSampleMimeType("audio/ac3").setChannelCount(6).setSampleRate(48000).build(),null)
            analytics.onAudioDecoderInitialized(event,"c2.vendor.ac3.decoder",0,10)
            analytics.onDroppedVideoFrames(event,12,1000)
            val uri=Uri.parse("https://fixture.invalid/redirect/segment.ts")
            val spec=DataSpec(uri)
            val error=HttpDataSource.InvalidResponseCodeException(403,"Forbidden",null,emptyMap(),spec,byteArrayOf())
            analytics.onLoadError(event,LoadEventInfo(1,spec,uri,emptyMap(),0,100,0),MediaLoadData(C.DATA_TYPE_MEDIA),error,false)
            (field(c,"listener") as Player.Listener).onPlayerError(PlaybackException("Upstream refused the stream",error,PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
            val videoCounts=androidx.media3.exoplayer.DecoderCounters().apply {queuedInputBufferCount=120;renderedOutputBufferCount=0;skippedOutputBufferCount=5;droppedBufferCount=3}
            val audioCounts=androidx.media3.exoplayer.DecoderCounters().apply {queuedInputBufferCount=96;renderedOutputBufferCount=96}
            exo.javaClass.getDeclaredField("videoDecoderCounters").apply{isAccessible=true}.set(exo,videoCounts)
            exo.javaClass.getDeclaredField("audioDecoderCounters").apply{isAccessible=true}.set(exo,audioCounts)
            assertEquals(PlaybackState.LOADING,c.state.value.state)
            val before=c.state.value
            val info=c.streamInfo();val facts=info.facts.associate{it.label to it.value}
            assertEquals("file:///fixture.ts",info.sourceUrl)
            assertEquals(uri.toString(),info.lastRequestUrl)
            assertTrue(info.lastError!!.contains("403"));assertTrue(info.lastError!!.contains("ERROR_CODE_IO_BAD_HTTP_STATUS"))
            assertTrue(facts.getValue("Видеокодек").contains("video/hevc"))
            assertEquals("c2.mtk.hevc.decoder",facts["Видеодекодер"])
            assertEquals("137 ms",facts["Первый видеокадр"])
            assertEquals("1",facts["Запуски видеодекодера"])
            assertEquals("0 / 0",facts["Аудиодорожки: найдено / выбрано"])
            assertEquals("3840 × 2160",facts["Разрешение"])
            assertEquals("audio/ac3",facts["Аудиокодек"])
            assertTrue(facts.getValue("Параметры звука").contains("48000"))
            assertEquals("403",facts["HTTP"]);assertEquals("12",facts["Потери кадров"])
            assertEquals("120 / 0",facts["Видео: вход / выход"]);assertEquals("96 / 96",facts["Аудио: вход / выход"])
            assertEquals("5 / 3",facts["Видео: пропущено / потеряно"])
            assertTrue(facts.getValue("Очередь декодера").contains("Media3/ExoPlayer"))
            assertEquals(before,c.state.value)
            assertSame(exo,(field(c,"_player") as StateFlow<*>).value)
        } finally {c.release()}
    }
    @Test fun aNewChannelCannotInheritThePreviousSourcesErrorOrResolvedAddress() {
        val c=controller()
        try {
            c.play(PlaybackItem("old","Old source","file:///old.ts"))
            (field(c,"listener") as Player.Listener).onPlayerError(PlaybackException("old failure",null,PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
            assertTrue(c.streamInfo().lastError!!.contains("old failure"))
            c.play(PlaybackItem("new","New source","file:///new.ts"))
            val facts=c.streamInfo().facts.associate{it.label to it.value}
            assertEquals("—",facts["Первый видеокадр"]);assertEquals("0",facts["Запуски видеодекодера"])
            val info=c.streamInfo()
            assertEquals("New source",info.title);assertEquals("file:///new.ts",info.sourceUrl)
            assertNull(info.lastError);assertNull(info.lastRequestUrl)
        } finally {c.release()}
    }
}
