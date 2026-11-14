package com.tvnext.app.core.player

import android.content.Context
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/** Audio-only LGPL extension: AC3/EAC3 can become PCM without a licensed system decoder. */
internal class AndroidAudioRenderersFactory @JvmOverloads constructor(context:Context,videoPath:AndroidVideoDecoderPath,
    private val audioMode:AudioOutputMode,
    private val videoOutputPolicy: AndroidVideoOutputPolicy = AndroidVideoOutputPolicy()):DefaultRenderersFactory(context) {
    val videoCodecAdapters = AmlogicMediaCodecAdapterFactory(context)
    init {
        setMediaCodecSelector(androidVideoCodecSelector(videoPath))
        setEnableDecoderFallback(true)
        setExtensionRendererMode(if(audioMode==AudioOutputMode.PASSTHROUGH)EXTENSION_RENDERER_MODE_ON else EXTENSION_RENDERER_MODE_PREFER)
    }
    override fun getCodecAdapterFactory(): androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Factory = videoCodecAdapters
    override fun buildVideoRenderers(context: Context, extensionRendererMode: Int,
        mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
        enableDecoderFallback: Boolean, eventHandler: android.os.Handler,
        eventListener: androidx.media3.exoplayer.video.VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long, out: ArrayList<androidx.media3.exoplayer.Renderer>) {
        // Native video only; the optional FFmpeg extension remains audio-only.
        val builder = androidx.media3.exoplayer.video.MediaCodecVideoRenderer.Builder(context)
            .setCodecAdapterFactory(codecAdapterFactory)
            .setMediaCodecSelector(mediaCodecSelector)
            .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
            .setEnableDecoderFallback(enableDecoderFallback)
            .setEventHandler(eventHandler).setEventListener(eventListener)
            .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
            .experimentalSetParseAv1SampleDependencies(true)
        out.add(TextureSafeVideoRenderer(builder, videoOutputPolicy))
    }
    override fun buildAudioSink(context:Context,enableFloatOutput:Boolean,enableAudioOutputPlaybackParams:Boolean):AudioSink {
        val builder=DefaultAudioSink.Builder(context).setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
        if(audioMode==AudioOutputMode.PCM_STEREO || (audioMode==AudioOutputMode.AUTO && !platformSupportsSurroundPcm()))
            builder.setAudioProcessors(arrayOf(createStereoPcmMixer()))
        return builder.build()
    }
}

internal fun createStereoPcmMixer():ChannelMixingAudioProcessor = ChannelMixingAudioProcessor().apply {
    for(channels in 1..8)putChannelMixingMatrix(ChannelMixingMatrix(channels,2,stereoMixCoefficients(channels)))
}

private fun platformSupportsSurroundPcm():Boolean = try {
    android.os.Build.VERSION.SDK_INT>=29 && android.media.AudioTrack.isDirectPlaybackSupported(
        android.media.AudioFormat.Builder().setSampleRate(48000).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_5POINT1).build(),
        android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
} catch(_:Exception){false}
