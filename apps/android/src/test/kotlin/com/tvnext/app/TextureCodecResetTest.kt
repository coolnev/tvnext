package com.tvnext.app

import android.app.Application
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.Format
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,35], application=Application::class)
class TextureCodecResetTest {
    private val rendererClass = Class.forName("com.tvnext.app.core.player.TextureSafeVideoRenderer")
    private val policyClass = Class.forName("com.tvnext.app.core.player.AndroidVideoOutputPolicy")
    private class Operations { var flushes=0; var releases=0;var surfaceChanges=0 }

    private fun renderer(texture:Boolean=true, tcl:Boolean=false): Pair<MediaCodecVideoRenderer,Any> {
        val policy=policyClass.getDeclaredConstructor(Boolean::class.javaPrimitiveType).apply{isAccessible=true}.newInstance(tcl)
        policyClass.getDeclaredMethod("setTextureOutput",Boolean::class.javaPrimitiveType).invoke(policy,texture)
        val renderer=rendererClass.getDeclaredConstructor(MediaCodecVideoRenderer.Builder::class.java,policyClass)
            .apply{isAccessible=true}.newInstance(MediaCodecVideoRenderer.Builder(RuntimeEnvironment.getApplication()),policy) as MediaCodecVideoRenderer
        return renderer to policy
    }
    private fun installCodec(renderer:MediaCodecVideoRenderer, name:String="c2.mtk.avc.decoder", hardware:Boolean=true,
        software:Boolean=false):Operations {
        val operations=Operations()
        val adapter=Proxy.newProxyInstance(MediaCodecAdapter::class.java.classLoader,arrayOf(MediaCodecAdapter::class.java)) {_,m,_->
            when(m.name) {
                "flush" -> {operations.flushes++;Unit}
                "release" -> {operations.releases++;Unit}
                "setOutputSurface"->{operations.surfaceChanges++;Unit}
                else -> error("Unexpected codec operation ${m.name}")
            }
        } as MediaCodecAdapter
        for((name,value) in listOf("codec" to adapter,"codecInfo" to
            MediaCodecInfo.newInstance(name,"video/avc","video/avc",null,hardware,software,true,false,false))) {
            MediaCodecRenderer::class.java.getDeclaredField(name).apply{isAccessible=true}.set(renderer,value)
        }
        MediaCodecRenderer::class.java.getDeclaredField("codecReceivedBuffers").apply{isAccessible=true}.setBoolean(renderer,true)
        return operations
    }
    private fun reset(renderer:MediaCodecVideoRenderer,keyFrame:Boolean=true) {
        rendererClass.getDeclaredMethod("onPositionReset",Long::class.javaPrimitiveType,Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(renderer,0L,false,keyFrame)
    }

    @Test fun actualMedia3SeekReleasesAffectedTextureCodecWithoutFlushingItsQueue() {
        for(name in listOf("c2.mtk.avc.decoder","c2.mtk.hevc.decoder","OMX.MTK.VIDEO.DECODER.AVC","OMX.realtek.video.decoder")) {
            val (renderer,_)=renderer();val operations=installCodec(renderer,name)
            reset(renderer)
            assertEquals(0,operations.flushes);assertEquals(1,operations.releases)
            reset(renderer)
            assertEquals(1,operations.releases)
        }
    }
    @Test fun nativeOutputOtherVendorsAndSoftwareKeepTheNormalMedia3Flush() {
        for((texture,tcl,name,hardware,software) in listOf(
            listOf(false,false,"c2.mtk.avc.decoder",true,false),
            listOf(false,true,"c2.mtk.avc.decoder",true,false),
            listOf(true,false,"OMX.amlogic.avc.decoder.awesome",true,false),
            listOf(true,true,"c2.android.avc.decoder",false,true))) {
            val (renderer,_)=renderer(texture as Boolean,tcl as Boolean)
            val operations=installCodec(renderer,name as String,hardware as Boolean,software as Boolean)
            reset(renderer)
            assertEquals(1,operations.flushes);assertEquals(0,operations.releases)
        }
    }
    @Test fun nonKeyframePositionUpdatesDoNotRestartTheCodec() {
        val (renderer,_)=renderer();val operations=installCodec(renderer)
        repeat(8){reset(renderer,false)}
        assertEquals(0,operations.flushes);assertEquals(0,operations.releases)
    }
    @Test fun disableReleasesTheAffectedCodecButAStreamNotificationDoesNotDiscardItsKeyframe() {
        val (disabled,_)=renderer();val operations=installCodec(disabled)
        rendererClass.getDeclaredMethod("onDisabled").apply{isAccessible=true}.invoke(disabled)
        assertEquals(0,operations.flushes);assertEquals(1,operations.releases)

        val (changed,_)=renderer(tcl=true);val changedOps=installCodec(changed,"OMX.vendor.avc.decoder")
        val stream=Proxy.newProxyInstance(SampleStream::class.java.classLoader,arrayOf(SampleStream::class.java)) {_,m,_->
            if(m.name=="getFlags")0 else error("Unexpected sample read ${m.name}")
        }
        BaseRenderer::class.java.getDeclaredField("stream").apply{isAccessible=true}.set(changed,stream)
        MediaCodecVideoRenderer::class.java.getDeclaredMethod("onStreamChanged",Array<Format>::class.java,Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,MediaSource.MediaPeriodId::class.java).apply{isAccessible=true}
            .invoke(changed,arrayOf(Format.Builder().setSampleMimeType("video/avc").build()),0L,0L,MediaSource.MediaPeriodId("new-channel"))
        assertEquals(0,changedOps.flushes);assertEquals(0,changedOps.releases)
    }
    @Test fun sameSurfacePreservesTheFirstKeyframeButARealTextureHandoffReplacesTheCodec() {
        val (renderer,policy)=renderer()
        val texture=SurfaceTexture(0);val first=Surface(texture);val second=Surface(texture)
        try {
            renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT,first)
            val operations=installCodec(renderer)
            renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT,first)
            assertEquals("Repeated output notification must preserve the consumed IDR",0,operations.releases)
            assertEquals(0,operations.surfaceChanges)
            policyClass.getDeclaredMethod("setTextureOutput",Boolean::class.javaPrimitiveType).invoke(policy,false)
            renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT,second)
            assertEquals(0,operations.flushes);assertEquals(1,operations.releases);assertEquals(0,operations.surfaceChanges)
        } finally {first.release();second.release();texture.release()}
    }
    @Test fun nativeVideoAndOtherVendorsStillChangeTheirSurfaceWithoutRestartingTheDecoder() {
        for((texture,name) in listOf(false to "OMX.realtek.video.decoder",true to "OMX.amlogic.avc.decoder.awesome")) {
            val (renderer,_)=renderer(texture=texture)
            val st=SurfaceTexture(0);val first=Surface(st);val second=Surface(st)
            try {
                renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT,first)
                val operations=installCodec(renderer,name)
                renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT,second)
                assertEquals(0,operations.releases);assertEquals(1,operations.surfaceChanges);assertEquals(0,operations.flushes)
            } finally {first.release();second.release();st.release()}
        }
    }
    private fun changeFormat(renderer:MediaCodecVideoRenderer,width:Int,height:Int,codecDataChanged:Boolean=false):androidx.media3.exoplayer.DecoderReuseEvaluation {
        val oldFormat=Format.Builder().setId("old-track").setSampleMimeType("video/avc").setWidth(width).setHeight(height).build()
        val newFormat=oldFormat.buildUpon().setId("new-track").setAverageBitrate(2_000_000).apply {
            if(codecDataChanged)setInitializationData(listOf(byteArrayOf(0,0,0,1,0x67)))
        }.build()
        for((name,value) in listOf("inputFormat" to oldFormat,"codecInputFormat" to oldFormat))
            MediaCodecRenderer::class.java.getDeclaredField(name).apply{isAccessible=true}.set(renderer,value)
        val maxClass=Class.forName("androidx.media3.exoplayer.video.MediaCodecVideoRenderer\$CodecMaxValues")
        val max=maxClass.getDeclaredConstructor(Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType)
            .apply{isAccessible=true}.newInstance(3840,2160,64_000_000)
        MediaCodecVideoRenderer::class.java.getDeclaredField("codecMaxValues").apply{isAccessible=true}.set(renderer,max)
        val holder=androidx.media3.exoplayer.FormatHolder().apply{format=newFormat}
        return MediaCodecVideoRenderer::class.java.getDeclaredMethod("onInputFormatChanged",androidx.media3.exoplayer.FormatHolder::class.java)
            .apply{isAccessible=true}.invoke(renderer,holder) as androidx.media3.exoplayer.DecoderReuseEvaluation
    }
    @Test fun formatChangesDrainAndReplaceTheReportedRealtekTextureCodecInsteadOfReusingItsQueue() {
        val (renderer,_)=renderer(tcl=true);val operations=installCodec(renderer,"OMX.realtek.video.decoder")
        val result=changeFormat(renderer,1280,720,codecDataChanged=true)
        assertEquals(androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_NO,result.result)
        assertEquals("Drain to EOS, then reinitialize; do not flush or reconfigure the old queue",3,
            MediaCodecRenderer::class.java.getDeclaredField("codecDrainAction").apply{isAccessible=true}.getInt(renderer))
        assertEquals(0,operations.flushes);assertEquals(0,operations.releases)
    }
    @Test fun aMetadataOnlyHeaderUpdateDoesNotDrainOrReinitializeAnAlreadyRunningRealtekDecoder() {
        val (renderer,_)=renderer(tcl=true);val operations=installCodec(renderer,"OMX.realtek.video.decoder")
        val result=changeFormat(renderer,1280,720)
        assertEquals("Track id/bitrate changes do not change the coded AVC data",androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION,result.result)
        assertEquals(0,MediaCodecRenderer::class.java.getDeclaredField("codecDrainAction").apply{isAccessible=true}.getInt(renderer))
        assertEquals(0,operations.flushes);assertEquals(0,operations.releases)
    }
    @Test fun nativeUhdFormatNotificationDoesNotDiscardARunningRealtekDecoder() {
        val (renderer,_)=renderer(texture=false,tcl=true);val operations=installCodec(renderer,"OMX.realtek.video.decoder")
        val result=changeFormat(renderer,3840,2160)
        assertEquals(androidx.media3.exoplayer.DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION,result.result)
        assertEquals(0,MediaCodecRenderer::class.java.getDeclaredField("codecDrainAction").apply{isAccessible=true}.getInt(renderer))
        assertEquals(0,operations.flushes);assertEquals(0,operations.releases)
    }
    @Test fun lateVideoCanCatchUpToTheNextKeyframeWithoutFlushingTheAffectedQueue() {
        for(treatAsSkipped in listOf(false,true)) {
        val (renderer,_)=renderer();val operations=installCodec(renderer)
        rendererClass.getDeclaredField("pendingCodecBuffers").apply{isAccessible=true}.setInt(renderer,4)
        var skips=0
        val stream=Proxy.newProxyInstance(SampleStream::class.java.classLoader,arrayOf(SampleStream::class.java)) {_,m,_->
            if(m.name=="skipData"){skips++;6} else if(m.name=="getFlags")0 else error(m.name)
        }
        BaseRenderer::class.java.getDeclaredField("stream").apply{isAccessible=true}.set(renderer,stream)
        val dropped=rendererClass.getDeclaredMethod("maybeDropBuffersToKeyframe",Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(renderer,1_000_000L,treatAsSkipped)
        assertEquals(true,dropped);assertEquals(1,skips);assertEquals(0,operations.flushes);assertEquals(1,operations.releases)
        val counts=MediaCodecRenderer::class.java.getDeclaredField("decoderCounters").apply{isAccessible=true}
            .get(renderer) as androidx.media3.exoplayer.DecoderCounters
        if(treatAsSkipped) {
            assertEquals(6,counts.skippedInputBufferCount);assertEquals(4,counts.skippedOutputBufferCount)
            assertEquals(0,counts.droppedBufferCount)
        } else {assertEquals(1,counts.droppedToKeyframeCount);assertEquals(10,counts.droppedBufferCount)}
        assertEquals(0,rendererClass.getDeclaredField("pendingCodecBuffers").apply{isAccessible=true}.getInt(renderer))
        }
    }
    @Test fun catchupWithoutAnAvailableKeyframeDoesNotRestartTheDecoder() {
        val (renderer,_)=renderer();val operations=installCodec(renderer)
        val stream=Proxy.newProxyInstance(SampleStream::class.java.classLoader,arrayOf(SampleStream::class.java)) {_,m,_->
            if(m.name=="skipData" || m.name=="getFlags")0 else error(m.name)
        }
        BaseRenderer::class.java.getDeclaredField("stream").apply{isAccessible=true}.set(renderer,stream)
        val dropped=rendererClass.getDeclaredMethod("maybeDropBuffersToKeyframe",Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(renderer,1_000_000L,false)
        assertEquals(false,dropped);assertEquals(0,operations.flushes);assertEquals(0,operations.releases)
    }
}
