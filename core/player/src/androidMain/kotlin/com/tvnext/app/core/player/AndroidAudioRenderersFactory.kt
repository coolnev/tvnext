package com.tvnext.app.core.player

import android.content.Context
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/** Audio-only LGPL extension: AC3/EAC3 can become PCM without a licensed system decoder. */
internal class AndroidAudioRenderersFactory(context:Context,videoPath:AndroidVideoDecoderPath,
    private val audioMode:AudioOutputMode):DefaultRenderersFactory(context) {
    init {
        setMediaCodecSelector(androidVideoCodecSelector(videoPath))
        setEnableDecoderFallback(true)
        setExtensionRendererMode(if(audioMode==AudioOutputMode.PASSTHROUGH)EXTENSION_RENDERER_MODE_ON else EXTENSION_RENDERER_MODE_PREFER)
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
