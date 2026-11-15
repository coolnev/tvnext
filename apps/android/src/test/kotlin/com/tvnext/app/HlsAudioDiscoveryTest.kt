package com.tvnext.app

import android.app.Application
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.datasource.*
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.hls.*
import androidx.media3.extractor.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28,35],application=Application::class)
class HlsAudioDiscoveryTest {
    private val videoCodecs="hvc1.2.4.H153"
    private fun factory():HlsExtractorFactory = try {
        Class.forName("com.tvnext.app.core.player.IptvHlsExtractorFactory").getDeclaredConstructor()
            .apply{isAccessible=true}.newInstance() as HlsExtractorFactory
    } catch(_:ClassNotFoundException) {DefaultHlsExtractorFactory()}
    private fun extract(file:String,codecs:String=videoCodecs):List<Format> {
        val bytes=javaClass.getResourceAsStream("/transport/$file")!!.use{it.readBytes()}
        val data=ByteArrayDataSource(bytes)
        val uri=Uri.parse("https://fixture.invalid/segment.ts")
        data.open(DataSpec(uri))
        try {
            val input=DefaultExtractorInput(data,0,bytes.size.toLong())
            val format=Format.Builder().setCodecs(codecs).setContainerMimeType(MimeTypes.VIDEO_MP2T).build()
            val extractor=factory().createExtractor(uri,format,null,TimestampAdjuster(0),
                mapOf("Content-Type" to listOf("video/mp2t")),input,PlayerId.UNSET)
            val formats=mutableListOf<Format>()
            extractor.init(object:ExtractorOutput {
                override fun track(id:Int,type:Int):TrackOutput=object:TrackOutput by DiscardingTrackOutput() {
                    override fun format(format:Format){formats+=format}
                }
                override fun endTracks() {}
                override fun seekMap(map:SeekMap) {}
            })
            var reads=0
            while(extractor.read(input)){check(++reads<10_000)}
            return formats
        } finally {data.close()}
    }
    @Test fun aHevcOnlyManifestMustNotDiscardTheAacActuallyPresentInTransportPackets() {
        val formats=extract("hevc-aac.ts")
        assertTrue(formats.any{it.sampleMimeType==MimeTypes.VIDEO_H265})
        val audio=formats.firstOrNull{it.sampleMimeType==MimeTypes.AUDIO_AAC}
        assertNotNull("A real AAC track must survive an incomplete HEVC-only CODECS attribute",audio)
        assertEquals(48000,audio!!.sampleRate);assertEquals(2,audio.channelCount)
    }
    @Test fun blurayStyleDtsTransportAudioIsDiscovered() {
        val formats=extract("hevc-dts.ts",videoCodecs+",dtsc")
        assertTrue(formats.any{it.sampleMimeType==MimeTypes.VIDEO_H265})
        assertNotNull("HDMV DTS stream type 0x82 must not be silently ignored",formats.firstOrNull{it.sampleMimeType==MimeTypes.AUDIO_DTS})
    }
    @Test fun completeCodecDeclarationsStillExposeTheirOriginalAacTrack() {
        val audio=extract("hevc-aac.ts",videoCodecs+",mp4a.40.2").filter{it.sampleMimeType==MimeTypes.AUDIO_AAC}
        assertEquals(1,audio.size);assertEquals(48000,audio.single().sampleRate)
    }
    @Test fun aGenuineVideoOnlyTransportDoesNotGainAFakeAudioTrack() {
        val formats=extract("hevc-only.ts")
        assertTrue(formats.any{it.sampleMimeType==MimeTypes.VIDEO_H265})
        assertFalse(formats.any{it.sampleMimeType?.startsWith("audio/")==true})
    }
    @Test fun actualPlaybackFactoryPreparesHlsFromChunksAndRetainsLocalAndProgressiveDispatch() {
        val type=Class.forName("com.tvnext.app.core.player.AndroidPlaybackMediaSources")
        val create=type.getDeclaredMethod("create",DataSource.Factory::class.java,MediaItem::class.java)
        val data=DataSource.Factory{ByteArrayDataSource(byteArrayOf())}
        for(uri in listOf("https://fixture.invalid/live.m3u8","file:///record/index.m3u8")) {
            val source=create.invoke(type.getDeclaredField("INSTANCE").get(null),data,MediaItem.fromUri(uri))!!
            assertEquals("HlsMediaSource",source.javaClass.simpleName)
            assertFalse(source.javaClass.getDeclaredField("allowChunklessPreparation").apply{isAccessible=true}.getBoolean(source))
            assertEquals("IptvHlsExtractorFactory",source.javaClass.getDeclaredField("extractorFactory").apply{isAccessible=true}.get(source).javaClass.simpleName)
        }
        val source=create.invoke(type.getDeclaredField("INSTANCE").get(null),data,MediaItem.fromUri("file:///record.ts"))!!
        assertEquals("ProgressiveMediaSource",source.javaClass.simpleName)
    }
}
