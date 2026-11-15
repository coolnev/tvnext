package com.tvnext.app.core.player

import android.os.Build
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
            name.startsWith("omx.mediatek.") || tclDevice
    }
}

/**
 * Media3's c2.mtk flush workaround (b/362904942) only covers its VideoSink path.
 * PlayerView renders directly to SurfaceTexture. Release an affected decoder before
 * operations that would flush it. Surface changes use Media3's vendor workarounds. Neither
 * navigation nor an animation frame triggers these operations.
 */
internal class TextureSafeVideoRenderer(
    builder: Builder,
    private val outputPolicy: AndroidVideoOutputPolicy,
) : MediaCodecVideoRenderer(builder) {
    private var previousTextureOutput = false
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

    override fun handleMessage(messageType: Int, message: Any?) {
        super.handleMessage(messageType, message)
        if (messageType == Renderer.MSG_SET_VIDEO_OUTPUT) previousTextureOutput = outputPolicy.textureOutput
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
