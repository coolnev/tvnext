package com.tvnext.app

import android.app.Application
import android.media.MediaFormat
import androidx.media3.common.Format
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.mediacodec.*
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk=[28,35],application=Application::class)
class RealtekCodecQueueTest {
    private val policyClass=Class.forName("com.tvnext.app.core.player.AndroidCodecCompatibility")
    private val format=Format.Builder().setSampleMimeType("video/hevc").setWidth(3840).setHeight(2160).build()
    private val decoder="OMX.realtek.video.decoder"
    private fun policy(sdk:Int=31)=policyClass.getDeclaredConstructor(Int::class.javaPrimitiveType).apply{isAccessible=true}.newInstance(sdk).also{select(it,"box")}
    private fun select(policy:Any,id:String) {policyClass.getDeclaredMethod("selectSource",String::class.java).invoke(policy,id)}
    private fun record(policy:Any,name:String=decoder,video:Format=format,counters:DecoderCounters?=counts()) =
        policyClass.getDeclaredMethod("recordNoOutputStall",String::class.java,Format::class.java,DecoderCounters::class.java)
            .invoke(policy,name,video,counters) as Boolean
    private fun counts()=DecoderCounters().apply{queuedInputBufferCount=249}
    private class Factory:MediaCodecAdapter.Factory {
        val calls=mutableListOf<MediaCodecAdapter.Configuration>()
        val adapter=Proxy.newProxyInstance(MediaCodecAdapter::class.java.classLoader,arrayOf(MediaCodecAdapter::class.java)){_,m,_->error(m.name)} as MediaCodecAdapter
        override fun createAdapter(configuration:MediaCodecAdapter.Configuration):MediaCodecAdapter {calls+=configuration;return adapter}
    }
    private fun factory(policy:Any,defaults:Factory,async:Factory,sync:Factory):MediaCodecAdapter.Factory =
        Class.forName("com.tvnext.app.core.player.AmlogicMediaCodecAdapterFactory").getDeclaredConstructor(
            Int::class.javaPrimitiveType,MediaCodecAdapter.Factory::class.java,MediaCodecAdapter.Factory::class.java,
            MediaCodecAdapter.Factory::class.java,policyClass).apply{isAccessible=true}.newInstance(31,defaults,async,sync,policy) as MediaCodecAdapter.Factory
    private fun config(video:Format=format,hardware:Boolean=true,software:Boolean=false)=MediaCodecAdapter.Configuration.createForVideoDecoding(
        MediaCodecInfo.newInstance(decoder,video.sampleMimeType!!,video.sampleMimeType!!,null,hardware,software,true,false,false),
        MediaFormat.createVideoFormat(video.sampleMimeType!!,video.width,video.height),video,null,null)

    @Test fun onlyAConfirmedNoOutputStallChangesTheNextNewFactoryAndKeepsTheExactHardwareConfiguration() {
        val policy=policy();val defaults=Factory();val async=Factory();val sync=Factory();val configuration=config()
        assertSame(defaults.adapter,factory(policy,defaults,async,sync).createAdapter(configuration))
        assertTrue(record(policy));assertFalse("The compatibility change is learned only once",record(policy))
        val replacement=factory(policy,defaults,async,sync)
        repeat(3){assertSame(sync.adapter,replacement.createAdapter(configuration))}
        assertTrue(sync.calls.all{it===configuration});assertEquals(1,defaults.calls.size);assertTrue(async.calls.isEmpty())
        assertEquals(3840,configuration.mediaFormat.getInteger(MediaFormat.KEY_WIDTH))
        assertEquals(2160,configuration.mediaFormat.getInteger(MediaFormat.KEY_HEIGHT))
        assertTrue(configuration.codecInfo.hardwareAccelerated)
        assertTrue(replacement.javaClass.getDeclaredMethod("getVideoOutputDescription").invoke(replacement)!!.toString().contains("Realtek HEVC sync"))
    }
    @Test fun renderedSkippedDroppedAndInsufficientInputCannotBeMistakenForAStuckOutputQueue() {
        for(counters in listOf(counts().apply{renderedOutputBufferCount=1},counts().apply{skippedOutputBufferCount=1},
            counts().apply{droppedBufferCount=1},counts().apply{queuedInputBufferCount=31})) assertFalse(record(policy(),counters=counters))
        assertFalse(record(policy(),counters=null))
    }
    @Test fun otherVendorsAvcFullHdAndOlderAndroidKeepTheirDefaultQueue() {
        assertFalse(record(policy(),name="c2.mtk.hevc.decoder"))
        assertFalse(record(policy(30)))
        assertFalse(record(policy(),video=format.buildUpon().setSampleMimeType("video/avc").build()))
        assertFalse(record(policy(),video=format.buildUpon().setWidth(1920).setHeight(1080).build()))
        val policy=policy();assertTrue(record(policy))
        val defaults=Factory();val async=Factory();val sync=Factory();val selection=factory(policy,defaults,async,sync)
        for(configuration in listOf(config(format.buildUpon().setWidth(1920).setHeight(1080).build()),config(hardware=false,software=true)))
            assertSame(defaults.adapter,selection.createAdapter(configuration))
        assertTrue(sync.calls.isEmpty())
    }
    @Test fun profileIsNotPersistedAcrossApplicationSessions() {
        val learned=policy();assertTrue(record(learned))
        val defaults=Factory();val async=Factory();val sync=Factory()
        assertSame(defaults.adapter,factory(policy(),defaults,async,sync).createAdapter(config()))
        assertTrue(sync.calls.isEmpty())
    }
    @Test fun oneBrokenBoxChannelCannotChangeTheQueueOfAnyOtherUhdChannelInAReusedPlayer() {
        val policy=policy();assertTrue(record(policy))
        val defaults=Factory();val async=Factory();val sync=Factory()
        val selection=factory(policy,defaults,async,sync)
        val configuration=config()
        assertSame(sync.adapter,selection.createAdapter(configuration))
        for(id in listOf("other4k","anotherMovie","ordinaryChannel")) {
            select(policy,id)
            assertSame("The exact same UHD Realtek configuration on $id keeps Media3 defaults",defaults.adapter,selection.createAdapter(configuration))
        }
        select(policy,"box")
        assertSame(sync.adapter,selection.createAdapter(configuration))
        assertEquals(2,sync.calls.size);assertEquals(3,defaults.calls.size);assertTrue(async.calls.isEmpty())
    }
}
