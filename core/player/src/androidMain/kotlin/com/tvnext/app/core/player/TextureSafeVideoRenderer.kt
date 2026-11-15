package com.tvnext.app.core.player

import android.os.Build
import android.view.Surface
import androidx.media3.common.Format
import androidx.media3.exoplayer.DecoderReuseEvaluation
import android.media.MediaCodec
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer

/** Main-thread surface selection, read by the playback thread before codec operations. */
internal class AndroidVideoOutputPolicy(
    private val tclDevice: Boolean = listOf(Build.MANUFACTURER, Build.BRAND).any { it.startsWith("tcl", true) },
) {
    @Volatile var textureOutput: Boolean = false

    fun needsTextureProtection(codec: MediaCodecInfo?, texture: Boolean): Boolean {
        if (!texture || codec == null || !codec.hardwareAccelerated || codec.softwareOnly) return false
        val name = codec.name.lowercase()
        return name.startsWith("c2.mtk.") || name.startsWith("omx.mtk.") ||
            name.startsWith("omx.mediatek.") || name.startsWith("omx.realtek.") || tclDevice
    }
}

/**
 * Protect direct SurfaceTexture output from vendor flush/setOutputSurface bugs.
 * Seeks, disable and keyframe catch-up release instead of flushing. Format/DRM
 * changes drain and replace the codec through Media3's normal reuse decision.
 */
internal class TextureSafeVideoRenderer(
    builder: Builder,
    private val outputPolicy: AndroidVideoOutputPolicy,
) : MediaCodecVideoRenderer(builder) {
    private var previousTextureOutput = false
    private var attachedSurface: Surface? = null
    private var pendingCodecBuffers = 0

    private fun needsProtection(): Boolean = outputPolicy.needsTextureProtection(
        codecInfo, outputPolicy.textureOutput || previousTextureOutput,
    )

    private fun releaseAffectedCodec() {
        if (needsProtection()) releaseCodec()
    }

    override fun onPositionReset(positionUs: Long, joining: Boolean, sampleStreamIsResetToKeyFrame: Boolean) {
        if (sampleStreamIsResetToKeyFrame) releaseAffectedCodec()
        super.onPositionReset(positionUs, joining, sampleStreamIsResetToKeyFrame)
    }

    override fun onDisabled() {
        releaseAffectedCodec()
        super.onDisabled()
    }

    override fun canReuseCodec(info: MediaCodecInfo, oldFormat: Format, newFormat: Format,
        isAdaptiveFormatChange: Boolean): DecoderReuseEvaluation {
        if (outputPolicy.needsTextureProtection(info, outputPolicy.textureOutput || previousTextureOutput)) {
            // Media3's format/DRM drain can request a flush without a position
            // reset. Never leave the affected queue on that unprotected path.
            return DecoderReuseEvaluation(info.name, oldFormat, newFormat,
                DecoderReuseEvaluation.REUSE_RESULT_NO, DecoderReuseEvaluation.DISCARD_REASON_WORKAROUND)
        }
        return super.canReuseCodec(info, oldFormat, newFormat, isAdaptiveFormatChange)
    }

    override fun handleMessage(messageType: Int, message: Any?) {
        if (messageType != Renderer.MSG_SET_VIDEO_OUTPUT) {
            super.handleMessage(messageType, message)
            return
        }
        val nextSurface = message as? Surface
        val changed = attachedSurface !== nextSurface
        // Keep duplicate notifications cheap; protect only an actual handoff.
        if (changed) releaseAffectedCodec()
        super.handleMessage(messageType, message)
        if (changed) {
            attachedSurface = nextSurface
            previousTextureOutput = outputPolicy.textureOutput
        }
    }

    override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
        super.onQueueInputBuffer(buffer)
        if (!configuration.tunneling &&
            (Build.VERSION.SDK_INT < 34 || getCodecBufferFlags(buffer) and MediaCodec.BUFFER_FLAG_DECODE_ONLY == 0)) {
            pendingCodecBuffers++
        }
    }

    override fun onProcessedOutputBuffer(presentationTimeUs: Long) {
        super.onProcessedOutputBuffer(presentationTimeUs)
        if (!configuration.tunneling) pendingCodecBuffers = (pendingCodecBuffers - 1).coerceAtLeast(0)
    }

    override fun resetCodecStateForFlush() {
        super.resetCodecStateForFlush()
        pendingCodecBuffers = 0
    }

    override fun maybeDropBuffersToKeyframe(positionUs: Long, treatDroppedBuffersAsSkipped: Boolean): Boolean {
        if (!needsProtection()) return super.maybeDropBuffersToKeyframe(positionUs, treatDroppedBuffersAsSkipped)
        val skipped = skipSource(positionUs)
        if (skipped == 0) return false
        // Catch up when a keyframe is available, replacing the unsafe vendor flush
        // with a codec release. Disabling catch-up leaves video behind the audio.
        if (treatDroppedBuffersAsSkipped) {
            decoderCounters.skippedInputBufferCount += skipped
            decoderCounters.skippedOutputBufferCount += pendingCodecBuffers
        } else {
            decoderCounters.droppedToKeyframeCount++
            updateDroppedBufferCounters(skipped, pendingCodecBuffers)
        }
        releaseCodec()
        maybeInitCodecOrBypass()
        return true
    }
}
