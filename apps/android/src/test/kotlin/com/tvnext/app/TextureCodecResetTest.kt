package com.tvnext.app

import android.app.Application
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
    private class Operations { var flushes=0; var releases=0 }

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
        for(name in listOf("c2.mtk.avc.decoder","c2.mtk.hevc.decoder","OMX.MTK.VIDEO.DECODER.AVC")) {
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
    @Test fun disableAndNewChannelReleaseTheAffectedCodecBeforeMedia3CanFlushIt() {
        val (disabled,_)=renderer();val operations=installCodec(disabled)
        rendererClass.getDeclaredMethod("onDisabled").apply{isAccessible=true}.invoke(disabled)
        assertEquals(0,operations.flushes);assertEquals(1,operations.releases)

        val (changed,_)=renderer(tcl=true);val changedOps=installCodec(changed,"OMX.vendor.avc.decoder")
        val stream=Proxy.newProxyInstance(SampleStream::class.java.classLoader,arrayOf(SampleStream::class.java)) {_,m,_->
            if(m.name=="getFlags")0 else error("Unexpected sample read ${m.name}")
        }
        BaseRenderer::class.java.getDeclaredField("stream").apply{isAccessible=true}.set(changed,stream)
        rendererClass.getDeclaredMethod("onStreamChanged",Array<Format>::class.java,Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,MediaSource.MediaPeriodId::class.java).apply{isAccessible=true}
            .invoke(changed,arrayOf(Format.Builder().setSampleMimeType("video/avc").build()),0L,0L,MediaSource.MediaPeriodId("new-channel"))
        assertEquals(0,changedOps.flushes);assertEquals(1,changedOps.releases)
    }
    @Test fun textureToNativeHandoffStillProtectsThePreviousTextureQueue() {
        val (renderer,policy)=renderer()
        renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT,null)
        val operations=installCodec(renderer)
        policyClass.getDeclaredMethod("setTextureOutput",Boolean::class.javaPrimitiveType).invoke(policy,false)
        renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT,null)
        assertEquals(0,operations.flushes);assertEquals(1,operations.releases)
    }
    @Test fun lateFrameCatchupCannotFlushTheAffectedQueue() {
        val (renderer,_)=renderer();val operations=installCodec(renderer)
        val dropped=rendererClass.getDeclaredMethod("maybeDropBuffersToKeyframe",Long::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType).apply{isAccessible=true}.invoke(renderer,1_000_000L,false)
        assertEquals(false,dropped);assertEquals(0,operations.flushes);assertEquals(0,operations.releases)
    }
}
